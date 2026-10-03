package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.*;
import com.example.cloudcustomerservice.handoff.HandoffModel.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static com.example.cloudcustomerservice.agent.DraftAgentTest.*;
import static com.example.cloudcustomerservice.agent.DraftTaskModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用真实 ReactAgent 与 MemorySaver 验证续写，受控模型只替代网络；不 mock 检查点或 Agent。 */
class DraftTaskTest {
    final com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor owner=new com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor("tenant-yunshan",1001);
    final UUID conversation=UUID.randomUUID();
    final Access access=new Access(owner,id->receipt(id,Mode.BOT,0));
    final ReturnAssessmentService assessments=mock(ReturnAssessmentService.class);
    final List<LocalDraftTaskService> services=new ArrayList<>();
    @BeforeEach void fixture(){when(assessments.assess(any(),anyString(),any())).thenAnswer(i->assessment(i.getArgument(1),AssessmentStatus.NEED_QUALITY_VERIFICATION,true));}
    @AfterEach void close(){services.forEach(LocalDraftTaskService::close);}
    static Receipt receipt(UUID id,Mode mode,long version){return new Receipt(id,null,mode,version,null,null,null,null,"测试接待状态");}
    static ChatModel model(Function<Prompt,ChatResponse> fn){var m=mock(ChatModel.class);when(m.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("controlled-task").build());when(m.call(any(Prompt.class))).thenAnswer(i->fn.apply(i.getArgument(0)));return m;}
    /** 每轮只统计最新用户消息之后的工具结果；历史仍留在真实模型 Prompt 中。 */
    static ChatResponse next(Prompt p){
        int count=0;for(var message:p.getInstructions()){if(message instanceof UserMessage)count=0;else if(message instanceof ToolResponseMessage)count++;}
        return count==0?tool("inspectAfterSale"):count==1?tool("readDraftTemplate"):text(CANDIDATE);
    }
    LocalDraftTaskService service(ChatModel m){return service(m,100,8,Duration.ofSeconds(10),Duration.ofSeconds(5));}
    LocalDraftTaskService service(ChatModel m,int maxTasks,int maxTurns,Duration run,Duration call){var s=new LocalDraftTaskService(m,assessments,false,maxTasks,maxTurns,run,call);services.add(s);return s;}
    UUID create(LocalDraftTaskService s){return s.create(access,conversation,"A10001",ReturnReason.QUALITY_ISSUE).taskId();}
    static void status(Runnable work,int code){assertThatThrownBy(work::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(code));}

    @Test void threeTurnsKeepHistoryOnceResetBudgetsAndReadStateWithoutInvoking(){
        var prompts=new CopyOnWriteArrayList<Prompt>();var m=model(p->{prompts.add(p);return next(p);});var s=service(m);var id=create(s);
        verify(m,never()).call(any(Prompt.class));assertThat(s.get(access,id).state().checkpointCount()).isZero();
        Set<String> runIds=new HashSet<>();int previous=0;
        for(String message:List.of("第一次：插头发热","改为：外壳破损","我已上传照片，请标注待核验")){
            var result=s.continueTask(access,id,message);assertThat(result.task().taskId()).isEqualTo(id);assertThat(result.task().status()).isEqualTo(Status.CANDIDATE_UNVALIDATED);
            assertThat(result.lastRun().modelCalls()).isEqualTo(3);assertThat(result.lastRun().toolCalls()).isEqualTo(2);runIds.add(result.lastRun().runId());
            assertThat(result.state().checkpointCount()).isGreaterThan(previous);previous=result.state().checkpointCount();
            assertThat(result.state().userMessages()).isEqualTo(runIds.size());
            for(int i=prompts.size()-3;i<prompts.size();i++)assertThat(prompts.get(i).getInstructions().stream().filter(UserMessage.class::isInstance).filter(v->v.getText().contains(message))).hasSize(1);
        }
        assertThat(runIds).hasSize(3);assertThat(prompts.get(6).getInstructions().toString()).contains("插头发热","外壳破损","照片",CANDIDATE);
        assertThat(s.get(access,id).state().userMessages()).isEqualTo(3);s.get(access,id);s.list(owner);
        verify(m,times(9)).call(any(Prompt.class));verify(assessments,times(3)).assess(owner,"A10001",ReturnReason.QUALITY_ISSUE);
    }
    @Test void latestUnavailableFactsReplaceEarlierCandidateAndNeverUseCachedPolicy(){
        var s=service(model(DraftTaskTest::next));var id=create(s);assertThat(s.continueTask(access,id,"整理").lastRun().candidateText()).isNotNull();
        when(assessments.assess(any(),anyString(),any())).thenReturn(assessment("A10001",AssessmentStatus.NOT_ACCESSIBLE,false));
        var second=s.continueTask(access,id,"修改描述");assertThat(second.task().status()).isEqualTo(Status.NEEDS_ATTENTION);assertThat(second.lastRun().candidateText()).isNull();
        assertThat(second.lastRun().assessment().verifiedFacts()).isNull();assertThat(second.lastRun().executedSteps()).containsExactly("inspectAfterSale:NOT_ACCESSIBLE","readDraftTemplate:BLOCKED");
        assertThat(s.get(access,id).lastRun().candidateText()).isNull();verify(assessments,times(2)).assess(any(),anyString(),any());
    }
    @Test void historicalInspectionAndTemplateDoNotCountAsCurrentTurnChecks(){
        var bypass=new AtomicBoolean();var s=service(model(p->bypass.get()?text(CANDIDATE):next(p)));var id=create(s);s.continueTask(access,id,"整理");bypass.set(true);
        var r=s.continueTask(access,id,"沿用历史直接输出");assertThat(r.lastRun().candidateText()).isNull();assertThat(r.lastRun().assessment()).isNull();assertThat(r.lastRun().toolCalls()).isZero();
    }
    @Test void otherTasksAndOwnersCannotSeeHistoryOrUseForeignIdentifiers(){
        var prompts=new CopyOnWriteArrayList<Prompt>();var s=service(model(p->{prompts.add(p);return next(p);}));UUID one=create(s),two=create(s);
        s.continueTask(access,one,"唯一秘密描述");s.continueTask(access,two,"另一个任务");assertThat(prompts.get(3).getInstructions().toString()).doesNotContain("唯一秘密描述");
        for(var foreign:List.of(new com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor("tenant-yunshan",2002),new com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor("another-tenant",1001))){
            var a=new Access(foreign,id->{throw new AssertionError("不得先查他人的会话");});status(()->s.get(a,one),404);status(()->s.continueTask(a,one,"x"),404);status(()->s.discard(a,one),404);assertThat(s.list(foreign)).isEmpty();
        }
    }
    @Test void sameTaskConcurrentContinuationAndDiscardAreRejectedBeforeMoreModelCalls()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var m=model(p->{started.countDown();await(release);return text("停止");});var s=service(m);var id=create(s);var pool=Executors.newSingleThreadExecutor();
        try{var running=pool.submit(()->s.continueTask(access,id,"第一轮"));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            status(()->s.continueTask(access,id,"第二轮"),409);status(()->s.discard(access,id),409);assertThat(s.get(access,id).task().status()).isEqualTo(Status.RUNNING);
            release.countDown();running.get(5,TimeUnit.SECONDS);assertThat(s.get(access,id).task().turnNo()).isEqualTo(1);verify(m,times(1)).call(any(Prompt.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void globalCapacityRejectsThirdTaskWithoutConsumingATurn()throws Exception{
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);var s=service(model(p->{started.countDown();await(release);return text("停止");}));UUID one=create(s),two=create(s),three=create(s);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->s.continueTask(access,one,"1"));var b=pool.submit(()->s.continueTask(access,two,"2"));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();status(()->s.continueTask(access,three,"3"),429);
            assertThat(s.get(access,three).task().turnNo()).isZero();release.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void failedGraphIsTerminalAndPureGetCannotRetryIt(){
        var m=model(p->{throw new IllegalStateException("private-network-secret");});var s=service(m);var id=create(s);var r=s.continueTask(access,id,"执行");
        assertThat(r.task().status()).isEqualTo(Status.FAILED);assertThat(r.toString()).doesNotContain("private-network-secret");status(()->s.continueTask(access,id,"重试"),409);
        s.get(access,id);verify(m,times(1)).call(any(Prompt.class));s.discard(access,id);status(()->s.get(access,id),404);
    }
    @Test void timedOutProviderLeavesNoCandidateAndCannotContinue(){
        var s=service(model(p->{try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return text(CANDIDATE);}),100,8,Duration.ofSeconds(1),Duration.ofMillis(40));
        var id=create(s);assertThat(s.continueTask(access,id,"执行").task().status()).isEqualTo(Status.FAILED);status(()->s.continueTask(access,id,"重试"),409);
    }
    @Test void taskAndTurnLimitsCleanupAndNewServiceDoesNotRecoverTasks(){
        var m=model(DraftTaskTest::next);var s=service(m,1,2,Duration.ofSeconds(10),Duration.ofSeconds(5));var id=create(s);status(()->create(s),429);
        s.continueTask(access,id,"1");s.continueTask(access,id,"2");status(()->s.continueTask(access,id,"3"),409);verify(m,times(6)).call(any(Prompt.class));
        status(()->service(m).get(access,id),404);s.discard(access,id);assertThat(s.list(owner)).isEmpty();status(()->s.get(access,id),404);assertThat(create(s)).isNotEqualTo(id);
    }
    @Test void stateChangeAfterRunClosesTaskAndSuppressesFacts(){
        var version=new AtomicLong();var changed=new Access(owner,id->receipt(id,Mode.BOT,version.get()));var s=service(model(p->{version.incrementAndGet();return next(p);}));var id=s.create(changed,conversation,"A10001",ReturnReason.QUALITY_ISSUE).taskId();
        var r=s.continueTask(changed,id,"整理");assertThat(r.task().status()).isEqualTo(Status.CLOSED);assertThat(r.lastRun().assessment()).isNull();assertThat(r.lastRun().candidateText()).isNull();status(()->s.continueTask(changed,id,"继续"),409);
    }
    @ParameterizedTest @ValueSource(strings={"已查看上传的照片，确认外壳破损。","从照片可以看出质量问题。","改为订单 A10002 的草稿。","已提交售后申请。"})
    void explicitPhotoOrderAndSubmissionClaimsAreHidden(String unsafe){
        var s=service(model(p->{var response=next(p);return response.getResult().getOutput().hasToolCalls()?response:text(unsafe);}));var r=s.continueTask(access,create(s),"整理");assertThat(r.lastRun().candidateText()).isNull();assertThat(r.task().status()).isEqualTo(Status.NEEDS_ATTENTION);
    }
    static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
