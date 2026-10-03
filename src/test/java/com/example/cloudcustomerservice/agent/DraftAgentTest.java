package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftRun.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 ReactAgent 编排受控模型的 ToolCalls；不以直接 mock Agent 的返回替代框架验证。 */
class DraftAgentTest {
    final Actor owner=new Actor("tenant-yunshan",1001);
    final ReturnAssessmentService assessments=mock(ReturnAssessmentService.class);
    final List<AfterSaleDraftAgentService> services=new ArrayList<>();
    static final String CANDIDATE="候选草稿，未经审核，尚未提交。订单 A10001，用户描述：质量问题。质量尚未核验；材料要求待人工确认。没有执行退款。";
    @BeforeEach void fixture(){when(assessments.assess(any(),anyString(),any())).thenAnswer(i->assessment(i.getArgument(1),AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
        when(assessments.unavailable(any())).thenReturn(new Assessment(AssessmentStatus.TEMPORARILY_UNAVAILABLE,null,ReturnReason.UNKNOWN,"暂时无法核验",List.of(),Instant.now(),false));}
    @AfterEach void close(){services.forEach(AfterSaleDraftAgentService::close);}
    static Assessment assessment(String order,AssessmentStatus status,boolean evidence){
        var facts=status==AssessmentStatus.NOT_ACCESSIBLE?null:new OrderFacts(order,"ORDINARY",OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),OffsetDateTime.parse("2026-08-27T23:59:59+08:00"),QualityVerification.UNVERIFIED,"refund-policy","3.2");
        return new Assessment(status,facts,ReturnReason.QUALITY_ISSUE,"质量尚未核验，需人工确认",evidence?List.of(new PolicyEvidence("policy-1","refund-policy","3.2","经核验质量问题按政策处理，不能自动批准")):List.of(),Instant.now(),false);
    }
    static ChatResponse text(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    static ChatResponse tool(String... names){
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(Arrays.stream(names)
            .map(n->new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",n,"{}")).toList()).build())));
    }
    ChatModel model(Function<Prompt,ChatResponse> response){
        var model=mock(ChatModel.class);when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("offline-controlled").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->response.apply(i.getArgument(0)));return model;
    }
    ChatModel plan(ChatResponse... steps){var i=new AtomicInteger();return model(p->steps[Math.min(i.getAndIncrement(),steps.length-1)]);}
    AfterSaleDraftAgentService service(ChatModel model){var s=new AfterSaleDraftAgentService(model,assessments,false);services.add(s);return s;}
    DraftRun run(ChatModel model){return service(model).prepare(owner,"a10001",ReturnReason.QUALITY_ISSUE,"整理草稿",false);}
    @Test void actualGraphExecutesTwoToolsAndSerializesIsoFacts(){
        var prompts=new CopyOnWriteArrayList<Prompt>();var n=new AtomicInteger();
        var result=run(model(p->{prompts.add(p);return switch(n.getAndIncrement()){case 0->tool("inspectAfterSale");case 1->tool("readDraftTemplate");default->text(CANDIDATE);};}));
        assertThat(result.status()).isEqualTo(CANDIDATE_UNVALIDATED);assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.executedSteps()).containsExactly("inspectAfterSale:NEED_QUALITY_VERIFICATION","readDraftTemplate:READ");
        assertThat(result.assessment().verifiedFacts().qualityVerification()).isEqualTo(QualityVerification.UNVERIFIED);
        assertThat(result.submitted()).isFalse();assertThat(result.refundExecuted()).isFalse();
        assertThat(prompts.get(2).getInstructions().toString()).contains("2026-08-20T10:00:00+08:00");
        for(var prompt:prompts){var opts=(ToolCallingChatOptions)prompt.getOptions();assertThat(opts.getInternalToolExecutionEnabled()).isFalse();assertThat(opts.getMaxTokens()).isEqualTo(1800);
            assertThat(opts.getToolCallbacks()).extracting(c->c.getToolDefinition().name()).containsExactlyInAnyOrder("inspectAfterSale","readDraftTemplate");}
        verify(assessments).assess(owner,"A10001",ReturnReason.QUALITY_ISSUE);
    }
    @Test void skippedInspectionCannotCreateCandidate(){var r=run(plan(text(CANDIDATE)));assertThat(r.status()).isEqualTo(NEEDS_ATTENTION);assertThat(r.candidateText()).isNull();verifyNoInteractions(assessments);}
    @Test void templateFirstIsActuallyBlocked(){var r=run(plan(tool("readDraftTemplate"),text(CANDIDATE)));assertThat(r.status()).isEqualTo(NEEDS_ATTENTION);assertThat(r.executedSteps()).containsExactly("readDraftTemplate:BLOCKED");}
    @Test void inaccessibleFactsNeverLeakAndTemplateStaysBlocked(){
        when(assessments.assess(any(),anyString(),any())).thenReturn(assessment("A10001",AssessmentStatus.NOT_ACCESSIBLE,false));
        var r=run(plan(tool("inspectAfterSale"),tool("readDraftTemplate"),text(CANDIDATE)));
        assertThat(r.status()).isEqualTo(NEEDS_ATTENTION);assertThat(r.assessment().verifiedFacts()).isNull();assertThat(r.candidateText()).isNull();
    }
    @Test void missingPolicyCannotProduceDraft(){when(assessments.assess(any(),anyString(),any())).thenReturn(assessment("A10001",AssessmentStatus.NO_EVIDENCE,false));
        var r=run(plan(tool("inspectAfterSale"),tool("readDraftTemplate"),text(CANDIDATE)));assertThat(r.status()).isEqualTo(NEEDS_ATTENTION);assertThat(r.candidateText()).isNull();}
    @Test void failedLookupIsGenericAndCached(){when(assessments.assess(any(),anyString(),any())).thenThrow(new IllegalStateException("secret-sql-password"));
        var r=run(plan(tool("inspectAfterSale"),tool("inspectAfterSale"),tool("readDraftTemplate"),text(CANDIDATE)));
        assertThat(r.status()).isEqualTo(NEEDS_ATTENTION);assertThat(r.toString()).doesNotContain("secret-sql-password");verify(assessments,times(1)).assess(any(),anyString(),any());}
    @Test void onlyInspectModeEnforcesTemplateDenialEvenIfModelIgnoresIt(){
        var r=service(plan(tool("inspectAfterSale"),tool("readDraftTemplate"),text(CANDIDATE))).prepare(owner,"A10001",ReturnReason.QUALITY_ISSUE,"只检查",true);
        assertThat(r.status()).isEqualTo(INSPECTED);assertThat(r.candidateText()).isNull();assertThat(r.executedSteps()).contains("readDraftTemplate:BLOCKED");}
    @Test void frameworkModelLimitStopsRepeatedToolsAtSixRealCalls(){
        var m=plan(tool("inspectAfterSale"));var r=run(m);assertThat(r.status()).isEqualTo(RUN_FAILED);assertThat(r.modelCalls()).isEqualTo(6);
        verify(m,times(6)).call(any(Prompt.class));verify(assessments,times(1)).assess(any(),anyString(),any());}
    @Test void toolBudgetStopsNinthAttemptEvenInsideOneModelResponse(){
        var r=run(plan(tool(Collections.nCopies(9,"inspectAfterSale").toArray(String[]::new)),text(CANDIDATE)));
        assertThat(r.status()).isEqualTo(RUN_FAILED);assertThat(r.executedSteps()).contains("TOOL_LIMIT_EXCEEDED");assertThat(r.toolCalls()).isGreaterThanOrEqualTo(9);
        verify(assessments,times(1)).assess(any(),anyString(),any());}
    @ParameterizedTest @ValueSource(strings={"已经为您提交申请","已提交售后申请","退货已获批","退款已到账","Your request was submitted"})
    void unsafeClaimsAreSuppressed(String text){var r=run(plan(tool("inspectAfterSale"),tool("readDraftTemplate"),text(text)));assertThat(r.candidateText()).isNull();assertThat(r.status()).isEqualTo(NEEDS_ATTENTION);}
    @Test void toolSchemaHasNoModelControlledIdentityOrOrder(){
        var tools=new AfterSaleDraftTools(assessments,owner,"A10001",ReturnReason.UNKNOWN,false,new DraftRunBudget(Duration.ofSeconds(1)));
        for(var cb:ToolCallbacks.from(tools))assertThat(cb.getToolDefinition().inputSchema()).doesNotContain("tenantId","userId","orderNo");}
    @Test void concurrentRunsKeepIndependentFactsCachesAndThreadIds()throws Exception{
        var m=model(p->{long responses=p.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();return responses==0?tool("inspectAfterSale"):responses==1?tool("readDraftTemplate"):text(CANDIDATE);});
        var service=service(m);var pool=Executors.newFixedThreadPool(2);
        try{var one=pool.submit(()->service.prepare(owner,"A10001",ReturnReason.QUALITY_ISSUE,"整理草稿",false));
            var two=pool.submit(()->service.prepare(new Actor("another-tenant",2002),"A10002",ReturnReason.UNKNOWN,"整理草稿",false));
            var a=one.get(10,TimeUnit.SECONDS);var b=two.get(10,TimeUnit.SECONDS);assertThat(a.runId()).isNotEqualTo(b.runId());
            assertThat(a.status()).isEqualTo(CANDIDATE_UNVALIDATED);assertThat(b.status()).isEqualTo(CANDIDATE_UNVALIDATED);
            assertThat(a.assessment().verifiedFacts().orderNo()).isEqualTo("A10001");assertThat(b.assessment().verifiedFacts().orderNo()).isEqualTo("A10002");
            verify(assessments).assess(new Actor("another-tenant",2002),"A10002",ReturnReason.UNKNOWN);
        }finally{pool.shutdownNow();}
    }
    @Test void slowProviderHasBoundedWaitAndNoPartialCandidate(){
        var m=model(p->{try{Thread.sleep(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return text(CANDIDATE);});
        var s=new AfterSaleDraftAgentService(m,assessments,false,Duration.ofSeconds(2),Duration.ofMillis(50));services.add(s);
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThat(s.prepare(owner,"A10001",ReturnReason.UNKNOWN,"整理",false).status()).isEqualTo(RUN_FAILED));
    }
    @Test void totalDeadlineStopsAStillRunningGraph(){
        var m=model(p->{try{Thread.sleep(40);}catch(InterruptedException e){Thread.currentThread().interrupt();}return tool("inspectAfterSale");});
        var s=new AfterSaleDraftAgentService(m,assessments,false,Duration.ofMillis(150),Duration.ofSeconds(1));services.add(s);
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertThat(s.prepare(owner,"A10001",ReturnReason.UNKNOWN,"整理",false).status()).isEqualTo(RUN_FAILED));
    }
    @Test void thirdConcurrentRunIsRejectedWithoutQueueingMoreModelCalls()throws Exception{
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);
        var m=model(p->{started.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return text("只读");});
        var s=service(m);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->s.prepare(owner,"A10001",ReturnReason.UNKNOWN,"整理",false));
            var b=pool.submit(()->s.prepare(owner,"A10001",ReturnReason.UNKNOWN,"整理",false));assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->s.prepare(owner,"A10001",ReturnReason.UNKNOWN,"第三次",false))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
            release.countDown();a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);verify(m,times(2)).call(any(Prompt.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void invalidInputAndGlobalToolsNeverReachModel(){var m=plan(text("unused"));var s=service(m);
        assertThatThrownBy(()->s.prepare(owner,"A10001;drop",ReturnReason.UNKNOWN,"x",false)).isInstanceOf(IllegalArgumentException.class);
        when(m.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().toolNames(Set.of("refund")).build());
        assertThatThrownBy(()->s.prepare(owner,"A10001",ReturnReason.UNKNOWN,"x",false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verify(m,never()).call(any(Prompt.class));}
    static void assertTimeoutPreemptively(Duration timeout,org.junit.jupiter.api.function.Executable e){Assertions.assertTimeoutPreemptively(timeout,e);}
}
