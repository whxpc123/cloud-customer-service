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

/** 查询已返回但本地记录尚未保存时真正杀掉 JVM；重启只按原事件核查，不再次创建。 */
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class ReconciliationRestartTest {
    @Test void interruptedLookupSurvivesRestartAndNewCheckRepairsWithoutCreating()throws Exception{
        var f=new PersistentDraftRestartTest();f.work=Files.createTempDirectory("ch29-process-");f.ready=f.work.resolve("ready");f.calls=f.work.resolve("calls");f.blocked=f.work.resolve("lookup-returned");
        f.db=new PostgreSQLContainer<>(DockerImageName.parse(AcceptanceDatabase.IMAGE).asCompatibleSubstituteFor("postgres")).withDatabaseName("chapter29_restart_test");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var creates=new AtomicInteger();var lookups=new AtomicInteger();
        var report=new LinkedHashMap<String,Object>();
        try{
            f.db.start();f.profiles="local,knowledge,outbox-delivery";
            f.extraEnvironment=new HashMap<>(Map.of("REMOTE_AFTER_SALE_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort()+"/applications",
                "REMOTE_AFTER_SALE_TOKEN","test-only-token","HANDOFF_ACCOUNTS_SUPPORT9001_PASSWORD","process-test-only-password","CH29_BLOCK_AFTER_LOOKUP","true"));
            var first=f.new Client(f.start("first"));report.put("firstPid",f.child.pid());
            try(var c=connect(f);var st=c.createStatement()){st.execute("CREATE TABLE ai.ch29_remote(event_id uuid PRIMARY KEY,payload jsonb NOT NULL,remote_id text NOT NULL)");}
            server.createContext("/applications",x->{try{
                var body=f.json.readTree(x.getRequestBody().readAllBytes());UUID event=UUID.fromString(body.path("eventId").asText());
                boolean lookup=x.getRequestURI().getPath().endsWith("/lookup");String remote;
                try(var c=connect(f)) {
                    if(!lookup){creates.incrementAndGet();try(var st=c.prepareStatement("INSERT INTO ai.ch29_remote VALUES (?,?::jsonb,?)")){st.setObject(1,event);st.setString(2,body.toString());st.setString(3,"remote-"+event);st.executeUpdate();}}
                    else lookups.incrementAndGet();
                    try(var st=c.prepareStatement("SELECT remote_id,payload::text FROM ai.ch29_remote WHERE event_id=?")){st.setObject(1,event);try(var rs=st.executeQuery()){assertThat(rs.next()).isTrue();remote=rs.getString(1);assertThat(f.json.readTree(rs.getString(2))).isEqualTo(body);}}
                }
                // 创建已提交，却返回无效回执，使真实投递器进入 REVIEW；查询返回原持久化结果。
                Object response=lookup?Map.of("eventId",event,"state","PERSISTED","receipt",Map.of("eventId",event,"applicationId",body.path("applicationId").asText(),"remoteApplicationId",remote,"status","PERSISTED")):Map.of("status","LOST_ACK");
                byte[] bytes=f.json.writeValueAsBytes(response);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
            }catch(Exception e){throw new RuntimeException(e);}finally{x.close();}});server.start();
            String conversation=first.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();
            String taskId=f.create(first,conversation),task=PersistentDraftRestartTest.path(taskId);
            first.call(task+"/turns","POST",f.turn(0,"first-fracture"),200);first.call(task+"/drafts","POST","{\"expectedTaskVersion\":2}",201);
            first.call(task+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",200);
            var op=first.call(SubmissionPersistenceTest.ROOT,"POST",f.json.writeValueAsString(Map.of("taskId",taskId,"draftVersion",1,"deliveryProfile","AFTER_SALE_V1")),200);
            String path=SubmissionPersistenceTest.ROOT+"/"+op.path("operationId").asText();first.call(path+"/decision","POST","{\"decision\":\"APPROVE\",\"accepted\":true}",200);
            first.call(path+"/execute","POST","{}",200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(first.call(path+"/delivery","GET",null,200).path("status").asText()).isEqualTo("REVIEW"));
            String event=first.call(path+"/delivery","GET",null,200).path("eventId").asText(),api="/api/support/outbox/"+event;
            int calls=f.callCount();var operator=f.new Client(Integer.parseInt(Files.readString(f.ready)),"support9001");
            var pending=CompletableFuture.runAsync(()->{try{operator.call(api+"/reconcile","POST","{}",200);}catch(Exception expected){/* 独立进程强杀会断开连接。 */}});
            f.awaitFile(f.blocked,Duration.ofSeconds(10));assertThat(lookups.get()).isEqualTo(1);
            f.child.destroyForcibly();assertThat(f.child.waitFor(10,TimeUnit.SECONDS)).isTrue();pending.get(10,TimeUnit.SECONDS);
            f.extraEnvironment.put("CH29_BLOCK_AFTER_LOOKUP","false");var second=f.new Client(f.start("second"),"support9001");report.put("secondPid",f.child.pid());
            var before=second.call(api,"GET",null,200);assertThat(before.path("event").path("status").asText()).isEqualTo("REVIEW");
            assertThat(before.path("audits").get(0).path("status").asText()).isEqualTo("STARTED");assertThat(lookups.get()).isEqualTo(1);
            assertThat(second.call(api+"/reconcile","POST","{}",200).path("repaired").asBoolean()).isTrue();
            var after=second.call(api,"GET",null,200);assertThat(after.path("event").path("status").asText()).isEqualTo("DELIVERED");
            assertThat(after.path("audits").size()).isEqualTo(2);assertThat(after.path("event").path("attemptCount").asInt()).isEqualTo(1);
            assertThat(creates.get()).isEqualTo(1);assertThat(lookups.get()).isEqualTo(2);assertThat(f.callCount()).isEqualTo(calls);
            try(var c=connect(f);var st=c.createStatement();var rs=st.executeQuery("SELECT (SELECT count(*) FROM ai.ch29_remote),(SELECT count(*) FROM ai.cs_after_sale_application),(SELECT count(*) FROM ai.cs_outbox)")){rs.next();for(int i=1;i<=3;i++)assertThat(rs.getInt(i)).isEqualTo(1);}
            report.put("startedSurvivesActualCrash",true);report.put("newQueryRepairs",true);report.put("createRequests",creates.get());report.put("lookupRequests",lookups.get());report.put("modelCallsDuringReconcile",0);
        }finally{server.stop(0);if(f.child!=null&&f.child.isAlive()){f.child.destroyForcibly();f.child.waitFor(10,TimeUnit.SECONDS);}f.db.stop();Files.writeString(Path.of("target/chapter-29-process-restart.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
    }
    Connection connect(PersistentDraftRestartTest f)throws SQLException{return DriverManager.getConnection(f.db.getJdbcUrl(),f.db.getUsername(),f.db.getPassword());}
}
