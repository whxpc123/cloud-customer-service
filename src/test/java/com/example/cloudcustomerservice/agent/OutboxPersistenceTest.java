package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.example.cloudcustomerservice.agent.persistence.*;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.submission.*;
import com.example.cloudcustomerservice.outbox.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.cloudcustomerservice.submission.IdempotentSubmissionService.Decision;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;
import static com.example.cloudcustomerservice.submission.IdempotentSubmissionService.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 第27章：真实数据库验证同事务事件、租约抢占、过期写回、回执丢失与客户隔离。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=submission-test-only-password","handoff.accounts.customer2002.password=submission-test-only-password",
    "handoff.accounts.support9001.password=submission-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class OutboxPersistenceTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PersistentDraftTaskService tasks;
    @Autowired DraftTaskRepository repository;
    @Autowired DraftApplicationService drafts;
    @Autowired IdempotentSubmissionService service;
    @Autowired SubmissionGraph graph;
    @Autowired OutboxWriter writer;
    @Autowired OutboxStore store;
    @Autowired OutboxQueryService query;
    @MockitoBean ChatModel model;
    @MockitoBean ReturnAssessmentService assessment;
    final Actor actor=new Actor("tenant-yunshan",1001);
    final Access access=new Access(actor,id->DraftTaskTest.receipt(id,com.example.cloudcustomerservice.handoff.HandoffModel.Mode.BOT,0));
    static final String ROOT="/internal/draft-tasks/submission/operations";
    Client owner,other,support;
    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        jdbc.update("update ai.cs_outbox set status='REVIEW',lease_token=null,lease_until=null where status in ('PENDING','SENDING')");
        owner=new Client("customer1001");other=new Client("customer2002");support=new Client("support9001");
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("controlled-submission").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->{Prompt p=i.getArgument(0);return DraftRevisionTest.isExtract(p)?DraftAgentTest.text("{\"userDescription\":\"验收用：右侧按钮按不动，质量尚未核验\",\"requestedHandling\":\"登记待审核申请\"}"):DraftTaskTest.next(p);});
        when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
    }
    /** 不手造草稿 UUID：走正式会话、任务运行、结构化发布，再记录具体版本确认。 */
    UUID confirmed()throws Exception{
        String c=owner.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();
        UUID id=UUID.fromString(owner.call("/internal/draft-tasks/tasks","POST",json.writeValueAsString(Map.of("conversationId",c,"orderNo","A10001","reason","QUALITY_ISSUE")),201).path("taskId").asText());
        tasks.continueTask(access,id,0L,"验收样例：按钮按不动，请整理售后候选");
        drafts.generate(access,id,2L);drafts.confirm(access,id,1L,true);return id;
    }
    Operation approved(UUID task){var op=service.prepare(actor,task,1);return service.decide(actor,op.operationId(),Decision.APPROVE);}
    long count(UUID task){return jdbc.queryForObject("select count(*) from ai.cs_after_sale_application where task_id=?",Long.class,task);}
    String op(UUID id){return ROOT+"/"+id;}
    void expire(UUID id){jdbc.update("update ai.cs_submit_operation set expires_at=clock_timestamp()-interval '1 minute' where operation_id=?",id);}
    void denied(Runnable work,int status){assertThatThrownBy(work::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(status));}

    /** 只在本次新建、已确认的测试草稿上明确选择同步范围。 */
    Operation remoteOperation()throws Exception {
        var o=service.prepare(actor,confirmed(),1,DeliveryProfile.AFTER_SALE_V1);
        return service.decide(actor,o.operationId(),Decision.APPROVE);
    }
    OutboxStore.Claim pending()throws Exception {
        var o=remoteOperation();service.submit(actor,o.operationId());return store.claimOne().orElseThrow();
    }
    String state(UUID e){return jdbc.queryForObject("select status from ai.cs_outbox where event_id=?",String.class,e);}
    void due(UUID e){jdbc.update("update ai.cs_outbox set next_attempt_at=clock_timestamp()-interval '1 second' where event_id=?",e);}
    void stale(UUID e){jdbc.update("update ai.cs_outbox set lease_until=clock_timestamp()-interval '1 second' where event_id=?",e);}

    @Test void approvedRemoteCreationIsAtomicAndReplayNeverCreatesAnotherEvent()throws Exception{
        var o=remoteOperation();var r=service.submit(actor,o.operationId());var d=query.delivery(actor,o.operationId());
        assertThat(d.status()).isEqualTo("PENDING");assertThat(d.applicationId()).isEqualTo(r.applicationId());assertThat(d.relayEnabled()).isFalse();
        var payload=json.readTree(jdbc.queryForObject("select payload::text from ai.cs_outbox where event_id=?",String.class,d.eventId()));
        assertThat(payload.path("eventId").asText()).isEqualTo(d.eventId().toString());
        assertThat(payload.properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("schemaVersion","eventId","eventType","applicationId","operationId","tenantId","orderNo","draftVersion","userStatement","occurredAt");
        assertThat(payload.path("userStatement")).isEqualTo(service.detail(actor,o.operationId()).body().path("userStatement"));
        clearInvocations(model);assertThat(service.submit(actor,o.operationId())).isEqualTo(r);
        assertThat(query.delivery(actor,o.operationId()).eventId()).isEqualTo(d.eventId());
        assertThat(jdbc.queryForObject("select count(*) from ai.cs_outbox where application_id=?",Long.class,r.applicationId())).isEqualTo(1);
        verify(model,never()).call(any(Prompt.class));
    }
    @Test void oldDefaultRemainsLocalAndChangingScopeOrDecisionIsRejected()throws Exception{
        var o=approved(confirmed());service.submit(actor,o.operationId());
        assertThat(o.deliveryProfile()).isEqualTo("LOCAL_ONLY");assertThat(query.delivery(actor,o.operationId()).status()).isEqualTo("LOCAL_ONLY");
        assertThat(query.delivery(actor,o.operationId()).eventId()).isNull();
        denied(()->service.prepare(actor,o.taskId(),1,DeliveryProfile.AFTER_SALE_V1),409);
        assertThatThrownBy(()->jdbc.update("update ai.cs_submit_operation set delivery_profile='AFTER_SALE_V1' where operation_id=?",o.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update ai.cs_submit_operation set decided_by=2002 where operation_id=?",o.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->writer.appendIfRequired(UUID.randomUUID())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void outboxInsertionFailureRollsBackApplicationAndAllBusinessState()throws Exception{
        var o=remoteOperation();long version=repository.owned(actor,o.taskId()).version();
        jdbc.execute("create function ai.ch27_fail_outbox() returns trigger language plpgsql as $$ begin raise exception 'test outbox failure'; end $$");
        jdbc.execute("create trigger ch27_fail_outbox before insert on ai.cs_outbox for each row execute function ai.ch27_fail_outbox()");
        try{assertThatThrownBy(()->service.submit(actor,o.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("drop trigger ch27_fail_outbox on ai.cs_outbox");jdbc.execute("drop function ai.ch27_fail_outbox()");}
        assertThat(count(o.taskId())).isZero();assertThat(service.detail(actor,o.operationId()).operation().status()).isEqualTo("APPROVED");
        assertThat(repository.owned(actor,o.taskId()).version()).isEqualTo(version);assertThat(query.delivery(actor,o.operationId()).status()).isEqualTo("NOT_CREATED");
    }
    @Test void failureAfterOutboxInsertionStillRollsEverythingBack()throws Exception{
        var o=remoteOperation();
        jdbc.execute("create function ai.ch27_fail_close() returns trigger language plpgsql as $$ begin if NEW.status='CLOSED' then raise exception 'test close failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger ch27_fail_close before update on ai.cs_draft_task for each row execute function ai.ch27_fail_close()");
        try{assertThatThrownBy(()->service.submit(actor,o.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("drop trigger ch27_fail_close on ai.cs_draft_task");jdbc.execute("drop function ai.ch27_fail_close()");}
        assertThat(count(o.taskId())).isZero();assertThat(jdbc.queryForObject("select count(*) from ai.cs_outbox where operation_id=?",Long.class,o.operationId())).isZero();
        assertThat(service.detail(actor,o.operationId()).operation().status()).isEqualTo("APPROVED");
    }
    @Test void eventIdentityAndBodyCannotBeRewrittenOrDuplicated()throws Exception{
        var c=pending();
        for(String change:List.of("payload='{}'","event_id=gen_random_uuid()","operation_id=gen_random_uuid()","tenant_id='other'"))
            assertThatThrownBy(()->jdbc.update("update ai.cs_outbox set "+change+" where event_id=?",c.eventId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("insert into ai.cs_outbox(event_id,application_id,operation_id,tenant_id,destination,event_type,payload) select gen_random_uuid(),application_id,operation_id,tenant_id,destination,event_type,payload from ai.cs_outbox where event_id=?",c.eventId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void concurrentWorkersGetDifferentEventsAndSkipLockedRows()throws Exception{
        var a=remoteOperation();service.submit(actor,a.operationId());var b=remoteOperation();service.submit(actor,b.operationId());
        var e=query.delivery(actor,a.operationId()).eventId();
        try(var cn=dataSource.getConnection()){
            cn.setAutoCommit(false);try(var st=cn.prepareStatement("select event_id from ai.cs_outbox where event_id=? for update")){st.setObject(1,e);st.executeQuery().close();}
            var next=store.claimOne().orElseThrow();assertThat(next.eventId()).isEqualTo(query.delivery(actor,b.operationId()).eventId());cn.rollback();
        }
        var pool=Executors.newFixedThreadPool(2);try{
            var gate=new CountDownLatch(1);Callable<Optional<OutboxStore.Claim>> claim=()->{gate.await();return store.claimOne();};
            var x=pool.submit(claim);var y=pool.submit(claim);gate.countDown();
            var values=List.of(x.get(8,TimeUnit.SECONDS),y.get(8,TimeUnit.SECONDS));assertThat(values.stream().flatMap(Optional::stream).map(OutboxStore.Claim::eventId).toList()).containsExactly(e);
        }finally{pool.shutdownNow();}
    }
    @Test void expiredLeaseKeepsEventAndPayloadButFencesOldSuccessAndFailure()throws Exception{
        var a=pending();stale(a.eventId());var b=store.claimOne().orElseThrow();
        assertThat(b.eventId()).isEqualTo(a.eventId());assertThat(b.payload()).isEqualTo(a.payload());assertThat(b.leaseToken()).isNotEqualTo(a.leaseToken());assertThat(b.attemptCount()).isEqualTo(2);
        assertThat(store.delivered(a,"late-result")).isFalse();assertThat(store.failed(a,"HTTP_503",true)).isFalse();
        assertThat(store.delivered(b,"remote-saved")).isTrue();assertThat(store.claimOne()).isEmpty();assertThat(state(a.eventId())).isEqualTo("DELIVERED");
    }
    @Test void retryBackoffAndEighthFailureStopWithoutChangingIdentity()throws Exception{
        var c=pending();for(int n=1;n<=8;n++){
            assertThat(c.attemptCount()).isEqualTo(n);assertThat(store.failed(c,"HTTP_503",true)).isTrue();
            if(n<8){assertThat(state(c.eventId())).isEqualTo("PENDING");assertThat(store.claimOne()).isEmpty();
                assertThat(jdbc.queryForObject("select extract(epoch from next_attempt_at-clock_timestamp()) from ai.cs_outbox where event_id=?",Double.class,c.eventId())).isGreaterThan(0).isLessThanOrEqualTo(300);
                due(c.eventId());var next=store.claimOne().orElseThrow();assertThat(next.eventId()).isEqualTo(c.eventId());c=next;}
        }
        assertThat(state(c.eventId())).isEqualTo("REVIEW");assertThat(store.claimOne()).isEmpty();
    }
    @Test void lastLeaseCrashIsCollectedAndPermanentFailureDoesNotRetry()throws Exception{
        var c=pending();jdbc.update("update ai.cs_outbox set attempt_count=8 where event_id=?",c.eventId());stale(c.eventId());store.reviewExhaustedLeases();
        assertThat(state(c.eventId())).isEqualTo("REVIEW");assertThat(store.delivered(c,"late")).isFalse();
        var other=pending();assertThat(store.failed(other,"ACK_MISMATCH",false)).isTrue();assertThat(state(other.eventId())).isEqualTo("REVIEW");assertThat(store.claimOne()).isEmpty();
    }
    @Test void lostAckAfterRemoteCommitResendsSameEventAndGetsSamePersistentResult()throws Exception{
        var c=pending();var received=new AtomicInteger();var endpoint=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        // 接收器使用独立数据库事务：真实提交后故意断开第一次响应，并非总返回 200 的桩。
        jdbc.execute("create table if not exists ai.ch27_test_receiver(event_id uuid primary key,payload jsonb not null,remote_id text not null)");
        endpoint.createContext("/receive",exchange->{try{
            var body=json.readTree(exchange.getRequestBody().readAllBytes());UUID id=UUID.fromString(body.path("eventId").asText());
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(id.toString());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only-token");
            jdbc.update("insert into ai.ch27_test_receiver values(?,cast(? as jsonb),?) on conflict(event_id) do nothing",id,body.toString(),"remote-"+UUID.randomUUID());
            assertThat(json.readTree(jdbc.queryForObject("select payload::text from ai.ch27_test_receiver where event_id=?",String.class,id))).isEqualTo(body);
            if(received.incrementAndGet()==1){exchange.close();return;}
            String remoteId=jdbc.queryForObject("select remote_id from ai.ch27_test_receiver where event_id=?",String.class,id);
            byte[] ack=json.writeValueAsBytes(Map.of("eventId",id,"applicationId",body.path("applicationId").asText(),"remoteApplicationId",remoteId,"status","PERSISTED"));
            exchange.sendResponseHeaders(200,ack.length);exchange.getResponseBody().write(ack);
        }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});endpoint.start();
        try{
            var remote=new RemoteAfterSaleClient(json,URI.create("http://127.0.0.1:"+endpoint.getAddress().getPort()+"/receive"),"test-only-token"){
                @Override public Ack deliver(OutboxStore.Claim claim){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return super.deliver(claim);}
            };
            assertThatThrownBy(()->remote.deliver(c)).isInstanceOfSatisfying(RemoteAfterSaleClient.DeliveryFailure.class,f->assertThat(f.retryable()).isTrue());
            assertThat(jdbc.queryForObject("select count(*) from ai.ch27_test_receiver where event_id=?",Long.class,c.eventId())).isEqualTo(1);
            // 模拟工作者随回执一起丢失：不写 failed，依靠租约到期重新领取。
            stale(c.eventId());new OutboxRelay(store,remote).tick();
            assertThat(state(c.eventId())).isEqualTo("DELIVERED");assertThat(received.get()).isEqualTo(2);
            assertThat(jdbc.queryForObject("select remote_application_id from ai.cs_outbox where event_id=?",String.class,c.eventId()))
                .isEqualTo(jdbc.queryForObject("select remote_id from ai.ch27_test_receiver where event_id=?",String.class,c.eventId()));
        }finally{endpoint.stop(0);}
    }
    @Test void wrongAckStopsInReviewWithoutInventingDelivery()throws Exception{
        var o=remoteOperation();service.submit(actor,o.operationId());var endpoint=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        endpoint.createContext("/",x->{byte[] ack=json.writeValueAsBytes(Map.of("eventId",UUID.randomUUID(),"applicationId",UUID.randomUUID(),"remoteApplicationId","r1","status","PERSISTED"));x.sendResponseHeaders(200,ack.length);x.getResponseBody().write(ack);x.close();});endpoint.start();
        try{new OutboxRelay(store,new RemoteAfterSaleClient(json,URI.create("http://127.0.0.1:"+endpoint.getAddress().getPort()),"test-only-token")).tick();}
        finally{endpoint.stop(0);}
        var d=query.delivery(actor,o.operationId());assertThat(d.status()).isEqualTo("REVIEW");assertThat(d.lastErrorCode()).isEqualTo("ACK_MISMATCH");assertThat(d.remoteApplicationId()).isNull();assertThat(d.deliveredAt()).isNull();
    }
    @Test void statusAndMetricsRequireOwnershipAndDoNotSendOrLeakPayload()throws Exception{
        var o=remoteOperation();service.submit(actor,o.operationId());String path=op(o.operationId())+"/delivery";
        var d=owner.call(path,"GET",null,200);assertThat(d.path("status").asText()).isEqualTo("PENDING");assertThat(d.has("payload")).isFalse();assertThat(d.has("leaseToken")).isFalse();
        other.call(path,"GET",null,404);support.call(path,"GET",null,403);
        denied(()->query.delivery(new Actor("other-tenant",1001),o.operationId()),404);
        assertThat(query.summary(new Actor("tenant-yunshan",2002)).pending()).isZero();assertThat(query.summary(actor).pending()).isEqualTo(1);
        assertThat(query.summary(actor).oldestPendingSeconds()).isNotNull();assertThat(query.summary(actor).attemptDistribution()).containsKey(0);
        owner.call(ROOT,"POST",json.writeValueAsString(Map.of("taskId",o.taskId(),"draftVersion",1,"deliveryProfile","LOCAL_ONLY")),409);
        owner.call(ROOT,"POST",json.writeValueAsString(Map.of("taskId",o.taskId(),"draftVersion",1,"deliveryProfile","UNKNOWN")),400);
        assertThat(query.delivery(actor,o.operationId()).attemptCount()).isZero();
    }
    final class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
        Client(String user)throws Exception{csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
            var r=http.send(request("/internal/handoff/login").header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString("username="+user+"&password=submission-test-only-password")).build(),HttpResponse.BodyHandlers.discarding());assertThat(r.statusCode()).isEqualTo(204);csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();}
        JsonNode call(String path,String method,String body,int status)throws Exception{var b=request(path).header("Content-Type","application/json");if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
            var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(status);return r.body().isEmpty()?json.nullNode():json.readTree(r.body());}
    }
    HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));}
}
