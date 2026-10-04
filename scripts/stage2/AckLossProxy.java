import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第30章故障工具，纯 JDK17。只监听回环地址，转发固定接收端的两个 POST 路径。
 * 接收端先完成真实事务并返回成功，工具才把目标事件的前 N 次成功回执替换为 503。
 * 这模拟“成功结果没有被发送方确认”，不是 TCP 丢包，也不是业务状态修改器。
 */
public final class AckLossProxy implements AutoCloseable {
    static final String CREATE = "/integration/after-sales/applications";
    static final int LIMIT = 65_536;
    final HttpServer server;
    final URI origin;
    final String event;
    final int firstN;
    final Path stats;
    final AtomicInteger creates = new AtomicInteger(), lookups = new AtomicInteger();
    final AtomicInteger successes = new AtomicInteger(), suppressed = new AtomicInteger();
    final ExecutorService workers = new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AckLossProxy(int port, URI origin, UUID event, int firstN, Path stats) throws IOException {
        // 工具仅用于本机验收，拒绝远程主机、嵌入凭证、路径、查询串和重定向目的地。
        if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                || origin.getPort() < 1 || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || firstN < 0 || firstN > 100 || port < 0 || port > 65535)
            throw new IllegalArgumentException("需要固定本机 origin、合法端口与 0..100 次故障预算");
        this.origin = origin; this.event = event.toString(); this.firstN = firstN; this.stats = stats;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        if (server.getAddress().getPort() == origin.getPort()) { server.stop(0); throw new IllegalArgumentException("不能代理自身"); }
        server.createContext("/", this::handle); server.setExecutor(workers);
    }
    public void start() throws IOException { writeStats(); server.start(); }
    public int port() { return server.getAddress().getPort(); }

    /** 请求及响应均最多 64 KiB；总交换超过 10 秒关闭，完整上游响应最多等待 8 秒。 */
    void handle(HttpExchange exchange) {
        var deadline = watchdog.schedule(exchange::close, 10, TimeUnit.SECONDS);
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (!"POST".equals(exchange.getRequestMethod()) || exchange.getRequestURI().getRawQuery() != null
                    || !(CREATE.equals(path) || (CREATE + "/lookup").equals(path))) {
                reply(exchange, 404, "{\"code\":\"PATH_NOT_ALLOWED\"}".getBytes()); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(LIMIT + 1);
            if (body.length > LIMIT) { reply(exchange, 413, new byte[0]); return; }
            boolean lookup = path.endsWith("/lookup");
            (lookup ? lookups : creates).incrementAndGet();
            var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            // 原始正文及必要身份头不改写；不记录令牌，不替调用方补权限。
            for (String name : List.of("Authorization", "Idempotency-Key", "Content-Type")) {
                var values = exchange.getRequestHeaders().get(name);
                if (values != null) for (String value : values) builder.header(name, value);
            }
            var pending = http.sendAsync(builder.build(), info -> new BoundedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(8, TimeUnit.SECONDS); }
            catch (Exception error) { pending.cancel(true); throw error; }
            int status = response.statusCode();
            byte[] result = response.body();
            // 协议规定 Idempotency-Key 必须等于正文 eventId，真实接收端验证后才会返回 200/201。
            // 不用正则解析 JSON，也不把查询成功或鉴权失败当作创建成功。
            if (!lookup && event.equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    && (status == 200 || status == 201)) {
                int ordinal = successes.incrementAndGet();
                if (ordinal <= firstN) {
                    suppressed.incrementAndGet(); status = 503;
                    result = "{\"code\":\"E2E_SUCCESS_ACK_SUPPRESSED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            response.headers().firstValue("Content-Type").ifPresent(v -> exchange.getResponseHeaders().set("Content-Type", v));
            reply(exchange, status, result);
        } catch (Exception error) {
            try { reply(exchange, 502, "{\"code\":\"PROXY_UPSTREAM_UNCONFIRMED\"}".getBytes()); }
            catch (IOException ignored) { /* 客户端已关闭，不能推断上游未入库。 */ }
        } finally {
            deadline.cancel(false); exchange.close();
            try { writeStats(); } catch (IOException error) { System.err.println("PROXY_STATS_WRITE_FAILED"); }
        }
    }
    static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length != 0) exchange.getResponseBody().write(bytes);
    }
    /** 接收流达到上限立即取消，不先把无限响应读到堆内存。 */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> done = new CompletableFuture<>();
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return done; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for (var item : items) {
                if (item.remaining() > LIMIT - buffer.size()) {
                    subscription.cancel(); done.completeExceptionally(new IOException("UPSTREAM_TOO_LARGE")); return;
                }
                byte[] bytes = new byte[item.remaining()]; item.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { done.completeExceptionally(error); }
        public void onComplete() { done.complete(buffer.toByteArray()); }
    }
    /** 文件只有计数和事件编号；原子替换使验收器不会读取半个 JSON。 */
    synchronized void writeStats() throws IOException {
        if (stats == null) return;
        Path temp = Files.createTempFile(stats.toAbsolutePath().getParent(), "proxy-stats-", ".tmp");
        try {
            Files.writeString(temp, "{\"eventId\":\"%s\",\"createRequests\":%d,\"lookupRequests\":%d,\"targetSuccesses\":%d,\"suppressed\":%d}"
                    .formatted(event, creates.get(), lookups.get(), successes.get(), suppressed.get()));
            Files.move(temp, stats, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public void close() { server.stop(0); workers.shutdownNow(); watchdog.shutdownNow(); }
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("参数：端口 接收端origin eventId 前N次 计数文件");
        var proxy = new AckLossProxy(Integer.parseInt(args[0]), URI.create(args[1]), UUID.fromString(args[2]),
                Integer.parseInt(args[3]), Path.of(args[4]));
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::close)); proxy.start();
        System.out.println("ACK_LOSS_PROXY_READY port=" + proxy.port());
    }
}
