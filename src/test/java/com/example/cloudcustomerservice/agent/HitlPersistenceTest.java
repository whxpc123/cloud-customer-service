package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.example.cloudcustomerservice.agent.persistence.DraftTaskRepository;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.hitl.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 HTTP、CSRF、草稿数据库与框架恢复；受控模型使不应执行的分支能够确定性复现。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=hitl-test-only-password","handoff.accounts.customer2002.password=hitl-test-only-password",
    "handoff.accounts.support9001.password=hitl-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class HitlPersistenceTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersistentDraftTaskService tasks;
    @Autowired DraftTaskRepository repository;
    @Autowired DraftApplicationService drafts;
    @Autowired HitlLabService hitl;
    @MockitoBean ChatModel model;
    @MockitoBean ReturnAssessmentService assessment;
    final Actor actor=new Actor("tenant-yunshan",1001);
    final Access access=new Access(actor,id->DraftTaskTest.receipt(id,com.example.cloudcustomerservice.handoff.HandoffModel.Mode.BOT,0));
    final AtomicInteger hitlCalls=new AtomicInteger();
    final AtomicReference<Function<Prompt,ChatResponse>> hitlAnswer=new AtomicReference<>();
    Client owner,other,support;
    final String root="/internal/draft-tasks/hitl/executions";
    @BeforeEach void setup()throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
        owner=new Client("customer1001");other=new Client("customer2002");support=new Client("support9001");
        hitlCalls.set(0);hitlAnswer.set(this::next);
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("controlled-hitl").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();Prompt p=i.getArgument(0);
            if(p.getInstructions().toString().contains("这是云杉本地人工审批实验")){hitlCalls.incrementAndGet();return hitlAnswer.get().apply(p);}
            if(DraftRevisionTest.isExtract(p))return DraftAgentTest.text("{\"userDescription\":\"右侧按钮按不动，质量未核验\",\"requestedHandling\":\"申请退货\"}");
            return DraftTaskTest.next(p);});
        when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
    }
    ChatResponse next(Prompt p){
        if(p.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance))return DraftAgentTest.text("已拒绝本次模拟操作。");
        var m=java.util.regex.Pattern.compile("taskId：([0-9a-f-]{36})，draftVersion：(\\d+)").matcher(p.getInstructions().toString());assertThat(m.find()).isTrue();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("exact-call","function",SubmissionProbe.TOOL_NAME,
            "{\"taskId\":\""+m.group(1)+"\",\"draftVersion\":"+m.group(2)+"}"))).build())));
    }
    UUID prepared(boolean confirm)throws Exception {
        String c=owner.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();
        UUID task=UUID.fromString(owner.call("/internal/draft-tasks/tasks","POST",json.writeValueAsString(Map.of("conversationId",c,"orderNo","A10001","reason","QUALITY_ISSUE")),201).path("taskId").asText());
        tasks.continueTask(access,task,0L,"按钮按不动，整理草稿");drafts.generate(access,task,2L);if(confirm)drafts.confirm(access,task,1L,true);return task;
    }
    JsonNode start(UUID task,int status)throws Exception{return owner.call(root,"POST",json.writeValueAsString(Map.of("taskId",task,"draftVersion",1,"expectedTaskVersion",repository.owned(actor,task).version())),status);}
    JsonNode decision(JsonNode view,String choice,int status)throws Exception{return owner.call(root+"/"+view.path("executionId").asText()+"/decision","POST",json.writeValueAsString(Map.of("approvalId",view.path("card").path("approvalId").asText(),"expectedVersion",view.path("version").asLong(),"decision",choice)),status);}
    @Test void approveRunsRealHookWithoutMutatingPreparationTaskOrDraft()throws Exception{
        UUID id=prepared(true);var before=repository.owned(actor,id);var draft=drafts.read(access,id,1L);var waiting=start(id,201);
        assertThat(waiting.path("phase").asText()).isEqualTo("WAITING_APPROVAL");assertThat(waiting.path("simulatedExecutions").asInt()).isZero();
        assertThat(waiting.toString()).doesNotContain("threadId","checkpointId","toolFeedbacks");var done=decision(waiting,"APPROVE",200);
        assertThat(done.path("phase").asText()).isEqualTo("SIMULATION_COMPLETED");assertThat(done.path("simulatedExecutions").asInt()).isEqualTo(1);
        assertThat(done.path("actualSubmitted").asBoolean()).isFalse();assertThat(done.path("refundExecuted").asBoolean()).isFalse();
        assertThat(repository.owned(actor,id)).isEqualTo(before);assertThat(drafts.read(access,id,1L)).isEqualTo(draft);assertThat(hitlCalls).hasValue(1);
        decision(waiting,"APPROVE",409);assertThat(owner.call(root+"/"+waiting.path("executionId").asText(),"GET",null,200).path("simulatedExecutions").asInt()).isEqualTo(1);
    }
    @Test void rejectionLeavesCounterZeroAndRecordsCurrentActor()throws Exception{var done=decision(start(prepared(true),201),"REJECT",200);
        assertThat(done.path("phase").asText()).isEqualTo("REJECTED");assertThat(done.path("simulatedExecutions").asInt()).isZero();assertThat(done.path("decision").path("decidedBy").asInt()).isEqualTo(1001);}
    @Test void contentConfirmationIsRequiredAndStalePageCannotStart()throws Exception{
        UUID id=prepared(false);start(id,409);assertThat(hitlCalls).hasValue(0);drafts.confirm(access,id,1L,true);
        owner.call(root,"POST","{\"taskId\":\""+id+"\",\"draftVersion\":1,\"expectedTaskVersion\":3}",409);assertThat(hitlCalls).hasValue(0);
    }
    @Test void newlySavedDraftInvalidatesPendingOperation()throws Exception{UUID id=prepared(true);var waiting=start(id,201);drafts.generate(access,id,4L);
        decision(waiting,"APPROVE",409);var view=owner.call(root+"/"+waiting.path("executionId").asText(),"GET",null,200);assertThat(view.path("phase").asText()).isEqualTo("STALE");assertThat(view.path("simulatedExecutions").asInt()).isZero();}
    @Test void newPreparationRunInvalidatesEvenBeforeNewDraftExists()throws Exception{UUID id=prepared(true);var waiting=start(id,201);repository.claim(actor,id,4,0,UUID.randomUUID());
        decision(waiting,"APPROVE",409);assertThat(hitlCalls).hasValue(1);}
    @Test void crossUserAndTenantStopBeforeGraphRestoration()throws Exception{UUID id=prepared(true);var waiting=start(id,201);String path=root+"/"+waiting.path("executionId").asText();
        other.call(path,"GET",null,404);other.call(path+"/decision","POST",json.writeValueAsString(Map.of("approvalId",waiting.path("card").path("approvalId").asText(),"expectedVersion",2,"decision","APPROVE")),404);
        var foreign=new Access(new Actor("other-tenant",1001),access.reception());DraftTaskTest.status(()->hitl.get(foreign,UUID.fromString(waiting.path("executionId").asText())),404);assertThat(hitlCalls).hasValue(1);}
    @Test void cookieRoleCsrfAndStrictInputAreEnforced()throws Exception{
        assertThat(HttpClient.newHttpClient().send(request(root).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);support.call(root,"GET",null,403);
        UUID id=prepared(true);var waiting=start(id,201);String path=root+"/"+waiting.path("executionId").asText()+"/decision";
        String valid=json.writeValueAsString(Map.of("approvalId",waiting.path("card").path("approvalId").asText(),"expectedVersion",waiting.path("version").asLong(),"decision","APPROVE"));
        assertThat(owner.http.send(request(path).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(valid)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        owner.call(path,"POST",valid.replace("APPROVE","EDITED"),400);
        for(String key:List.of("state","threadId","arguments","approverId"))owner.call(path,"POST",valid.substring(0,valid.length()-1)+",\""+key+"\":{}}",400);
        owner.call(path,"POST","{}",400);assertThat(hitlCalls).hasValue(1);
    }
    @Test void receptionChangesStopPendingApproval()throws Exception{UUID id=prepared(true);var waiting=start(id,201);UUID conversation=repository.owned(actor,id).conversationId();
        owner.call("/api/handoff/conversations/"+conversation+"/handoff","POST","{}",200);decision(waiting,"APPROVE",409);
        UUID another=prepared(true);var second=start(another,201);
        jdbc.update("update ai.cs_conversation set version=version+1 where id=?",repository.owned(actor,another).conversationId());
        decision(second,"APPROVE",409);assertThat(hitlCalls).hasValue(2);}
    @Test void changedDraftDuringModelCannotPublishACard()throws Exception{UUID id=prepared(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        hitlAnswer.set(p->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return next(p);});
        try{var response=pool.submit(()->start(id,201));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();drafts.generate(access,id,4L);release.countDown();var view=response.get(5,TimeUnit.SECONDS);
            assertThat(view.path("phase").asText()).isEqualTo("RECOVERY_REQUIRED");assertThat(view.path("card").isNull()).isTrue();assertThat(view.path("simulatedExecutions").asInt()).isZero();
        }finally{release.countDown();pool.shutdownNow();}}
    @Test void logoutDuringModelHidesLateCard()throws Exception{UUID id=prepared(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        hitlAnswer.set(p->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return next(p);});
        try{var response=pool.submit(()->start(id,401));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();owner.call("/internal/handoff/logout","POST",null,204);release.countDown();assertThat(response.get(5,TimeUnit.SECONDS).toString()).doesNotContain("reviewedDraft");}
        finally{release.countDown();pool.shutdownNow();}}
    @Test void cleanupOnlyRemovesLocalExperimentAndGetNeverCallsModel()throws Exception{UUID id=prepared(true);var waiting=start(id,201);String path=root+"/"+waiting.path("executionId").asText();var before=drafts.read(access,id,1L);
        owner.call(path,"GET",null,200);owner.call(path,"DELETE",null,204);owner.call(path,"GET",null,404);assertThat(drafts.read(access,id,1L)).isEqualTo(before);assertThat(hitlCalls).hasValue(1);}

    final class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
        Client(String user)throws Exception{csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
            var r=http.send(request("/internal/handoff/login").header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString("username="+user+"&password=hitl-test-only-password")).build(),HttpResponse.BodyHandlers.discarding());assertThat(r.statusCode()).isEqualTo(204);csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();}
        JsonNode call(String path,String method,String data,int status)throws Exception{var b=request(path).header("Content-Type","application/json");if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
            var r=http.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(status);return r.body().isEmpty()?json.nullNode():json.readTree(r.body());}
    }
    HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));}
}
