package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** 启动两次真实 JVM 并强制终止第一份；证明恢复来自 PostgreSQL，不是同一 Saver 缓存还活着。 */
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class PersistentDraftRestartTest {
    final ObjectMapper json=new ObjectMapper();
    Process child;
    Path work,ready,calls,blocked;
    PostgreSQLContainer<?> db;
    @Test void readyCompletedAndInterruptedTasksSurviveActualProcessRestart()throws Exception {
        work=Files.createTempDirectory("ch22-process-");ready=work.resolve("ready");calls=work.resolve("calls");blocked=work.resolve("blocked");
        var report=new LinkedHashMap<String,Object>();
        db=new PostgreSQLContainer<>(DockerImageName.parse(AcceptanceDatabase.IMAGE).asCompatibleSubstituteFor("postgres")).withDatabaseName("chapter22_restart_test");
        try {
            db.start();var first=new Client(start("first"));report.put("firstPid",child.pid());
            String conversation=first.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();
            String readyId=create(first,conversation),finishedId=create(first,conversation),crashId=create(first,conversation);
            var completed=first.call(path(finishedId)+"/turns","POST",turn(0,"first-fracture"),200);assertThat(completed.path("task").path("status").asText()).isEqualTo("CANDIDATE_UNVALIDATED");
            String thread=thread(finishedId);int count=callCount();assertThat(count).isEqualTo(3);
            var pending=CompletableFuture.runAsync(()->{try{first.call(path(crashId)+"/turns","POST",turn(0,"BLOCK_FOR_CRASH"),200);}catch(Exception ignored){/* 预期 TCP 连接随进程退出而中断。 */}});
            awaitFile(blocked,Duration.ofSeconds(15));assertThat(callCount()).isEqualTo(4);
            child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();pending.get(10,TimeUnit.SECONDS);
            var second=new Client(start("second"));report.put("secondPid",child.pid());
            assertThat(second.call(path(readyId),"GET",null,200).path("task").path("status").asText()).isEqualTo("READY");
            var restored=second.call(path(finishedId),"GET",null,200);assertThat(restored.path("lastRun")).isEqualTo(completed.path("lastRun"));assertThat(callCount()).isEqualTo(4);
            assertThat(thread(finishedId)).isEqualTo(thread);report.put("restoredWithoutModel",true);
            var next=second.call(path(finishedId)+"/turns","POST",turn(2,"second-button"),200);
            assertThat(next.path("task").path("version").asInt()).isEqualTo(4);assertThat(next.path("state").path("userMessages").asInt()).isEqualTo(2);
            assertThat(next.path("lastRun").path("runId")).isNotEqualTo(completed.path("lastRun").path("runId"));assertThat(callCount()).isEqualTo(7);
            second.call(path(finishedId)+"/turns","POST",turn(2,"重复发送"),409);
            var crashed=second.call(path(crashId),"GET",null,200);assertThat(crashed.path("task").path("status").asText()).isEqualTo("RUNNING");assertThat(crashed.path("lastRun").isNull()).isTrue();
            second.call(path(crashId)+"/turns","POST",turn(1,"普通续跑"),409);assertThat(callCount()).isEqualTo(7);
            second.call(path(readyId)+"/turns","POST",turn(0,"重启后第一轮"),200);assertThat(callCount()).isEqualTo(10);
            report.put("completedTask",finishedId);report.put("readyTaskStartsAfterRestart",true);report.put("crashedTaskRemainsRunning",true);report.put("duplicateRejected",true);report.put("modelCalls",callCount());
            report.put("userMessagesAfterContinuation",next.path("state").path("userMessages").asInt());
            try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var statement=connection.createStatement();var result=statement.executeQuery("select count(*) from ai.cs_message")){
                result.next();assertThat(result.getInt(1)).isZero();report.put("formalMessages",0);
            }
        }finally{
            if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}if(db!=null)db.stop();
            Files.writeString(Path.of("target/chapter-22-process-restart.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
    /** 使用原应用与真实迁移，连接信息仅通过环境传递，不输出到命令或验收报告。 */
    int start(String label)throws Exception {
        Files.deleteIfExists(ready);
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-DsocksNonProxyHosts=localhost|127.*|[::1]","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),PersistentProcessFixture.class.getName(),
                "--spring.profiles.active=local,knowledge","--server.port=0","--app.ai.log-payload=false","--spring.config.import=optional:file:/nonexistent-ch22-test-config");
        var env=builder.environment();env.remove("DASHSCOPE_API_KEY");env.put("SPRING_AI_DASHSCOPE_API_KEY","offline-placeholder");
        env.put("SPRING_DATASOURCE_URL",db.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",db.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",db.getPassword());
        env.put("HANDOFF_ACCOUNTS_CUSTOMER1001_PASSWORD","process-test-only-password");env.put("CH22_READY_FILE",ready.toString());env.put("CH22_CALLS_FILE",calls.toString());env.put("CH22_BLOCK_FILE",blocked.toString());
        child=builder.redirectErrorStream(true).redirectOutput(work.resolve(label+".log").toFile()).start();awaitFile(ready,Duration.ofSeconds(50));return Integer.parseInt(Files.readString(ready));
    }
    void awaitFile(Path file,Duration timeout)throws Exception{long end=System.nanoTime()+timeout.toNanos();while(!Files.exists(file)&&child.isAlive()&&System.nanoTime()<end)Thread.sleep(100);assertThat(Files.exists(file)).as("子进程就绪文件；诊断目录 %s",work).isTrue();}
    int callCount()throws Exception{return Files.exists(calls)?Files.readAllLines(calls).size():0;}
    String thread(String id)throws Exception{try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.prepareStatement("select thread_id from ai.cs_draft_task where task_id=?")){s.setObject(1,UUID.fromString(id));try(var r=s.executeQuery()){assertThat(r.next()).isTrue();return r.getString(1);}}}
    String create(Client client,String conversation)throws Exception{return client.call("/internal/draft-tasks/tasks","POST",json.writeValueAsString(Map.of("conversationId",conversation,"orderNo","A10001","reason","QUALITY_ISSUE")),201).path("taskId").asText();}
    static String path(String id){return "/internal/draft-tasks/tasks/"+id;}
    String turn(long version,String message)throws Exception{return json.writeValueAsString(Map.of("expectedVersion",version,"message",message));}
    final class Client {
        final String root;String csrf;
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Client(int port)throws Exception{root="http://127.0.0.1:"+port;csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
            var response=http.send(HttpRequest.newBuilder(URI.create(root+"/internal/handoff/login")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
                .POST(HttpRequest.BodyPublishers.ofString("username=customer1001&password=process-test-only-password")).build(),HttpResponse.BodyHandlers.discarding());assertThat(response.statusCode()).isEqualTo(204);
            csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
        }
        JsonNode call(String path,String method,String body,int expected)throws Exception{var builder=HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");if(csrf!=null)builder.header("X-CSRF-TOKEN",csrf);
            var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);return response.body().isEmpty()?json.nullNode():json.readTree(response.body());}
    }
}
