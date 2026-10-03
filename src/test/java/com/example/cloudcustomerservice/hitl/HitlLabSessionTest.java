package com.example.cloudcustomerservice.hitl;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.draft.DraftModels.Body;
import com.example.cloudcustomerservice.draft.DraftModels.ProposedText;
import com.example.cloudcustomerservice.draft.DraftModels.Confirmation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.cloudcustomerservice.hitl.HitlLabSession.*;

/** 使用真实 ReactAgent、HITL、MemorySaver 与受保护方法，只有模型响应受控。 */
class HitlLabSessionTest {
    final Actor actor=new Actor("tenant-yunshan",1001);
    final UUID task=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"),ZoneOffset.UTC);
    final DraftModelsFixture fixture=new DraftModelsFixture();
    class DraftModelsFixture {
        com.example.cloudcustomerservice.draft.DraftModels.View view(){return new com.example.cloudcustomerservice.draft.DraftModels.View(task,2,7,UUID.randomUUID(),OffsetDateTime.now(clock),
                new Body(1,"A10001",new ProposedText("按钮按不动","申请退货"),null,"仅确认内容"),true,true,
                new Confirmation(UUID.randomUUID(),task,2,1001,OffsetDateTime.now(clock),"DRAFT_CONTENT_ONLY"));}
    }
    ChatResponse call(String id,String name,String args){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(id,"function",name,args))).build())));}
    ChatResponse valid(){return call("call-one",SubmissionProbe.TOOL_NAME,"{\"taskId\":\""+task+"\",\"draftVersion\":2}");}
    ChatResponse text(){return new ChatResponse(List.of(new Generation(new AssistantMessage("已拒绝模拟操作。"))));}
    ChatModel model(ChatResponse... steps){var model=mock(ChatModel.class);var n=new AtomicInteger();
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("offline-hitl").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->steps[Math.min(n.getAndIncrement(),steps.length-1)]);return model;}
    HitlLabSession session(ChatModel model){return new HitlLabSession(model,new ObjectMapper().findAndRegisterModules(),actor,fixture.view(),false,clock,Duration.ofMinutes(10));}
    View start(HitlLabSession session){return session.start(actor,()->{},Callable::call);}
    View decide(HitlLabSession session,View view,Choice choice){return session.decide(actor,view.card().approvalId(),view.version(),choice,()->{},Callable::call);}

    @Test void realHookInterruptsBeforeToolAndApprovedFeedbackResumesExactlyOnce(){
        var model=model(valid());var session=session(model);var waiting=start(session);
        assertThat(waiting.phase()).isEqualTo(Phase.WAITING_APPROVAL);assertThat(waiting.simulatedExecutions()).isZero();
        assertThat(waiting.card().toolCallId()).isEqualTo("call-one");assertThat(waiting.card().scope()).isEqualTo("SIMULATE_SUBMISSION_ONLY");
        var done=decide(session,waiting,Choice.APPROVE);assertThat(done.phase()).as(done.toString()).isEqualTo(Phase.SIMULATION_COMPLETED);
        assertThat(done.simulatedExecutions()).isEqualTo(1);assertThat(done.actualSubmitted()).isFalse();assertThat(done.refundExecuted()).isFalse();
        assertThat(done.decision().decidedBy()).isEqualTo(1001);assertThat(done.decision().choice()).isEqualTo(Choice.APPROVE);
        assertThatThrownBy(()->decide(session,waiting,Choice.APPROVE)).hasMessageContaining("409");
        assertThat(session.snapshot(actor).simulatedExecutions()).isEqualTo(1);verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void rejectedFeedbackReallySkipsTool(){var session=session(model(valid(),text()));var done=decide(session,start(session),Choice.REJECT);
        assertThat(done.phase()).isEqualTo(Phase.REJECTED);assertThat(done.simulatedExecutions()).isZero();}
    @Test void newInterruptionAfterRejectionCannotInheritApproval(){var session=session(model(valid(),valid()));var done=decide(session,start(session),Choice.REJECT);
        assertThat(done.phase()).isEqualTo(Phase.NEW_APPROVAL_REQUIRED);assertThat(done.simulatedExecutions()).isZero();
        assertThatThrownBy(()->decide(session,done,Choice.APPROVE)).hasMessageContaining("409");}
    @Test void normalAssistantTextIsNotAnApprovalCard(){var view=start(session(model(text())));assertThat(view.phase()).isEqualTo(Phase.RECOVERY_REQUIRED);assertThat(view.card()).isNull();}
    @ParameterizedTest @ValueSource(strings={"{\"taskId\":\"wrong\",\"draftVersion\":2}","{}","{\"taskId\":\"TASK\",\"draftVersion\":3}","{\"taskId\":\"TASK\",\"draftVersion\":2,\"extra\":true}","{\"taskId\":\"TASK\",\"draftVersion\":2,\"draftVersion\":2}"})
    void modifiedOrAmbiguousArgumentsCannotBecomeACard(String args){var session=session(model(call("x",SubmissionProbe.TOOL_NAME,args.replace("TASK",task.toString()))));
        var view=start(session);assertThat(view.phase()).isEqualTo(Phase.RECOVERY_REQUIRED);assertThat(view.simulatedExecutions()).isZero();assertThat(view.card()).isNull();}
    @Test void multipleOrUnknownCallsStopBeforeTools(){var message=AssistantMessage.builder().content("").toolCalls(List.of(
            valid().getResult().getOutput().getToolCalls().get(0),valid().getResult().getOutput().getToolCalls().get(0))).build();
        for(ChatResponse response:List.of(new ChatResponse(List.of(new Generation(message))),call("x","refund","{}"))){var view=start(session(model(response)));
            assertThat(view.phase()).isEqualTo(Phase.RECOVERY_REQUIRED);assertThat(view.simulatedExecutions()).isZero();}}
    @Test void crossOwnerAndWrongApprovalDoNotResume(){var session=session(model(valid()));var waiting=start(session);
        assertThatThrownBy(()->session.decide(new Actor("tenant-yunshan",2002),waiting.card().approvalId(),waiting.version(),Choice.APPROVE,()->{},Callable::call)).hasMessageContaining("404");
        assertThatThrownBy(()->session.decide(actor,UUID.randomUUID(),waiting.version(),Choice.APPROVE,()->{},Callable::call)).hasMessageContaining("409");
        assertThat(session.snapshot(actor).phase()).isEqualTo(Phase.WAITING_APPROVAL);}
    @Test void expiredCardCannotResume(){var session=new HitlLabSession(model(valid()),new ObjectMapper(),actor,fixture.view(),false,clock,Duration.ZERO);var waiting=start(session);
        assertThatThrownBy(()->decide(session,waiting,Choice.APPROVE)).hasMessageContaining("409");assertThat(session.snapshot(actor).phase()).isEqualTo(Phase.EXPIRED);}
    @Test void changedDraftBlocksBeforeApprovalConsumption(){var session=session(model(valid()));var waiting=start(session);
        assertThatThrownBy(()->session.decide(actor,waiting.card().approvalId(),waiting.version(),Choice.APPROVE,()->{throw conflict("版本已变");},Callable::call)).hasMessageContaining("409");
        assertThat(session.snapshot(actor).phase()).isEqualTo(Phase.STALE);assertThat(session.snapshot(actor).decision()).isNull();}
    @Test void resumeFailureIsNotSuccessAndPermitIsRevoked(){var session=session(model(valid()));var waiting=start(session);
        var failed=session.decide(actor,waiting.card().approvalId(),waiting.version(),Choice.APPROVE,()->{},work->{throw new TimeoutException();});
        assertThat(failed.phase()).isEqualTo(Phase.RECOVERY_REQUIRED);assertThat(failed.decision()).isNotNull();assertThat(failed.simulatedExecutions()).isZero();}
    @Test void twoSimultaneousDecisionsCannotConsumeSameCard()throws Exception{var session=session(model(valid()));var waiting=start(session);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{var first=pool.submit(()->session.decide(actor,waiting.card().approvalId(),waiting.version(),Choice.APPROVE,()->{},work->{entered.countDown();release.await();return work.call();}));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->decide(session,waiting,Choice.REJECT)).hasMessageContaining("409");
            release.countDown();assertThat(first.get(3,TimeUnit.SECONDS).simulatedExecutions()).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();}}
    @Test void globalToolsNeverReachTheGraph(){var model=model(valid());when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().toolNames(Set.of("refund")).build());
        assertThatThrownBy(()->session(model)).hasMessageContaining("503");verify(model,never()).call(any(Prompt.class));}
    @Test void probeRequiresPermitMatchingParametersAndFreshChecks(){var probe=new SubmissionProbe(task,2);
        assertThatThrownBy(()->probe.simulate(task.toString(),2)).isInstanceOf(SecurityException.class);
        probe.permit(()->{});assertThatThrownBy(()->probe.simulate(task.toString(),3)).isInstanceOf(IllegalArgumentException.class);
        probe.permit(()->{throw new IllegalStateException("stale");});assertThatThrownBy(()->probe.simulate(task.toString(),2)).hasMessage("stale");assertThat(probe.executions()).isZero();
        probe.permit(()->{});probe.simulate(task.toString(),2);probe.simulate(task.toString(),2);assertThat(probe.executions()).isEqualTo(1);
        probe.permit(null);assertThatThrownBy(()->probe.simulate(task.toString(),2)).isInstanceOf(SecurityException.class);}
}
