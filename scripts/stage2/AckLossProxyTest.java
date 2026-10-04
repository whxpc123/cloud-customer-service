import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 纯 JDK 本机 HTTP 合同测试。只证明代理的行为，不证明售后业务成功。 */
public final class AckLossProxyTest {
    static int cases;
    static final UUID EVENT = UUID.randomUUID();
    static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static void check(boolean ok, String description) { if (!ok) throw new AssertionError(description); }
    static HttpResponse<String> send(AckLossProxy proxy, String path, String key, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.port()+path))
                .header("Idempotency-Key",key).header("Authorization","Bearer synthetic-test-only")
                .header("Content-Type","application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);
        var pool = Executors.newFixedThreadPool(12); upstream.setExecutor(pool);
        var status = new AtomicInteger(201); var calls = new AtomicInteger();
        var received = new AtomicReference<byte[]>(); var auth = new AtomicReference<String>();
        var contentType = new AtomicReference<String>(); var slow = new AtomicBoolean(false);
        upstream.createContext("/", e -> {
            calls.incrementAndGet(); received.set(e.getRequestBody().readAllBytes());
            auth.set(e.getRequestHeaders().getFirst("Authorization")); contentType.set(e.getRequestHeaders().getFirst("Content-Type"));
            try {
                if (slow.get()) Thread.sleep(9000);
                byte[] bytes = status.get()==299 ? new byte[AckLossProxy.LIMIT+1] : "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                if(status.get()==302)e.getResponseHeaders().set("Location","/must-not-follow");
                e.getResponseHeaders().set("Content-Type","application/json");
                e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);
            }catch(Exception ignored){ /* 超时测试关闭了客户端。 */ }finally{e.close();}
        }); upstream.start();
        try (var proxy = new AckLossProxy(0, URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()),EVENT,8,null)) {
            proxy.start(); String path=AckLossProxy.CREATE; String key=EVENT.toString();
            String body="{ \"eventId\": \""+key+"\", \"text\":\"中文\\n否定词\" }";
            var first=send(proxy,path,key,body);
            check(first.statusCode()==503&&first.body().contains("E2E_SUCCESS_ACK_SUPPRESSED"),"成功之后才替换回执");cases++;
            check(Arrays.equals(received.get(),body.getBytes(StandardCharsets.UTF_8))&&"Bearer synthetic-test-only".equals(auth.get())
                &&"application/json;charset=UTF-8".equals(contentType.get()),"正文及身份头逐字保留");cases++;
            check(send(proxy,path,UUID.randomUUID().toString(),body).statusCode()==201,"非目标事件不注入");cases++;
            check(send(proxy,path+"/lookup",key,body).statusCode()==201&&proxy.suppressed.get()==1,"查询不注入");cases++;
            status.set(401);check(send(proxy,path,key,body).statusCode()==401&&proxy.suppressed.get()==1,"鉴权失败不消耗预算");cases++;
            status.set(500);check(send(proxy,path,key,body).statusCode()==500&&proxy.suppressed.get()==1,"上游失败原样返回");cases++;
            status.set(302);int before=calls.get();check(send(proxy,path,key,body).statusCode()==302&&calls.get()==before+1,"不跟随重定向");cases++;
            status.set(200);
            var requests=new ArrayList<CompletableFuture<Integer>>();
            for(int i=0;i<14;i++)requests.add(CompletableFuture.supplyAsync(()->{try{return send(proxy,path,key,body).statusCode();}catch(Exception e){throw new CompletionException(e);}}));
            long suppressed=requests.stream().map(CompletableFuture::join).filter(s->s==503).count();
            check(suppressed==7&&proxy.suppressed.get()==8&&proxy.successes.get()==15,"并发恰好注入N次");cases++;
            check(send(proxy,path,key,body).statusCode()==200,"预算耗尽后200原样返回");cases++;
            before=calls.get();check(send(proxy,path,key,"x".repeat(AckLossProxy.LIMIT+1)).statusCode()==413&&calls.get()==before,"请求上限且不转发");cases++;
            status.set(299);check(send(proxy,path,key,body).statusCode()==502,"响应上限立即取消");cases++;
            before=calls.get();check(send(proxy,path+"?other=1",key,body).statusCode()==404&&calls.get()==before,"拒绝额外查询参数");cases++;
            var get=CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.port()+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(get.statusCode()==404,"只开放两个POST");cases++;
            status.set(201);slow.set(true);long start=System.nanoTime();check(send(proxy,path,key,body).statusCode()==502
                &&TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<10,"完整上游响应超时有界");cases++;
        } finally { upstream.stop(0);pool.shutdownNow(); }
        try { new AckLossProxy(0,URI.create("http://example.com"),EVENT,8,null);throw new AssertionError("不允许远端目标"); }
        catch(IllegalArgumentException expected){cases++;}
        System.out.println("PASS: "+cases+" proxy contract cases; tool evidence only");
    }
}
