package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.example.cloudcustomerservice.agent.persistence.*;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.submission.*;
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

/** 真实 PostgreSQL + Spring 事务代理 + HTTP 安全链；草稿由第23章服务真实发布和确认。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=submission-test-only-password","handoff.accounts.customer2002.password=submission-test-only-password",
    "handoff.accounts.support9001.password=submission-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class SubmissionPersistenceTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PersistentDraftTaskService tasks;
    @Autowired DraftTaskRepository repository;
    @Autowired DraftApplicationService drafts;
    @Autowired IdempotentSubmissionService service;
    @Autowired SubmissionGraph graph;
    @MockitoBean ChatModel model;
    @MockitoBean ReturnAssessmentService assessment;
    final Actor actor=new Actor("tenant-yunshan",1001);
    final Access access=new Access(actor,id->DraftTaskTest.receipt(id,com.example.cloudcustomerservice.handoff.HandoffModel.Mode.BOT,0));
    static final String ROOT="/internal/draft-tasks/submission/operations";
    Client owner,other,support;
    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
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

    @Test void repeatedPrepareAndDecisionPreserveIdentityAndDeadline()throws Exception{
        UUID id=confirmed();var a=service.prepare(actor,id,1);var b=service.prepare(actor,id,1);assertThat(b).isEqualTo(a);
        assertThat(service.result(actor,a.operationId()).observation()).isEqualTo("NOT_OBSERVED");
        var decision=service.decide(actor,a.operationId(),Decision.APPROVE);assertThat(service.decide(actor,a.operationId(),Decision.APPROVE)).isEqualTo(decision);
        assertThat(decision.decidedBy()).isEqualTo(1001);assertThat(decision.decidedAt()).isNotNull();assertThat(count(id)).isZero();
    }
    @Test void createAndReplayHaveIdenticalReceiptEvenAfterClosedTaskAndExpiry()throws Exception{
        UUID id=confirmed();var op=approved(id);var body=service.detail(actor,op.operationId()).body();clearInvocations(model);
        var first=service.submit(actor,op.operationId());expire(op.operationId());
        assertThat(service.submit(actor,op.operationId())).isEqualTo(first);assertThat(service.result(actor,op.operationId()).receipt()).isEqualTo(first);
        assertThat(service.prepare(actor,id,1).operationId()).isEqualTo(op.operationId());assertThat(count(id)).isEqualTo(1);
        assertThat(first.result()).isEqualTo("APPLICATION_CREATED_PENDING_REVIEW");assertThat(first.refundExecutedByThisOperation()).isFalse();
        assertThat(repository.owned(actor,id).status()).isEqualTo("CLOSED");
        assertThat(json.readTree(jdbc.queryForObject("select body_snapshot::text from ai.cs_after_sale_application where task_id=?",String.class,id))).isEqualTo(body);
        assertThat(jdbc.queryForObject("select status from ai.cs_after_sale_application where task_id=?",String.class,id)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("select mode from ai.cs_conversation where id=?",String.class,repository.owned(actor,id).conversationId())).isEqualTo("BOT");
        verify(model,never()).call(any(Prompt.class));
    }
    @Test void pendingAndRejectedNeverCreateAndDecisionCannotReverse()throws Exception{
        UUID id=confirmed();var op=service.prepare(actor,id,1);denied(()->service.submit(actor,op.operationId()),409);
        var rejected=service.decide(actor,op.operationId(),Decision.REJECT);assertThat(service.decide(actor,op.operationId(),Decision.REJECT)).isEqualTo(rejected);
        denied(()->service.decide(actor,op.operationId(),Decision.APPROVE),409);denied(()->service.submit(actor,op.operationId()),409);assertThat(count(id)).isZero();
    }
    @Test void missingCurrentConfirmationCannotPrepare()throws Exception{
        UUID id=confirmed();drafts.generate(access,id,repository.owned(actor,id).version());
        denied(()->service.prepare(actor,id,2),409);denied(()->service.prepare(actor,id,1),409);assertThat(count(id)).isZero();
    }
    @Test void expiredUnexecutedApprovalCannotCreateOrBeReplaced()throws Exception{
        UUID id=confirmed();var op=approved(id);expire(op.operationId());denied(()->service.submit(actor,op.operationId()),409);
        assertThat(service.prepare(actor,id,1).operationId()).isEqualTo(op.operationId());assertThat(service.detail(actor,op.operationId()).firstExecutionEligible()).isFalse();assertThat(count(id)).isZero();
    }
    @Test void expiryBeforeDecisionBlocksApprovalButAllowsRejection()throws Exception{
        UUID id=confirmed();var op=service.prepare(actor,id,1);expire(op.operationId());denied(()->service.decide(actor,op.operationId(),Decision.APPROVE),409);
        assertThat(service.decide(actor,op.operationId(),Decision.REJECT).status()).isEqualTo("REJECTED");
    }
    @Test void changedDraftBlocksOldFirstExecutionAndTaskCannotCreateTwice()throws Exception{
        UUID id=confirmed();var old=approved(id);drafts.generate(access,id,repository.owned(actor,id).version());drafts.confirm(access,id,2L,true);
        var next=service.prepare(actor,id,2);service.decide(actor,next.operationId(),Decision.APPROVE);
        denied(()->service.submit(actor,old.operationId()),409);var receipt=service.submit(actor,next.operationId());
        denied(()->service.submit(actor,old.operationId()),409);assertThat(count(id)).isEqualTo(1);assertThat(service.result(actor,next.operationId()).receipt()).isEqualTo(receipt);
    }
    @Test void newRunImmediatelyBlocksOldOperation()throws Exception{
        UUID id=confirmed();var op=approved(id);repository.claim(actor,id,repository.owned(actor,id).version(),0,UUID.randomUUID());
        denied(()->service.submit(actor,op.operationId()),409);assertThat(count(id)).isZero();
    }
    @Test void changedReceptionVersionInvalidatesEvenIfBotAgain()throws Exception{
        UUID id=confirmed();var op=approved(id);jdbc.update("update ai.cs_conversation set version=version+2 where id=?",repository.owned(actor,id).conversationId());
        denied(()->service.submit(actor,op.operationId()),409);assertThat(service.detail(actor,op.operationId()).firstExecutionEligible()).isFalse();assertThat(count(id)).isZero();
    }
    @Test void currentOwnershipCheckedBeforeHistoricalReplay()throws Exception{
        UUID id=confirmed();var op=approved(id);service.submit(actor,op.operationId());
        for(Actor intruder:List.of(new Actor("tenant-yunshan",2002),new Actor("other-tenant",1001))){
            denied(()->service.result(intruder,op.operationId()),404);denied(()->service.submit(intruder,op.operationId()),404);
            denied(()->service.detail(intruder,op.operationId()),404);denied(()->service.prepare(intruder,id,1),404);
            assertThat(service.list(intruder)).noneMatch(o->o.operationId().equals(op.operationId()));
        }
    }
    @Test void databaseFailureAfterInsertRollsBackApplicationOperationAndTask()throws Exception{
        UUID id=confirmed();var op=approved(id);long version=repository.owned(actor,id).version();
        // 该触发器只在 INSERT 申请之后的状态更新处失败，确保验证的是中途回滚。
        jdbc.execute("create function ai.ch26_fail_update() returns trigger language plpgsql as $$ begin if NEW.operation_id='"+op.operationId()+"'::uuid and NEW.status='SUCCEEDED' then raise exception 'injected after application insert'; end if; return NEW; end $$");
        jdbc.execute("create trigger ch26_fail_update before update on ai.cs_submit_operation for each row execute function ai.ch26_fail_update()");
        try{assertThatThrownBy(()->service.submit(actor,op.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count(id)).isZero();assertThat(service.detail(actor,op.operationId()).operation().status()).isEqualTo("APPROVED");assertThat(repository.owned(actor,id).version()).isEqualTo(version);
        }finally{jdbc.execute("drop trigger ch26_fail_update on ai.cs_submit_operation");jdbc.execute("drop function ai.ch26_fail_update()");}
        assertThat(service.submit(actor,op.operationId()).applicationId()).isNotNull();assertThat(count(id)).isEqualTo(1);
    }
    @Test void concurrentPrepareUsesOneOperation()throws Exception{UUID id=confirmed();var rows=concurrent(id,()->service.prepare(actor,id,1));assertThat(rows.get(1)).isEqualTo(rows.get(0));}
    @Test void concurrentSubmitUsesOneApplicationAndReturnsSameReceipt()throws Exception{
        UUID id=confirmed();var op=approved(id);var rows=concurrent(id,()->service.submit(actor,op.operationId()));assertThat(rows.get(1)).isEqualTo(rows.get(0));assertThat(count(id)).isEqualTo(1);
    }
    /** 持有任务锁，等待两个独立连接真实进入锁等待后放开；不使用 sleep 猜测竞争。 */
    List<Object> concurrent(UUID id,Callable<?> action)throws Exception{
        var pool=Executors.newFixedThreadPool(2);try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);try(var q=connection.prepareStatement("select task_id from ai.cs_draft_task where task_id=? for update")){q.setObject(1,id);q.executeQuery().close();}
            var a=pool.submit(action);var b=pool.submit(action);
            try{await().atMost(Duration.ofSeconds(2)).until(()->jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Long.class)>=2);}
            finally{connection.commit();}return List.of(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    /** 第30章交叉竞争：实际进入行锁等待后才释放；提交与新轮领取最多一个成功。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void submissionRacingNewRunHasOnlyOneBusinessWinner(boolean submitFirst)throws Exception {
        UUID id=confirmed();var op=approved(id);long version=repository.owned(actor,id).version();
        Callable<String> submit=()->{try{service.submit(actor,op.operationId());return "CREATED";}
            catch(ResponseStatusException conflict){assertThat(conflict.getStatusCode().value()).isEqualTo(409);return "BLOCKED";}};
        Callable<String> claim=()->{try{repository.claim(actor,id,version,0,UUID.randomUUID());return "CLAIMED";}
            catch(ResponseStatusException conflict){assertThat(conflict.getStatusCode().value()).isEqualTo(409);return "BLOCKED";}};
        var pool=Executors.newFixedThreadPool(2);
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("select task_id from ai.cs_draft_task where task_id=? for update")){
                query.setObject(1,id);query.executeQuery().close();
            }
            var first=pool.submit(submitFirst?submit:claim);
            await().atMost(Duration.ofSeconds(2)).until(()->jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Long.class)>=1);
            var second=pool.submit(submitFirst?claim:submit);
            try{await().atMost(Duration.ofSeconds(2)).until(()->jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Long.class)>=2);}
            finally{connection.commit();}
            var outcomes=List.of(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(submitFirst?"CREATED":"CLAIMED","BLOCKED");
            assertThat(count(id)).isEqualTo(submitFirst?1:0);
            assertThat(repository.owned(actor,id).status()).isEqualTo(submitFirst?"CLOSED":"RUNNING");
            // 新轮领取先赢时旧批准仍保留为历史，但不能被首次执行消费。
            if(!submitFirst)assertThat(service.detail(actor,op.operationId()).firstExecutionEligible()).isFalse();
        }finally{pool.shutdownNow();}
    }
    @Test void uniqueConstraintsAndImmutableTargetRejectBypass()throws Exception{
        UUID id=confirmed();var op=approved(id);var receipt=service.submit(actor,op.operationId());
        assertThatThrownBy(()->jdbc.update("update ai.cs_submit_operation set draft_version=2 where operation_id=?",op.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update ai.cs_submit_operation set status='APPROVED' where operation_id=?",op.operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("insert into ai.cs_submit_operation(operation_id,task_id,draft_version,reception_version,expires_at) values(?,?,1,0,clock_timestamp())",UUID.randomUUID(),id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("insert into ai.cs_after_sale_application select ?,operation_id,task_id,draft_version,tenant_id,user_id,order_no,body_snapshot,status,created_at from ai.cs_after_sale_application where application_id=?",UUID.randomUUID(),receipt.applicationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update ai.cs_after_sale_application set body_snapshot='{}' where application_id=?",receipt.applicationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count(id)).isEqualTo(1);
    }
    @Test void graphLooksUpReceiptBeforeClosedTaskChecks()throws Exception{
        UUID id=confirmed();var op=approved(id);var first=graph.execute(actor,op.operationId());expire(op.operationId());
        assertThat(first.trace()).containsExactly("lookup_receipt","submit_once","receipt");
        var next=graph.execute(actor,op.operationId());assertThat(next.trace()).containsExactly("lookup_receipt","receipt");assertThat(next.receipt()).isEqualTo(first.receipt());assertThat(next.replayed()).isTrue();assertThat(count(id)).isEqualTo(1);
    }
    @Test void graphRejectsUnapprovedWithoutInventingReceipt()throws Exception{
        UUID id=confirmed();var op=service.prepare(actor,id,1);var run=graph.execute(actor,op.operationId());assertThat(run.status()).isEqualTo("BLOCKED");assertThat(run.receipt()).isNull();assertThat(count(id)).isZero();
    }
    @Test void lockTimeoutGoesToReconciliationAndDoesNotAutomaticallyRetry()throws Exception{
        UUID id=confirmed();var op=approved(id);try(var c=dataSource.getConnection()){
            c.setAutoCommit(false);try(var q=c.prepareStatement("select task_id from ai.cs_draft_task where task_id=? for update")){q.setObject(1,id);q.executeQuery().close();}
            var run=graph.execute(actor,op.operationId());assertThat(run.status()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(run.trace()).containsExactly("lookup_receipt","submit_once","reconcile");assertThat(count(id)).isZero();c.rollback();
        }
        assertThat(service.result(actor,op.operationId()).observation()).isEqualTo("NOT_OBSERVED");
    }
    @Test void uncommittedApplicationIsNotObservedAndQueryDoesNotDeclareFailure()throws Exception{
        UUID id=confirmed();var op=approved(id);var pool=Executors.newSingleThreadExecutor();
        jdbc.execute("create function ai.ch26_pause_insert() returns trigger language plpgsql as $$ begin if NEW.operation_id='"+op.operationId()+"'::uuid then perform pg_advisory_xact_lock(262626); end if; return NEW; end $$");
        jdbc.execute("create trigger ch26_pause_insert after insert on ai.cs_after_sale_application for each row execute function ai.ch26_pause_insert()");
        try(var c=dataSource.getConnection()){
            c.setAutoCommit(false);c.createStatement().execute("select pg_advisory_xact_lock(262626)");var pending=pool.submit(()->service.submit(actor,op.operationId()));
            try{await().atMost(Duration.ofSeconds(2)).until(()->jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event='advisory'",Long.class)>0);
                assertThat(service.result(actor,op.operationId()).observation()).isEqualTo("NOT_OBSERVED");assertThat(count(id)).isZero();
            }finally{c.commit();}
            var saved=pending.get(8,TimeUnit.SECONDS);assertThat(service.result(actor,op.operationId()).receipt()).isEqualTo(saved);assertThat(count(id)).isEqualTo(1);
        }finally{pool.shutdownNow();jdbc.execute("drop trigger ch26_pause_insert on ai.cs_after_sale_application");jdbc.execute("drop function ai.ch26_pause_insert()");}
    }
    @Test void httpSecurityStrictInputAndReadOnlyQuery()throws Exception{
        UUID id=confirmed();var op=service.prepare(actor,id,1);String p=op(op.operationId());
        assertThat(HttpClient.newHttpClient().send(request(ROOT).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        support.call(ROOT,"GET",null,403);other.call(p,"GET",null,404);other.call(p+"/execute","POST","{}",404);other.call(p+"/result","GET",null,404);
        assertThat(owner.http.send(request(p+"/execute").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        for(String body:List.of("{}","{\"decision\":\"APPROVE\"}","{\"decision\":\"APPROVE\",\"accepted\":false}","{\"decision\":\"APPROVE\",\"accepted\":true,\"approvedBy\":9001}"))owner.call(p+"/decision","POST",body,400);
        owner.call(p+"/execute","POST","{\"status\":\"APPROVED\"}",400);owner.call(ROOT,"POST","{\"taskId\":\""+id+"\",\"draftVersion\":1,\"body\":{}}",400);
        owner.call(p+"/result","GET",null,200);assertThat(count(id)).isZero();
        owner.call(p+"/decision","POST","{\"decision\":\"APPROVE\",\"accepted\":true}",200);
        var first=owner.call(p+"/execute","POST","{}",200);var next=owner.call(p+"/execute","POST","{}",200);
        assertThat(next.path("receipt")).isEqualTo(first.path("receipt"));assertThat(owner.call(p+"/result","GET",null,200).path("receipt")).isEqualTo(first.path("receipt"));assertThat(count(id)).isEqualTo(1);
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
