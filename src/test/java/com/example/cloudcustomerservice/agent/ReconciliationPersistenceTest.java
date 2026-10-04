package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.example.cloudcustomerservice.agent.persistence.*;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.submission.*;
import com.example.cloudcustomerservice.outbox.*;
import com.sun.net.httpserver.HttpServer;
import com.example.cloudcustomerservice.reconcile.*;
import static com.example.cloudcustomerservice.reconcile.ReconcileModel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** 第29章：用真实数据库与登录/CSRF 验证版本防护、事务原子性、只读核查和权限。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=submission-test-only-password","handoff.accounts.customer2002.password=submission-test-only-password",
    "handoff.accounts.support9001.password=submission-test-only-password", "handoff.accounts.support9002.password=submission-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class ReconciliationPersistenceTest {
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
    @Autowired OutboxReconciliationStore reconciliations;
    @MockitoBean RemoteAfterSaleClient remote;
    final com.example.cloudcustomerservice.handoff.HandoffModel.Actor operator=new com.example.cloudcustomerservice.handoff.HandoffModel.Actor("tenant-yunshan",9001);
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

    OutboxStore.Claim review()throws Exception{var c=pending();assertThat(store.failed(c,"ACK_MISMATCH",false)).isTrue();clearInvocations(model);return c;}
    String api(UUID id){return "/api/support/outbox/"+id;}
    Evidence persisted(){return new Evidence(Finding.PERSISTED,"remote-verified","VERIFIED_RECEIPT");}
    void reply(OutboxStore.Claim c){when(remote.lookup(c.eventId(),c.payload())).thenAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new Reply(c.eventId(),"PERSISTED",new Ack(c.eventId(),c.applicationId(),"remote-verified","PERSISTED"));});}

    @Test void verifiedReceiptOnlyRepairsDeliveryAndKeepsBusinessAndAttemptIdentity()throws Exception {
        var c=review();reply(c);
        var result=support.call(api(c.eventId())+"/reconcile","POST","{}",200);
        assertThat(result.path("repaired").asBoolean()).isTrue();assertThat(state(c.eventId())).isEqualTo("DELIVERED");
        var detail=support.call(api(c.eventId()),"GET",null,200);
        assertThat(detail.path("event").path("attemptCount").asInt()).isEqualTo(1);
        assertThat(detail.path("event").path("reconcileVersion").asInt()).isEqualTo(1);
        assertThat(detail.path("audits").get(0).path("requestedBy").asInt()).isEqualTo(9001);
        assertThat(detail.toString()).doesNotContain("userStatement","payload","leaseToken");
        assertThat(jdbc.queryForObject("select status from ai.cs_after_sale_application where application_id=?",String.class,c.applicationId())).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from ai.cs_outbox where application_id=?",Long.class,c.applicationId())).isEqualTo(1);
        support.call(api(c.eventId())+"/reconcile","POST","{}",409);
        verify(remote,times(1)).lookup(any(),anyString());verify(remote,never()).deliver(any());verify(model,never()).call(any(Prompt.class));
        support.call("/api/support/outbox/summary","GET",null,200);
        support.call("/api/support/outbox?status=DELIVERED","GET",null,200);
    }

    @ParameterizedTest @EnumSource(value=Finding.class,names={"NOT_OBSERVED","PAYLOAD_CONFLICT","RECEIVER_INCONSISTENT","QUERY_UNAVAILABLE","INVALID_RESPONSE"})
    void inconclusiveEvidenceIsRecordedWithoutResendingOrClearingOriginalFailure(Finding finding)throws Exception {
        var c=review();var w=reconciliations.begin(operator,c.eventId());
        var result=reconciliations.record(w,new Evidence(finding,null,"SAFE_CODE"));
        assertThat(result.outboxStatus()).isEqualTo("REVIEW");assertThat(result.repaired()).isFalse();
        var row=jdbc.queryForMap("select attempt_count,last_error_code,reconcile_version,remote_application_id from ai.cs_outbox where event_id=?",c.eventId());
        assertThat(row.get("attempt_count")).isEqualTo(1);assertThat(row.get("last_error_code")).isEqualTo("ACK_MISMATCH");
        assertThat(row.get("reconcile_version")).isEqualTo(1L);assertThat(row.get("remote_application_id")).isNull();
        assertThat(store.claimOne()).isEmpty();verifyNoInteractions(remote);verify(model,never()).call(any(Prompt.class));
    }

    @Test void staleObservationCannotOverwriteRepairAndReverseOrderingNeedsFreshQuery()throws Exception {
        var c=review();var early=reconciliations.begin(operator,c.eventId());var later=reconciliations.begin(operator,c.eventId());
        assertThat(reconciliations.record(later,persisted()).repaired()).isTrue();
        var staleResult=reconciliations.record(early,new Evidence(Finding.NOT_OBSERVED,null,"NOT_OBSERVED"));
        assertThat(staleResult.auditStatus()).isEqualTo("STALE");assertThat(staleResult.outboxStatus()).isEqualTo("DELIVERED");
        var other=review();var positive=reconciliations.begin(operator,other.eventId());var unknown=reconciliations.begin(operator,other.eventId());
        reconciliations.record(unknown,new Evidence(Finding.NOT_OBSERVED,null,"NOT_OBSERVED"));
        assertThat(reconciliations.record(positive,persisted()).auditStatus()).isEqualTo("STALE");assertThat(state(other.eventId())).isEqualTo("REVIEW");
        assertThat(reconciliations.record(reconciliations.begin(operator,other.eventId()),persisted()).repaired()).isTrue();
    }

    @Test void simultaneousCompletionsApplyOnlyOneAndKeepBothAudits()throws Exception {
        var c=review();var a=reconciliations.begin(operator,c.eventId());var b=reconciliations.begin(operator,c.eventId());
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var x=pool.submit(()->{gate.await();return reconciliations.record(a,persisted());});
            var y=pool.submit(()->{gate.await();return reconciliations.record(b,persisted());});gate.countDown();
            var results=List.of(x.get(8,TimeUnit.SECONDS),y.get(8,TimeUnit.SECONDS));
            assertThat(results.stream().filter(OutboxReconciliationStore.Result::repaired).count()).isEqualTo(1);
            assertThat(results.stream().map(OutboxReconciliationStore.Result::auditStatus)).containsExactlyInAnyOrder("RECORDED","STALE");
        }finally{pool.shutdownNow();}
    }

    @Test void auditFailureRollsBackRepairAndStartedRemainsForInvestigation()throws Exception {
        var c=review();var w=reconciliations.begin(operator,c.eventId());
        jdbc.execute("create function ai.ch29_fail_audit() returns trigger language plpgsql as $$ begin raise exception 'synthetic audit failure'; end $$");
        jdbc.execute("create trigger ch29_fail_audit before update on ai.cs_outbox_reconciliation for each row execute function ai.ch29_fail_audit()");
        try{assertThatThrownBy(()->reconciliations.record(w,persisted())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("drop trigger ch29_fail_audit on ai.cs_outbox_reconciliation");jdbc.execute("drop function ai.ch29_fail_audit()");}
        assertThat(state(c.eventId())).isEqualTo("REVIEW");
        assertThat(jdbc.queryForObject("select reconcile_version from ai.cs_outbox where event_id=?",Long.class,c.eventId())).isZero();
        assertThat(jdbc.queryForObject("select status from ai.cs_outbox_reconciliation where check_id=?",String.class,w.checkId())).isEqualTo("STARTED");
        assertThat(reconciliations.record(reconciliations.begin(operator,c.eventId()),persisted()).repaired()).isTrue();
    }

    @Test void replayedOrMismatchedAuditCannotRepairEvenWhenObservationLooksPositive()throws Exception {
        var c=review();var w=reconciliations.begin(operator,c.eventId());
        reconciliations.record(w,new Evidence(Finding.NOT_OBSERVED,null,"NOT_OBSERVED"));
        var forged=new OutboxReconciliationStore.Work(w.checkId(),w.eventId(),w.applicationId(),w.operator(),1,w.payload());
        assertThatThrownBy(()->reconciliations.record(forged,persisted())).isInstanceOf(IllegalStateException.class);
        assertThat(state(c.eventId())).isEqualTo("REVIEW");
        assertThat(jdbc.queryForObject("select reconcile_version from ai.cs_outbox where event_id=?",Long.class,c.eventId())).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("update ai.cs_outbox_reconciliation set repaired=true where check_id=?",w.checkId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void permissionsCsrfIdentityInputsAndTenantScopeAreEnforcedBeforeRemoteCall()throws Exception {
        var c=review();String p=api(c.eventId());
        owner.call(p,"GET",null,403);other.call(p+"/reconcile","POST","{}",403);
        var ordinary=new Client("support9002");ordinary.call(p,"GET",null,403);ordinary.call(p+"/reconcile","POST","{}",403);
        String csrf=support.csrf;support.csrf=null;support.call(p+"/reconcile","POST","{}",403);support.csrf=csrf;
        for(String body:List.of("{\"remoteId\":\"fake\"}","{\"tenantId\":\"other\"}","{\"force\":true}","null"))support.call(p+"/reconcile","POST",body,400);
        support.call(api(UUID.randomUUID()),"GET",null,404);
        denied(()->reconciliations.begin(new com.example.cloudcustomerservice.handoff.HandoffModel.Actor("other-tenant",9001),c.eventId()),404);
        verifyNoInteractions(remote);
    }

    @Test void unavailableHttpAndWrongReceiptAreObservationsNotEvidenceOfAbsence()throws Exception {
        var c=review();when(remote.lookup(any(),anyString())).thenThrow(new RemoteAfterSaleClient.DeliveryFailure("LOOKUP_HTTP_403",false));
        var r=support.call(api(c.eventId())+"/reconcile","POST","{}",200);assertThat(r.path("finding").asText()).isEqualTo("QUERY_UNAVAILABLE");
        doReturn(new Reply(c.eventId(),"PERSISTED",new Ack(UUID.randomUUID(),c.applicationId(),"remote","PERSISTED"))).when(remote).lookup(any(),anyString());
        assertThat(support.call(api(c.eventId())+"/reconcile","POST","{}",200).path("finding").asText()).isEqualTo("INVALID_RESPONSE");
        assertThat(state(c.eventId())).isEqualTo("REVIEW");verify(remote,never()).deliver(any());
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
