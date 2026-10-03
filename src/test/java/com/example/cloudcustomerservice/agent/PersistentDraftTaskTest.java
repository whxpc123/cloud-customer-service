package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.fasterxml.jackson.databind.*;
import com.example.cloudcustomerservice.agent.persistence.*;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import org.springframework.ai.chat.messages.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Cookie、CSRF、Tomcat、数据库事务和 Agent；订单与模型受控，隔离容器不触碰用户知识库。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=draft-test-only-password",
    "handoff.accounts.customer2002.password=draft-test-only-password",
    "handoff.accounts.support9001.password=draft-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class PersistentDraftTaskTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean ChatModel model;
    @MockitoBean ReturnAssessmentService assessment;
    @Autowired PersistentDraftTaskService service;
    @MockitoSpyBean DraftTaskRepository repository;
    @MockitoSpyBean PostgresSaverFactory savers;
    final Actor actor=new Actor("tenant-yunshan",1001);
    final Access access=new Access(actor,id->DraftTaskTest.receipt(id,com.example.cloudcustomerservice.handoff.HandoffModel.Mode.BOT,0));
    Client owner,other,support;
    UUID newTask()throws Exception{return UUID.fromString(task(create()));}
    String turn(long version,String message)throws Exception{return json.writeValueAsString(Map.of("expectedVersion",version,"message",message));}
    PersistentDraftTaskService fresh(){return new PersistentDraftTaskService(repository,savers,assessment,model,json,false);}
    String url(UUID id){return path()+"/"+id;}
    void completed(UUID id){assertThat(service.continueTask(access,id,0L,"原始描述独特标记").task().status()).isEqualTo("CANDIDATE_UNVALIDATED");}

    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
        owner=new Client("customer1001");other=new Client("customer2002");support=new Client("support9001");
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("controlled-http").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return DraftTaskTest.next(i.getArgument(0));});
        when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
    }
    String create()throws Exception{return owner.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();}
    String body(String id)throws Exception{return json.writeValueAsString(Map.of("conversationId",id,"orderNo","A10001","reason","QUALITY_ISSUE"));}
    String path(){return "/internal/draft-tasks/tasks";}
    String task(String conversation)throws Exception{return owner.call(path(),"POST",body(conversation),201).path("taskId").asText();}
    String turn(){return "{\"message\":\"请整理候选，不要提交\"}";}
    HttpRequest.Builder base(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));}
    final class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Client(String name)throws Exception{
            csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
            var r=http.send(base("/internal/handoff/login").header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
                .POST(HttpRequest.BodyPublishers.ofString("username="+name+"&password=draft-test-only-password")).build(),HttpResponse.BodyHandlers.discarding());
            assertThat(r.statusCode()).isEqualTo(204);csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
        }
        JsonNode call(String path,String method,String data,int expected)throws Exception{
            var b=base(path).header("Content-Type","application/json");if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
            var r=http.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);return r.body().isEmpty()?json.nullNode():json.readTree(r.body());
        }
    }
    @Test void newSaverAndNewServiceRecoverTypedMessagesAndNormalTurns()throws Exception{
        UUID id=newTask();var ready=repository.owned(actor,id);var second=fresh();
        try{
            assertThat(second.get(access,id).task().status()).isEqualTo("READY");verify(model,never()).call(any(Prompt.class));
            completed(id);var first=repository.owned(actor,id);assertThat(first.version()).isEqualTo(2);assertThat(first.threadId()).isEqualTo(ready.threadId());
            var config=RunnableConfig.builder().threadId(first.threadId()).build();var loaded=savers.create().get(config).orElseThrow();
            assertThat(loaded.getId()).isEqualTo(first.lastCheckpointId().toString());assertThat(loaded.getNextNodeId()).isEqualTo(StateGraph.END);
            var messages=(List<?>)loaded.getState().get("messages");assertThat(messages).hasSize(6);
            assertThat(messages.get(0)).isInstanceOf(UserMessage.class);assertThat(messages.stream().filter(ToolResponseMessage.class::isInstance)).hasSize(2);
            assertThat(messages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast).anyMatch(m->m.hasToolCalls())).isTrue();
            var checkpointRows=jdbc.queryForObject("select count(*) from public.graphcheckpoint",Long.class);var read=second.get(access,id);
            assertThat(read.lastRun().candidateText()).isEqualTo(DraftAgentTest.CANDIDATE);verify(model,times(3)).call(any(Prompt.class));
            assertThat(jdbc.queryForObject("select count(*) from public.graphcheckpoint",Long.class)).isEqualTo(checkpointRows);
            var prompts=new CopyOnWriteArrayList<Prompt>();when(model.call(any(Prompt.class))).thenAnswer(i->{Prompt p=i.getArgument(0);prompts.add(p);return DraftTaskTest.next(p);});
            var result=second.continueTask(access,id,2L,"第二轮独特修正");assertThat(result.task().version()).isEqualTo(4);assertThat(result.task().turnNo()).isEqualTo(2);
            assertThat(result.lastRun().runId()).isNotEqualTo(read.lastRun().runId());assertThat(result.state().userMessages()).isEqualTo(2);
            assertThat(prompts.get(0).getInstructions().toString()).contains("原始描述独特标记",DraftAgentTest.CANDIDATE,"第二轮独特修正");
            for(var p:prompts)assertThat(p.getInstructions().stream().filter(UserMessage.class::isInstance).filter(m->m.getText().contains("第二轮独特修正"))).hasSize(1);
            verify(assessment,times(2)).assess(actor,"A10001",ReturnReason.QUALITY_ISSUE);
            assertThat(repository.owned(actor,id).threadId()).isEqualTo(ready.threadId());
        }finally{second.shutdown();}
    }
    @Test void eachTurnReloadsCurrentFactsAndReplacesCandidate()throws Exception{
        UUID id=newTask();completed(id);when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NOT_ACCESSIBLE,false));
        var r=service.continueTask(access,id,2L,"继续修改");assertThat(r.task().status()).isEqualTo("NEEDS_ATTENTION");assertThat(r.lastRun().candidateText()).isNull();assertThat(r.lastRun().assessment().verifiedFacts()).isNull();
        assertThat(service.get(access,id).lastCompletedTurn()).isEqualTo(2);verify(assessment,times(2)).assess(any(),anyString(),any());
    }
    @Test void twoIndependentServicesCompeteForSameVersionOnlyOneExecutes()throws Exception{
        UUID id=newTask();var otherService=fresh();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(model.call(any(Prompt.class))).thenAnswer(i->{started.countDown();DraftTaskTest.await(release);return DraftTaskTest.next(i.getArgument(0));});
        try{var first=pool.submit(()->service.continueTask(access,id,0L,"本轮"));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            DraftTaskTest.status(()->otherService.continueTask(access,id,0L,"并发"),409);assertThat(otherService.get(access,id).task().status()).isEqualTo("RUNNING");
            release.countDown();assertThat(first.get(10,TimeUnit.SECONDS).task().version()).isEqualTo(2);verify(model,times(3)).call(any(Prompt.class));
        }finally{release.countDown();pool.shutdownNow();otherService.shutdown();}
    }
    @Test void oldVersionCannotReplayCompletedRunAndHttpGetHasNoSideEffects()throws Exception{
        UUID id=newTask();var a=owner.call(url(id)+"/turns","POST",turn(0,"整理"),200);assertThat(a.path("task").path("version").asInt()).isEqualTo(2);
        owner.call(url(id)+"/turns","POST",turn(0,"重发同一请求"),409);
        var b=owner.call(url(id),"GET",null,200);assertThat(b.path("lastRun")).isEqualTo(a.path("lastRun"));assertThat(b.toString()).doesNotContain("threadId","checkpointId","csrfToken");
        verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void loginCsrfRoleAndForeignOwnershipStopBeforeSaverLoads()throws Exception{
        UUID id=newTask();clearInvocations(savers);
        var anonymous=HttpClient.newHttpClient().send(base(url(id)).GET().build(),HttpResponse.BodyHandlers.discarding());assertThat(anonymous.statusCode()).isEqualTo(401);
        var noCsrf=owner.http.send(base(url(id)+"/turns").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(turn(0,"x"))).build(),HttpResponse.BodyHandlers.discarding());assertThat(noCsrf.statusCode()).isEqualTo(403);
        support.call(url(id),"GET",null,403);other.call(url(id),"GET",null,404);other.call(url(id)+"/turns","POST",turn(0,"x"),404);other.call(url(id)+"/close","POST","{\"expectedVersion\":0}",404);
        assertThat(other.call(path(),"GET",null,200).toString()).doesNotContain(id.toString());
        var tenant=new Access(new Actor("other-tenant",1001),c->{throw new AssertionError("不应查会话");});DraftTaskTest.status(()->service.get(tenant,id),404);
        verify(savers,never()).create();verify(model,never()).call(any(Prompt.class));
    }
    @Test void missingExpectedVersionAndInvalidInputAreRejected()throws Exception{
        UUID id=newTask();owner.call(url(id)+"/turns","POST",turn(),400);owner.call(url(id)+"/turns","POST",turn(-1,"x"),400);owner.call(url(id)+"/turns","POST",turn(0," "),400);
        owner.call(path(),"POST",body(create()).replace("A10001","bad"),400);verify(model,never()).call(any(Prompt.class));
    }
    @Test void incompatibleAgentProfileCannotLoadCheckpoints()throws Exception{
        UUID id=newTask();jdbc.update("update ai.cs_draft_task set agent_profile='future-v2' where task_id=?",id);clearInvocations(savers);
        assertThat(service.get(access,id).task().compatible()).isFalse();DraftTaskTest.status(()->service.continueTask(access,id,0L,"继续"),409);verify(savers,never()).create();verify(model,never()).call(any(Prompt.class));
    }
    @Test void deletedCheckpointStopsInsteadOfStartingWithEmptyHistory()throws Exception{
        UUID id=newTask();completed(id);var task=repository.owned(actor,id);
        jdbc.update("delete from public.graphcheckpoint where thread_id=(select thread_id from public.graphthread where thread_name=?)",task.threadId());
        DraftTaskTest.status(()->service.continueTask(access,id,2L,"再修改"),503);
        var view=service.get(access,id);assertThat(view.task().status()).isEqualTo("RECOVERY_REQUIRED");assertThat(view.lastCompletedTurn()).isEqualTo(1);assertThat(view.lastRun().candidateText()).isEqualTo(DraftAgentTest.CANDIDATE);
        verify(model,times(3)).call(any(Prompt.class));DraftTaskTest.status(()->service.continueTask(access,id,view.task().version(),"重试"),409);
    }
    @Test void malformedSerializedCheckpointStopsWithoutFallbackSaver()throws Exception{
        UUID id=newTask();completed(id);var task=repository.owned(actor,id);jdbc.update("update public.graphcheckpoint set state_content_type='incompatible' where checkpoint_id=?",task.lastCheckpointId());
        DraftTaskTest.status(()->service.continueTask(access,id,2L,"修改"),503);assertThat(service.get(access,id).task().status()).isEqualTo("RECOVERY_REQUIRED");verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void extraCheckpointNotRecordedByBusinessTaskIsDetected()throws Exception{
        UUID id=newTask();completed(id);var task=repository.owned(actor,id);var config=RunnableConfig.builder().threadId(task.threadId()).build();
        var saver=savers.create();var old=saver.get(config).orElseThrow();saver.put(config,Checkpoint.builder().state(old.getState()).nodeId("unexpected").nextNodeId(StateGraph.END).build());
        DraftTaskTest.status(()->service.continueTask(access,id,2L,"修改"),503);assertThat(service.get(access,id).task().status()).isEqualTo("RECOVERY_REQUIRED");verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void modelFailureMarksRecoveryAndNeverAutomaticallyRetries()throws Exception{
        UUID id=newTask();when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private-test-secret"));
        owner.call(url(id)+"/turns","POST",turn(0,"整理"),503);var read=owner.call(url(id),"GET",null,200);assertThat(read.path("task").path("status").asText()).isEqualTo("RECOVERY_REQUIRED");assertThat(read.path("lastRun").isNull()).isTrue();
        assertThat(read.toString()).doesNotContain("private-test-secret");owner.call(url(id)+"/turns","POST",turn(read.path("task").path("version").asLong(),"重试"),409);verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void checkpointSuccessButFinishFailureRequiresRecovery()throws Exception{
        UUID id=newTask();doThrow(new IllegalStateException("finish unavailable")).when(repository).finish(any(),anyString(),any(),anyString());
        DraftTaskTest.status(()->service.continueTask(access,id,0L,"整理"),503);var row=repository.owned(actor,id);assertThat(row.status()).isEqualTo("RECOVERY_REQUIRED");assertThat(row.lastResultJson()).isNull();
        assertThat(savers.create().get(RunnableConfig.builder().threadId(row.threadId()).build())).isPresent();
    }
    @Test void lostCompletionAcknowledgementIsRecoveredByReadWithoutRerun()throws Exception{
        UUID id=newTask();doAnswer(i->{
            // 在独立事务里真正提交后才模拟确认丢失；不能在同一事务内抛异常，否则测试的只是回滚。
            var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.execute(status->{try{i.callRealMethod();return null;}catch(Throwable e){throw new IllegalStateException(e);}});
            throw new IllegalStateException("response lost after commit");}).when(repository).finish(any(),anyString(),any(),anyString());
        DraftTaskTest.status(()->service.continueTask(access,id,0L,"整理"),503);
        var view=service.get(access,id);assertThat(view.task().status()).isEqualTo("CANDIDATE_UNVALIDATED");assertThat(view.task().version()).isEqualTo(2);assertThat(view.lastRun().candidateText()).isEqualTo(DraftAgentTest.CANDIDATE);
        DraftTaskTest.status(()->service.continueTask(access,id,0L,"重发"),409);verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void recoveryMarkFailureLeavesRunningWhichFreshServiceCannotReset()throws Exception{
        UUID id=newTask();when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("model down"));doThrow(new IllegalStateException("db down")).when(repository).requireRecovery(any());
        DraftTaskTest.status(()->service.continueTask(access,id,0L,"整理"),503);var otherService=fresh();
        try{assertThat(otherService.get(access,id).task().status()).isEqualTo("RUNNING");DraftTaskTest.status(()->otherService.continueTask(access,id,1L,"重跑"),409);verify(model,times(1)).call(any(Prompt.class));}finally{otherService.shutdown();}
    }
    @Test void closeRetainsUnreleasedCheckpointAndCannotContinue()throws Exception{
        UUID id=newTask();completed(id);var thread=repository.owned(actor,id).threadId();var closed=owner.call(url(id)+"/close","POST","{\"expectedVersion\":2}",200);
        assertThat(closed.path("task").path("status").asText()).isEqualTo("CLOSED");assertThat(closed.path("lastRun").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select is_released from public.graphthread where thread_name=?",Boolean.class,thread)).isFalse();assertThat(repository.owned(actor,id).lastResultJson()).isNotBlank();
        owner.call(url(id)+"/turns","POST",turn(3,"继续"),409);verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void waitingHumanHidesStoredResultAndDoesNotRun()throws Exception{
        String conversation=create();UUID id=UUID.fromString(task(conversation));completed(id);
        owner.call("/api/handoff/conversations/"+conversation+"/handoff","POST",null,200);
        var view=owner.call(url(id),"GET",null,200);assertThat(view.path("lastRun").isNull()).isTrue();assertThat(view.path("task").path("status").asText()).isEqualTo("CLOSED");
        owner.call(url(id)+"/turns","POST",turn(2,"继续"),409);verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void timeoutMarksRecoveryAndCannotBeContinued()throws Exception{
        UUID id=newTask();when(model.call(any(Prompt.class))).thenAnswer(i->{try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return DraftAgentTest.text("未完成");});
        var fast=new PersistentDraftTaskService(repository,savers,assessment,model,json,false,Duration.ofSeconds(1),Duration.ofMillis(40));
        try{DraftTaskTest.status(()->fast.continueTask(access,id,0L,"整理"),503);assertThat(fast.get(access,id).task().status()).isEqualTo("RECOVERY_REQUIRED");
            DraftTaskTest.status(()->fast.continueTask(access,id,fast.get(access,id).task().version(),"重试"),409);verify(model,atMostOnce()).call(any(Prompt.class));
        }finally{fast.shutdown();}
    }
    @Test void eightTurnsAndUncertainStatusCannotBeBypassed()throws Exception{
        UUID id=newTask();jdbc.update("update ai.cs_draft_task set turn_no=8 where task_id=?",id);clearInvocations(savers);
        DraftTaskTest.status(()->service.continueTask(access,id,0L,"第九次"),409);verify(savers,never()).create();
        UUID running=newTask();repository.claim(actor,running,0,0,UUID.randomUUID());DraftTaskTest.status(()->service.close(access,running,1L),409);
        verify(model,never()).call(any(Prompt.class));
    }
    @Test void handoffDuringPersistentRunSuppressesLateResult()throws Exception{late(false);}
    @Test void logoutDuringPersistentRunSuppressesLateResult()throws Exception{late(true);}
    void late(boolean logout)throws Exception{
        String conversation=create();UUID id=UUID.fromString(task(conversation));var started=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(model.call(any(Prompt.class))).thenAnswer(i->{started.countDown();DraftTaskTest.await(release);return DraftTaskTest.next(i.getArgument(0));});
        try{var pending=pool.submit(()->owner.call(url(id)+"/turns","POST",turn(0,"整理"),200));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            if(logout)owner.call("/internal/handoff/logout","POST",null,204);else owner.call("/api/handoff/conversations/"+conversation+"/handoff","POST",null,200);
            release.countDown();var r=pending.get(10,TimeUnit.SECONDS);assertThat(r.path("task").path("status").asText()).isEqualTo("CLOSED");assertThat(r.path("lastRun").isNull()).isTrue();assertThat(repository.owned(actor,id).lastResultJson()).isNull();
            assertThat(jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=? and role='BOT'",Long.class,UUID.fromString(conversation))).isZero();
        }finally{release.countDown();pool.shutdownNow();}
    }
}
