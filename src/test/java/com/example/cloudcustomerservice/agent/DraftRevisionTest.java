package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.example.cloudcustomerservice.agent.persistence.*;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.draft.DraftModels.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL、行锁、HTTP/CSRF、结构化转换与 Agent；只有模型和订单适配器受控。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=draft-test-only-password","handoff.accounts.customer2002.password=draft-test-only-password",
    "handoff.accounts.support9001.password=draft-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class DraftRevisionTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PersistentDraftTaskService tasks;
    @Autowired DraftTaskRepository repository;
    @Autowired DraftApplicationService drafts;
    @MockitoSpyBean DraftVersionStore store;
    @MockitoBean ChatModel model;
    @MockitoBean ReturnAssessmentService assessment;
    final Actor actor=new Actor("tenant-yunshan",1001);
    final Access access=new Access(actor,id->DraftTaskTest.receipt(id,com.example.cloudcustomerservice.handoff.HandoffModel.Mode.BOT,0));
    final AtomicInteger extracted=new AtomicInteger();
    Client owner,other,support;
    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
        owner=new Client("customer1001");other=new Client("customer2002");support=new Client("support9001");
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("controlled-draft").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();Prompt p=i.getArgument(0);
            if(isExtract(p)){extracted.incrementAndGet();return DraftAgentTest.text("{\"userDescription\":\"右侧按钮按不动，质量尚未核验\",\"requestedHandling\":\"希望进入售后流程\"}");}return DraftTaskTest.next(p);});
        when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
    }
    static boolean isExtract(Prompt prompt){return prompt.getInstructions().toString().contains("你是售后草稿的文字整理器");}
    String path(UUID id){return "/internal/draft-tasks/tasks/"+id;}
    UUID ready()throws Exception{String c=owner.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();
        return UUID.fromString(owner.call("/internal/draft-tasks/tasks","POST",json.writeValueAsString(Map.of("conversationId",c,"orderNo","A10001","reason","QUALITY_ISSUE")),201).path("taskId").asText());}
    UUID completed()throws Exception{UUID id=ready();assertThat(tasks.continueTask(access,id,0L,"外壳开裂，整理候选").task().version()).isEqualTo(2);return id;}
    View publish(UUID id){return drafts.generate(access,id,repository.owned(actor,id).version());}
    long count(String table,UUID id){return jdbc.queryForObject("select count(*) from ai."+table+" where task_id=?",Long.class,id);}
    Object result(Supplier<?> work){try{return work.get();}catch(ResponseStatusException e){return e.getStatusCode().value();}}
    final class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
        Client(String user)throws Exception{csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
            var r=http.send(request("/internal/handoff/login").header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString("username="+user+"&password=draft-test-only-password")).build(),HttpResponse.BodyHandlers.discarding());assertThat(r.statusCode()).isEqualTo(204);
            csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();}
        JsonNode call(String path,String method,String data,int status)throws Exception{var b=request(path).header("Content-Type","application/json");if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
            var r=http.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(status);return r.body().isEmpty()?json.nullNode():json.readTree(r.body());}
    }
    HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));}

    @Test void immutableBodyAndExactServerFactsAreSavedWithoutFormalBusinessWrites()throws Exception{
        UUID id=completed();var before=tasks.get(access,id);var view=publish(id);
        assertThat(view.draftVersion()).isEqualTo(1);assertThat(view.taskVersion()).isEqualTo(3);assertThat(view.body().checkedSnapshot()).isEqualTo(before.lastRun().assessment());
        assertThat(view.body().orderNo()).isEqualTo("A10001");assertThat(view.body().notice()).contains("不代表");assertThat(view.confirmation()).isNull();
        assertThat(view.current()).isTrue();assertThat(view.confirmationEffective()).isFalse();assertThat(extracted).hasValue(1);
        var saved=jdbc.queryForObject("select body_json::text from ai.cs_draft_revision where task_id=?",String.class,id);
        assertThatThrownBy(()->jdbc.update("update ai.cs_draft_revision set body_json='{}'::jsonb where task_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select body_json::text from ai.cs_draft_revision where task_id=?",String.class,id)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=?",Long.class,before.task().conversationId())).isZero();
    }
    @Test void currentConfirmationIsIdempotentAndCannotBeRewritten()throws Exception{
        UUID id=completed();publish(id);var one=drafts.confirm(access,id,1L,true);var two=drafts.confirm(access,id,1L,true);
        assertThat(two).isEqualTo(one);assertThat(one.confirmedBy()).isEqualTo(1001);assertThat(one.scope()).isEqualTo("DRAFT_CONTENT_ONLY");assertThat(one.confirmedAt()).isNotNull();
        assertThat(count("cs_draft_confirmation",id)).isEqualTo(1);assertThat(repository.owned(actor,id).version()).isEqualTo(4);
        assertThat(drafts.read(access,id,1L).confirmationEffective()).isTrue();assertThat(extracted).hasValue(1);
        assertThatThrownBy(()->jdbc.update("update ai.cs_draft_confirmation set confirmed_by=2002 where task_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void newRevisionNeverInheritsHistoricalConfirmationEvenForIdenticalText()throws Exception{
        UUID id=completed();var v1=publish(id);var confirmation=drafts.confirm(access,id,1L,true);var v2=publish(id);
        assertThat(v2.draftVersion()).isEqualTo(2);assertThat(v2.body()).isEqualTo(v1.body());assertThat(v2.confirmation()).isNull();
        var old=drafts.read(access,id,1L);assertThat(old.body()).isEqualTo(v1.body());assertThat(old.confirmation()).isEqualTo(confirmation);assertThat(old.current()).isFalse();assertThat(old.confirmationEffective()).isFalse();
        DraftTaskTest.status(()->drafts.confirm(access,id,1L,true),409);assertThat(count("cs_draft_confirmation",id)).isEqualTo(1);
    }
    @Test void startingNewRunImmediatelyInvalidatesOldConfirmationAndCompletedNewRunDoesNotRestoreIt()throws Exception{
        UUID id=completed();publish(id);drafts.confirm(access,id,1L,true);var running=repository.claim(actor,id,4,0,UUID.randomUUID());
        var old=drafts.read(access,id,1L);assertThat(old.current()).isFalse();assertThat(old.confirmationEffective()).isFalse();assertThat(old.confirmation()).isNotNull();DraftTaskTest.status(()->drafts.confirm(access,id,1L,true),409);
        repository.finish(running,"CANDIDATE_UNVALIDATED",running.lastCheckpointId(),running.lastResultJson());
        assertThat(drafts.read(access,id,1L).current()).isFalse(); // 即使任务完成，依据 runId 不同也不能恢复旧确认。
    }
    @Test void newAgentCandidateCreatesV2WhileV1BodyStaysUnchanged()throws Exception{
        UUID id=completed();var v1=publish(id);var second=tasks.continueTask(access,id,3L,"更正为按钮按不动");assertThat(second.task().version()).isEqualTo(5);
        var v2=publish(id);assertThat(v2.basisRunId()).isNotEqualTo(v1.basisRunId());assertThat(v2.draftVersion()).isEqualTo(2);assertThat(drafts.read(access,id,1L).body()).isEqualTo(v1.body());
        assertThat(drafts.list(access,id)).hasSize(2);assertThat(tasks.get(access,id).task().draftVersion()).isEqualTo(2);
    }
    @Test void crossAccountAndTenantCannotReadPublishOrConfirm()throws Exception{
        UUID id=completed();publish(id);int before=extracted.get();
        for(String endpoint:List.of("/drafts","/drafts/1"))other.call(path(id)+endpoint,"GET",null,404);
        other.call(path(id)+"/drafts","POST","{\"expectedTaskVersion\":3}",404);other.call(path(id)+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",404);
        var tenant=new Access(new Actor("other-tenant",1001),c->{throw new AssertionError("不能先查对方会话");});DraftTaskTest.status(()->drafts.read(tenant,id,1L),404);DraftTaskTest.status(()->drafts.confirm(tenant,id,1L,true),404);
        assertThat(extracted).hasValue(before);assertThat(count("cs_draft_confirmation",id)).isZero();
    }
    @Test void authenticationRoleCsrfAndExplicitAcceptanceAreRequired()throws Exception{
        UUID id=completed();publish(id);String confirm=path(id)+"/draft-confirmations";
        assertThat(HttpClient.newHttpClient().send(request(path(id)+"/drafts/1").GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        support.call(path(id)+"/drafts/1","GET",null,403);support.call(confirm,"POST","{\"draftVersion\":1,\"accepted\":true}",403);
        assertThat(owner.http.send(request(confirm).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"draftVersion\":1,\"accepted\":true}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        for(String input:List.of("{}","{\"draftVersion\":0,\"accepted\":true}","{\"draftVersion\":1}","{\"draftVersion\":1,\"accepted\":false}"))owner.call(confirm,"POST",input,400);
        owner.call(path(id)+"/drafts","POST","{}",400);owner.call(path(id)+"/drafts","POST","{\"expectedTaskVersion\":-1}",400);
        assertThat(count("cs_draft_confirmation",id)).isZero();
    }
    @Test void staleExpectedTaskVersionStopsBeforeModel()throws Exception{UUID id=completed();publish(id);DraftTaskTest.status(()->drafts.generate(access,id,2L),409);assertThat(extracted).hasValue(1);assertThat(count("cs_draft_revision",id)).isEqualTo(1);}
    @Test void invalidStructuredOutputDoesNotSavePartialRevisionOrChangeTask()throws Exception{UUID id=completed();when(model.call(any(Prompt.class))).thenReturn(DraftAgentTest.text("{\"userDescription\":\"\"}"));
        DraftTaskTest.status(()->publish(id),503);assertThat(count("cs_draft_revision",id)).isZero();assertThat(repository.owned(actor,id).version()).isEqualTo(2);}
    @Test void inconsistentSavedFactsStopBeforeExtractor()throws Exception{UUID id=completed();jdbc.update("update ai.cs_draft_task set last_result_json=jsonb_set(last_result_json,'{run,assessment,verifiedFacts,orderNo}','\"A20001\"') where task_id=?",id);
        DraftTaskTest.status(()->publish(id),503);assertThat(extracted).hasValue(0);assertThat(count("cs_draft_revision",id)).isZero();}
    @Test void failedTransactionRollsBackBothRevisionAndVersion()throws Exception{UUID id=completed();doAnswer(i->{i.callRealMethod();throw new IllegalStateException("rollback test");}).when(store).publish(any(),any(),anyLong(),any());
        assertThatThrownBy(()->publish(id)).isInstanceOf(IllegalStateException.class);assertThat(count("cs_draft_revision",id)).isZero();assertThat(repository.owned(actor,id).version()).isEqualTo(2);}
    @Test void confirmDuringExtractionRejectsLatePublication()throws Exception{raceDuringExtraction(false,false);}
    @Test void newRoundDuringExtractionRejectsLatePublication()throws Exception{raceDuringExtraction(true,false);}
    @Test void handoffDuringExtractionRejectsLatePublication()throws Exception{raceDuringExtraction(false,true);}
    /** 真实模型调用在事务外等待闩锁；另一个请求可以提交确认、任务领取或人工接待。 */
    void raceDuringExtraction(boolean startRun,boolean handoff)throws Exception{
        UUID id=completed();publish(id);var begun=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(model.call(any(Prompt.class))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();begun.countDown();assertThat(release.await(6,TimeUnit.SECONDS)).isTrue();return DraftAgentTest.text("{\"userDescription\":\"迟到描述\",\"requestedHandling\":\"售后\"}");});
        try{var pending=pool.submit(()->result(()->drafts.generate(access,id,3L)));assertThat(begun.await(3,TimeUnit.SECONDS)).isTrue();
            if(startRun)repository.claim(actor,id,3,0,UUID.randomUUID());else if(handoff)owner.call("/api/handoff/conversations/"+repository.owned(actor,id).conversationId()+"/handoff","POST",null,200);else drafts.confirm(access,id,1L,true);
            release.countDown();assertThat(pending.get(8,TimeUnit.SECONDS)).isEqualTo(409);assertThat(count("cs_draft_revision",id)).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void logoutDuringHttpExtractionCannotPublishLateDraft()throws Exception{
        UUID id=completed();var begun=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(model.call(any(Prompt.class))).thenAnswer(i->{begun.countDown();assertThat(release.await(6,TimeUnit.SECONDS)).isTrue();return DraftAgentTest.text("{\"userDescription\":\"迟到描述\",\"requestedHandling\":\"售后\"}");});
        try{var pending=pool.submit(()->owner.call(path(id)+"/drafts","POST","{\"expectedTaskVersion\":2}",401));assertThat(begun.await(3,TimeUnit.SECONDS)).isTrue();
            owner.call("/internal/handoff/logout","POST",null,204);release.countDown();pending.get(8,TimeUnit.SECONDS);assertThat(count("cs_draft_revision",id)).isZero();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void httpConfirmationRecordsViewedVersionAndRejectsStaleWindow()throws Exception{
        UUID id=completed();String root=path(id);
        var v1=owner.call(root+"/drafts","POST","{\"expectedTaskVersion\":2}",201);assertThat(v1.path("draftVersion").asInt()).isEqualTo(1);
        owner.call(root+"/drafts","POST","{\"expectedTaskVersion\":3}",201);
        owner.call(root+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",409);
        assertThat(count("cs_draft_confirmation",id)).isZero();
        var receipt=owner.call(root+"/draft-confirmations","POST","{\"draftVersion\":2,\"accepted\":true}",200);
        assertThat(owner.call(root+"/draft-confirmations","POST","{\"draftVersion\":2,\"accepted\":true}",200)).isEqualTo(receipt);
        assertThat(owner.call(root+"/drafts/2","GET",null,200).path("confirmationEffective").asBoolean()).isTrue();
        assertThat(owner.call(root+"/drafts/1","GET",null,200).path("body")).isEqualTo(v1.path("body"));
        assertThat(extracted).hasValue(2);
    }
    @Test void sameVersionConcurrentConfirmationHasOneReceiptWithRealRowLocks()throws Exception{
        UUID id=completed();publish(id);var results=whileLocked(id,()->drafts.confirm(access,id,1L,true),()->drafts.confirm(access,id,1L,true));
        assertThat(results.get(0)).isInstanceOf(Confirmation.class);assertThat(results.get(1)).isEqualTo(results.get(0));assertThat(count("cs_draft_confirmation",id)).isEqualTo(1);
    }
    @Test void publishAndConfirmCannotTransferAcceptanceToDifferentContent()throws Exception{
        UUID id=completed();var v1=publish(id);var basis=store.source(actor,id);
        var results=whileLocked(id,()->store.publish(actor,basis,0,v1.body()),()->drafts.confirm(access,id,1L,true));
        var all=drafts.list(access,id);if(all.size()==2){assertThat(results.get(0)).isInstanceOf(View.class);assertThat(results.get(1)).isEqualTo(409);assertThat(drafts.read(access,id,2L).confirmation()).isNull();}
        else{assertThat(results.get(0)).isEqualTo(409);assertThat(results.get(1)).isInstanceOf(Confirmation.class);}
        assertThat(drafts.read(access,id,1L).body()).isEqualTo(v1.body());
    }
    /** 先持有父任务锁，等到两个独立连接真的进入 PostgreSQL 锁等待后释放，不用固定睡眠伪造并发。 */
    List<Object> whileLocked(UUID id,Supplier<?> a,Supplier<?> b)throws Exception{
        var pool=Executors.newFixedThreadPool(2);
        try(var c=dataSource.getConnection()){c.setAutoCommit(false);try(var q=c.prepareStatement("select task_id from ai.cs_draft_task where task_id=? for update")){q.setObject(1,id);q.executeQuery().close();}
            var one=pool.submit(()->result(a));var two=pool.submit(()->result(b));
            try{await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0",Integer.class)).isGreaterThanOrEqualTo(2));}finally{c.commit();}
            return List.of(one.get(6,TimeUnit.SECONDS),two.get(6,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    @Test void endedOrNonBotTaskCannotBeConfirmed()throws Exception{
        UUID id=completed();publish(id);tasks.close(access,id,3L);assertThat(drafts.read(access,id,1L).current()).isFalse();DraftTaskTest.status(()->drafts.confirm(access,id,1L,true),409);
        UUID second=completed();publish(second);owner.call("/api/handoff/conversations/"+repository.owned(actor,second).conversationId()+"/handoff","POST",null,200);
        owner.call(path(second)+"/drafts/1","GET",null,409);owner.call(path(second)+"/draft-confirmations","POST","{\"draftVersion\":1,\"accepted\":true}",409);
    }
}
