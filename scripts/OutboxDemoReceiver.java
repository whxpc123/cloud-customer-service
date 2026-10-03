import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

/**
 * 第27章本机教学接收器，不属于业务 JAR，也不是生产 Inbox 实现。
 * 只接受 tenant-yunshan；固定 eventId + 固定 JSON 对应一份落盘回执。
 * 同一目录只允许单进程持锁；故意丢第一次回执用于验证发送方恢复。
 */
class OutboxDemoReceiver {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Path ROOT=Path.of(".local/outbox-demo"),CONFIG=Path.of(".local/outbox-demo.properties");
    private static final Set<String> FIELDS=Set.of("schemaVersion","eventId","eventType","applicationId","operationId","tenantId","orderNo","draftVersion","userStatement","occurredAt");
    private static String token;
    private static boolean dropFirst;

    /** 仅绑定回环地址，生成的服务令牌写入被 Git 忽略的权限 600 文件，绝不输出令牌。 */
    public static void main(String[] args)throws Exception {
        Files.createDirectories(ROOT);
        var config=new Properties();
        if(Files.exists(CONFIG))try(var input=Files.newInputStream(CONFIG)){config.load(input);}
        else{
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);token=HexFormat.of().formatHex(bytes);
            config.setProperty("app.remote-after-sale.endpoint","http://127.0.0.1:18081/applications");config.setProperty("app.remote-after-sale.token",token);
            Files.createFile(CONFIG,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try(var out=Files.newOutputStream(CONFIG)){config.store(out,"Local chapter 27 demonstration only");}
        }
        token=config.getProperty("app.remote-after-sale.token");if(token==null||token.isBlank())throw new IllegalStateException("本机演示凭证文件无效");
        dropFirst=Arrays.asList(args).contains("--drop-first-ack");
        // OS 文件锁避免两个进程对同一目录各自生成不同回执；进程退出即释放。
        var lockChannel=FileChannel.open(ROOT.resolve("receiver.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        var lock=lockChannel.tryLock();if(lock==null)throw new IllegalStateException("此演示目录已有接收器在运行");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",18081),0);
        server.createContext("/applications",OutboxDemoReceiver::receive);server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);try{lock.release();lockChannel.close();}catch(Exception ignored){}}));
        System.out.println("教学接收器已启动：http://127.0.0.1:18081/applications；记录保存在 .local/outbox-demo；首次回执丢弃="+dropFirst);
    }

    /** 单进程顺序处理；先原子落盘，再返回或故意丢回执；所有读取/写入只使用经 UUID 验证的文件名。 */
    private static synchronized void receive(HttpExchange exchange) {
        try{
            if(!"POST".equals(exchange.getRequestMethod())||!"/applications".equals(exchange.getRequestURI().getPath())){respond(exchange,405,Map.of("code","POST_REQUIRED"));return;}
            String auth=Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst("Authorization"),"");
            if(!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))){respond(exchange,401,Map.of("code","UNAUTHORIZED"));return;}
            byte[] bytes=exchange.getRequestBody().readNBytes(65537);if(bytes.length>65536){respond(exchange,413,Map.of("code","TOO_LARGE"));return;}
            JsonNode payload=JSON.readTree(bytes);
            if(payload==null||!payload.isObject()||!payload.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(FIELDS)
                    ||payload.path("schemaVersion").asInt()!=1||!"AFTER_SALE_APPLICATION_CREATED".equals(payload.path("eventType").asText())
                    ||!"tenant-yunshan".equals(payload.path("tenantId").asText())||!payload.path("userStatement").isObject()){
                respond(exchange,400,Map.of("code","CONTRACT_MISMATCH"));return;
            }
            UUID event=UUID.fromString(payload.path("eventId").asText()),app=UUID.fromString(payload.path("applicationId").asText());
            if(!event.toString().equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))){respond(exchange,400,Map.of("code","KEY_MISMATCH"));return;}
            Path saved=ROOT.resolve(event+".json");JsonNode ack;
            boolean created=!Files.exists(saved);
            if(!created){
                JsonNode previous=JSON.readTree(Files.readAllBytes(saved));
                if(!previous.path("payload").equals(payload)){respond(exchange,409,Map.of("code","EVENT_CONTENT_CHANGED"));return;}
                ack=previous.path("ack");
            }else{
                ack=JSON.valueToTree(Map.of("eventId",event,"applicationId",app,"remoteApplicationId","DEMO-"+UUID.randomUUID(),"status","PERSISTED"));
                byte[] record=JSON.writeValueAsBytes(Map.of("payload",payload,"ack",ack));
                Path temp=Files.createTempFile(ROOT,"pending-",".tmp");
                try{
                    try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(record);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
                    Files.move(temp,saved,StandardCopyOption.ATOMIC_MOVE);
                    try(var directory=FileChannel.open(ROOT,StandardOpenOption.READ)){directory.force(true);}
                }finally{Files.deleteIfExists(temp);}
            }
            System.out.println("eventId="+event+" persisted=true replay="+!created);
            if(created&&dropFirst){System.out.println("eventId="+event+" 首次回执故意断开，落盘记录保留");return;}
            respond(exchange,created?201:200,ack);
        }catch(IllegalArgumentException|com.fasterxml.jackson.core.JsonProcessingException ex){try{respond(exchange,400,Map.of("code","INVALID_REQUEST"));}catch(Exception ignored){}}
        catch(Exception ex){System.err.println("接收未确认，errorType="+ex.getClass().getSimpleName());try{respond(exchange,503,Map.of("code","UNCONFIRMED"));}catch(Exception ignored){}}
        finally{exchange.close();}
    }
    private static void respond(HttpExchange exchange,int status,Object value)throws Exception{
        byte[] bytes=JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}
