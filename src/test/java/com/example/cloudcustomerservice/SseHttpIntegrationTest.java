package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.stream.SseConnections;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** 真正启动 MVC/Tomcat，使用 Cookie/CSRF 的 HTTP 客户端读取未结束响应，避免 MockMvc 聚合结果掩盖流式故障。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-test-placeholder",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/cloud_customer_service_test",
    "handoff.accounts.customer1001.password=only-test-stream-password",
    "handoff.accounts.customer2002.password=only-test-stream-password",
    "handoff.accounts.support9001.password=only-test-stream-password",
    "app.sse.poll-interval=50ms","app.sse.heartbeat-interval=100ms","app.sse.state-lifetime=3s",
    "app.sse.model-idle-timeout=1s","app.sse.model-deadline=3s"})
@ActiveProfiles({"local","knowledge"}) @EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_TESTS",matches="true")
class SseHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SseConnections connections;
    @MockitoBean ChatModel model;
    final List<Stream> open=new ArrayList<>();
    Client customer,other,agent;
    int clientPort;
    Process nginx;
    final boolean proxy="true".equals(System.getenv("RUN_NGINX_TESTS"));
    /** 第十九章可选第二轮：真实本机 Nginx 代理到随机 Tomcat 端口，不放宽应用回环权限。 */
    void startProxy()throws Exception{
        clientPort=port;if(!proxy)return;
        try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){clientPort=socket.getLocalPort();}
        var prefix=java.nio.file.Files.createTempDirectory("cs-acceptance-nginx-");
        var config=prefix.resolve("nginx.conf");
        java.nio.file.Files.writeString(config,"pid nginx.pid; error_log error.log; events {} http { access_log off; server { listen 127.0.0.1:"+clientPort+
            "; location / { proxy_pass http://127.0.0.1:"+port+"; proxy_http_version 1.1; proxy_set_header Host $http_host; proxy_set_header Connection \"\"; proxy_buffering off; proxy_cache off; proxy_read_timeout 60s; gzip off; } } }");
        nginx=new ProcessBuilder(System.getenv().getOrDefault("NGINX_BINARY","nginx"),"-p",prefix+"/","-c",config.toString(),"-g","daemon off;")
            .redirectErrorStream(true).redirectOutput(prefix.resolve("process.log").toFile()).start();
        await().atMost(Duration.ofSeconds(5)).until(()->{try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",clientPort),100);return nginx.isAlive();}catch(IOException e){return false;}});
    }
    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("cloud_customer_service_test");
        jdbc.update("delete from ai.cs_message");jdbc.update("delete from ai.cs_conversation");
        startProxy();
        customer=new Client("customer1001");other=new Client("customer2002");agent=new Client("support9001");
    }
    @AfterEach void cleanup()throws Exception{
        try{
            for(var stream:open)stream.close();
            await().atMost(Duration.ofSeconds(6)).untilAsserted(()->assertThat(connections.active()).isZero());
        }finally{if(nginx!=null){nginx.destroy();if(!nginx.waitFor(3,TimeUnit.SECONDS))nginx.destroyForcibly();}}
        jdbc.update("delete from ai.cs_message");jdbc.update("delete from ai.cs_conversation");
    }
    String create()throws Exception{return customer.json("/api/handoff/conversations","POST",null,201).get("conversationId").asText();}
    String statePath(String id){return "/api/handoff/conversations/"+id+"/events";}
    ChatResponse chunk(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    /** Cookie、表单和登录后新 CSRF 走真实服务器；所有密码都是隔离的测试常量。 */
    final class Client {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        String csrf;
        Client(String username)throws Exception{
            csrf=json("/internal/handoff/session","GET",null,200).get("csrfToken").asText();
            var request=base("/internal/handoff/login").header("X-CSRF-TOKEN",csrf).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password=only-test-stream-password")).build();
            assertThat(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
            csrf=json("/internal/handoff/session","GET",null,200).get("csrfToken").asText();
        }
        HttpRequest.Builder base(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+clientPort+path)).timeout(Duration.ofSeconds(5));}
        JsonNode json(String path,String method,String data,int expected)throws Exception{
            var b=base(path).header("Content-Type","application/json");if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
            var r=client.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);return r.body().isEmpty()?null:SseHttpIntegrationTest.this.json.readTree(r.body());
        }
        Stream stream(String path,String method,String data,String lastEventId)throws Exception{
            var b=base(path).header("Accept","text/event-stream").header("X-CSRF-TOKEN",csrf);
            if(lastEventId!=null)b.header("Last-Event-ID",lastEventId);
            if(data!=null)b.header("Content-Type","application/json");
            var r=client.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofInputStream());
            var s=new Stream(r);open.add(s);return s;
        }
    }
    record Event(String name,String data){ }
    final class Stream implements AutoCloseable {
        final HttpResponse<InputStream> response;final BufferedReader reader;
        Stream(HttpResponse<InputStream> response){this.response=response;reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8));}
        Event next()throws Exception{
            // 使用有限等待包住阻塞读；无事件/无 EOF 时测试失败，不能无期限卡住整个构建。
            var pool=Executors.newSingleThreadExecutor();
            try{return pool.submit(()->{
                String name="",line;var data=new StringBuilder();
                while((line=reader.readLine())!=null){
                    if(line.startsWith("event:"))name=line.substring(6).stripLeading();
                    if(line.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());}
                    if(line.isEmpty()&&!data.isEmpty())return new Event(name,data.toString());
                }return null;
            }).get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        }
        @Override public void close()throws IOException{response.body().close();}
    }
    @Test void mvcPushesRealStateChangesAndClosedEndsConnection()throws Exception{
        String id=create();var stream=customer.stream(statePath(id),"GET",null,null);
        assertThat(stream.response.statusCode()).isEqualTo(200);
        assertThat(stream.response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(stream.response.headers().firstValue("Cache-Control")).contains("no-store");
        // Nginx 消费 X-Accel-* 控制头，默认不回传；代理时检查真实首帧和后续行为。
        if(!proxy)assertThat(stream.response.headers().firstValue("X-Accel-Buffering")).contains("no");
        assertThat(json.readTree(stream.next().data()).get("mode").asText()).isEqualTo("BOT");
        customer.json("/api/handoff/conversations/"+id+"/handoff","POST",null,200);
        assertThat(json.readTree(stream.next().data()).get("mode").asText()).isEqualTo("WAITING_HUMAN");
        agent.json("/api/support/conversations/"+id+"/accept","POST",null,200);
        assertThat(json.readTree(stream.next().data()).get("mode").asText()).isEqualTo("HUMAN_ACTIVE");
        agent.json("/api/support/conversations/"+id+"/close","POST",null,200);
        assertThat(json.readTree(stream.next().data()).get("mode").asText()).isEqualTo("CLOSED");assertThat(stream.next()).isNull();
        verify(model,never()).stream(any(Prompt.class));verify(model,never()).call(any(Prompt.class));
    }
    @Test void ownershipLoginAndCsrfFailBeforeOpeningPrivateStream()throws Exception{
        var id=create();var foreign=other.stream(statePath(id),"GET",null,null);
        assertThat(foreign.response.statusCode()).isEqualTo(404);
        var anonymous=HttpClient.newHttpClient().send(customer.base(statePath(id)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(anonymous.statusCode()).isEqualTo(401);
        var noCsrf=customer.client.send(customer.base("/internal/stream-lab/answer").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"你好\"}")).build(),HttpResponse.BodyHandlers.ofString());assertThat(noCsrf.statusCode()).isEqualTo(403);
        var invalid=customer.stream("/internal/stream-lab/answer","POST","{\"question\":\" \"}",null);assertThat(invalid.response.statusCode()).isEqualTo(400);
        verify(model,never()).stream(any(Prompt.class));
    }
    @Test void reconnectIgnoresHistoricalIdAndReadsCurrentSnapshotWithoutActions()throws Exception{
        var id=create();var first=customer.stream(statePath(id),"GET",null,null);assertThat(first.next()).isNotNull();first.close();
        var receipt=customer.json("/api/handoff/conversations/"+id+"/handoff","POST",null,200);
        var second=customer.stream(statePath(id),"GET",null,"999999");var current=json.readTree(second.next().data());
        assertThat(current.get("handoffId")).isEqualTo(receipt.get("handoffId"));assertThat(current.get("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai.cs_message",Integer.class)).isEqualTo(1);verify(model,never()).stream(any(Prompt.class));
    }
    @Test void logoutRevokesExistingStateReads()throws Exception{
        var s=customer.stream(statePath(create()),"GET",null,null);assertThat(s.next()).isNotNull();
        customer.json("/internal/handoff/logout","POST",null,204);
        assertThat(s.next().name()).isEqualTo("stream.failure");assertThat(s.next()).isNull();
    }
    @Test void trueModelChunksArriveWhileUpstreamStillOpenAndNoBusinessHistoryIsWritten()throws Exception{
        var sink=Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();when(model.stream(any(Prompt.class))).thenReturn(sink.asFlux());
        var s=customer.stream("/internal/stream-lab/answer","POST","{\"question\":\"一般问题\"}",null);
        assertThat(s.next().name()).isEqualTo("turn.started");sink.tryEmitNext(chunk("甲"));
        var first=s.next();assertThat(first.name()).isEqualTo("answer.delta");assertThat(json.readTree(first.data()).get("text").asText()).isEqualTo("甲");
        sink.tryEmitNext(chunk(" \n乙"));assertThat(json.readTree(s.next().data()).get("text").asText()).isEqualTo(" \n乙");
        sink.tryEmitComplete();assertThat(s.next().name()).isEqualTo("turn.completed");assertThat(s.next()).isNull();
        verify(model,times(1)).stream(argThat((Prompt p)->{var o=(ToolCallingChatOptions)p.getOptions();return Boolean.FALSE.equals(o.getInternalToolExecutionEnabled())&&o.getMaxTokens()==512&&o.getToolCallbacks().isEmpty()&&o.getToolNames().isEmpty();}));
        verify(model,never()).call(any(Prompt.class));assertThat(jdbc.queryForObject("select count(*) from ai.cs_message",Integer.class)).isZero();
    }
    @Test void midstreamErrorIsFailedAndNeverLeakesProviderDetails()throws Exception{
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(chunk("前半句")),Flux.error(new IllegalStateException("provider-secret"))));
        var s=customer.stream("/internal/stream-lab/answer","POST","{\"question\":\"一般问题\"}",null);
        assertThat(s.next().name()).isEqualTo("turn.started");assertThat(s.next().name()).isEqualTo("answer.delta");
        var last=s.next();assertThat(last.name()).isEqualTo("turn.failed");assertThat(last.data()).doesNotContain("provider-secret");assertThat(s.next()).isNull();
    }
    @Test void disconnectAndIdleDeadlineReleaseModelAndConnection()throws Exception{
        var cancelled=new AtomicBoolean();when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(()->cancelled.set(true)));
        var s=customer.stream("/internal/stream-lab/answer","POST","{\"question\":\"一般问题\"}",null);assertThat(s.next().name()).isEqualTo("turn.started");s.close();
        await().atMost(Duration.ofSeconds(4)).untilAsserted(()->{assertThat(cancelled).isTrue();assertThat(connections.active()).isZero();});
    }
    @Test void globalToolsFailClosedBeforeAnyModelSubscription()throws Exception{
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().toolNames(Set.of("dangerousDefault")).build());
        var s=customer.stream("/internal/stream-lab/answer","POST","{\"question\":\"一般问题\"}",null);assertThat(s.response.statusCode()).isEqualTo(503);verify(model,never()).stream(any(Prompt.class));
    }
    @Test void accountConnectionLimitReturns429AndCancellationFreesCapacity()throws Exception{
        String path=statePath(create());for(int i=0;i<4;i++){var s=customer.stream(path,"GET",null,null);assertThat(s.next()).isNotNull();}
        var fifth=customer.stream(path,"GET",null,null);assertThat(fifth.response.statusCode()).isEqualTo(429);
        for(var s:open)s.close();await().atMost(Duration.ofSeconds(4)).untilAsserted(()->assertThat(connections.active()).isZero());
        var reopened=customer.stream(path,"GET",null,null);assertThat(reopened.response.statusCode()).isEqualTo(200);assertThat(reopened.next()).isNotNull();
    }
}
