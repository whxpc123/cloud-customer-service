package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 三个独立 JVM：停投递器时先创建，再启用领取并崩溃，最后按原事件找回远端持久化结果。 */
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class OutboxRestartTest {
    @Test void pendingAndClaimedEventRecoverAcrossRealProcessCrashes()throws Exception{
        var f=new PersistentDraftRestartTest();f.work=Files.createTempDirectory("ch27-process-");f.ready=f.work.resolve("ready");f.calls=f.work.resolve("calls");f.blocked=f.work.resolve("blocked");
        f.db=new PostgreSQLContainer<>(DockerImageName.parse(AcceptanceDatabase.IMAGE).asCompatibleSubstituteFor("postgres")).withDatabaseName("chapter27_restart_test");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var received=new CountDownLatch(1);var release=new CountDownLatch(1);var attempts=new AtomicInteger();var report=new LinkedHashMap<String,Object>();
        try{f.db.start();var first=f.new Client(f.start("first-no-relay"));report.put("firstPid",f.child.pid());
            String conversation=first.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();String taskId=f.create(first,conversation),task=PersistentDraftRestartTest.path(taskId);
            first.call(task+"/turns","POST",f.turn(0,"first-fracture"),200);first.call(task+"/drafts","POST","{\"expectedTaskVersion\":2}",201);first.call(task+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",200);
            var op=first.call(SubmissionPersistenceTest.ROOT,"POST",f.json.writeValueAsString(Map.of("taskId",taskId,"draftVersion",1,"deliveryProfile","AFTER_SALE_V1")),200);String path=SubmissionPersistenceTest.ROOT+"/"+op.path("operationId").asText();
            first.call(path+"/decision","POST","{\"decision\":\"APPROVE\",\"accepted\":true}",200);var receipt=first.call(path+"/execute","POST","{}",200).path("receipt");
            var delivery=first.call(path+"/delivery","GET",null,200);assertThat(delivery.path("status").asText()).isEqualTo("PENDING");String event=delivery.path("eventId").asText();int calls=f.callCount();
            f.child.destroyForcibly();assertThat(f.child.waitFor(10,TimeUnit.SECONDS)).isTrue();
            try(var cn=connect(f);var st=cn.createStatement()){st.execute("create table ai.ch27_receiver(event_id uuid primary key,payload jsonb not null,remote_id text not null)");}
            server.createContext("/",x->{try{
                var body=f.json.readTree(x.getRequestBody().readAllBytes());String remoteId;
                try(var cn=connect(f)){
                    // JDBC 自动提交把去重记录与模拟业务结果作为同一行持久化；正文变化拒绝回执。
                    try(var insert=cn.prepareStatement("insert into ai.ch27_receiver values(?,cast(? as jsonb),?) on conflict do nothing")){insert.setObject(1,UUID.fromString(body.path("eventId").asText()));insert.setString(2,body.toString());insert.setString(3,"remote-"+UUID.randomUUID());insert.executeUpdate();}
                    try(var q=cn.prepareStatement("select payload::text,remote_id from ai.ch27_receiver where event_id=?")){q.setObject(1,UUID.fromString(event));try(var rs=q.executeQuery()){assertThat(rs.next()).isTrue();assertThat(f.json.readTree(rs.getString(1))).isEqualTo(body);remoteId=rs.getString(2);}}
                }
                if(attempts.incrementAndGet()==1){received.countDown();release.await(40,TimeUnit.SECONDS);x.close();return;}
                byte[] bytes=f.json.writeValueAsBytes(Map.of("eventId",event,"applicationId",receipt.path("applicationId").asText(),"remoteApplicationId",remoteId,"status","PERSISTED"));x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
            }catch(Exception e){throw new RuntimeException(e);}finally{x.close();}});server.start();
            f.profiles="local,knowledge,outbox-delivery";f.extraEnvironment=Map.of("REMOTE_AFTER_SALE_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort(),"REMOTE_AFTER_SALE_TOKEN","process-test-only-token");
            var second=f.new Client(f.start("second-claim"));report.put("secondPid",f.child.pid());assertThat(received.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(second.call(path+"/delivery","GET",null,200).path("status").asText()).isEqualTo("SENDING");
            f.child.destroyForcibly();assertThat(f.child.waitFor(10,TimeUnit.SECONDS)).isTrue();release.countDown();
            // 使用数据库时钟推进过期条件，避免测试靠睡眠 30 秒碰运气；第三 JVM 仍运行真实定时任务。
            try(var cn=connect(f);var st=cn.prepareStatement("update ai.cs_outbox set lease_until=clock_timestamp()-interval '1 second' where event_id=?")){st.setObject(1,UUID.fromString(event));assertThat(st.executeUpdate()).isEqualTo(1);}
            var third=f.new Client(f.start("third-recover"));report.put("thirdPid",f.child.pid());
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(third.call(path+"/delivery","GET",null,200).path("status").asText()).isEqualTo("DELIVERED"));
            var restored=third.call(path+"/delivery","GET",null,200);assertThat(restored.path("eventId")).isEqualTo(delivery.path("eventId"));assertThat(restored.path("attemptCount").asInt()).isEqualTo(2);
            assertThat(third.call(path+"/result","GET",null,200).path("receipt")).isEqualTo(receipt);assertThat(f.callCount()).isEqualTo(calls);assertThat(attempts.get()).isEqualTo(2);
            try(var cn=connect(f);var st=cn.createStatement();var r=st.executeQuery("select (select count(*) from ai.ch27_receiver),(select count(*) from ai.cs_outbox),(select count(*) from ai.cs_after_sale_application)")){r.next();assertThat(r.getInt(1)).isEqualTo(1);assertThat(r.getInt(2)).isEqualTo(1);assertThat(r.getInt(3)).isEqualTo(1);}
            report.put("pendingSurvivesRestart",true);report.put("claimedEventSurvivesCrash",true);report.put("httpRequests",2);report.put("remoteResults",1);report.put("outboxEvents",1);report.put("newModelCallsForDelivery",0);
        }finally{release.countDown();server.stop(0);if(f.child!=null&&f.child.isAlive()){f.child.destroyForcibly();f.child.waitFor(10,TimeUnit.SECONDS);}f.db.stop();Files.writeString(Path.of("target/chapter-27-process-restart.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
    private Connection connect(PersistentDraftRestartTest f)throws SQLException{return DriverManager.getConnection(f.db.getJdbcUrl(),f.db.getUsername(),f.db.getPassword());}
}
