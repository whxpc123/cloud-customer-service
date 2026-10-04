package com.example.cloudcustomerservice.hitl;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.agent.DraftTaskModel.Access;
import com.example.cloudcustomerservice.draft.*;
import com.example.cloudcustomerservice.draft.DraftModels.*;
import com.example.cloudcustomerservice.handoff.HandoffModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实慢图测试：HTTP 等待结束不会允许迟到模型继续放行，也不会无限新建工作线程。 */
class HitlServiceLimitsTest {
    @Test void timeoutsKeepCapacityUntilWorkerActuallyExitsAndLateResultCannotBecomeCard()throws Exception {
        var model=mock(ChatModel.class);var drafts=mock(DraftApplicationService.class);var store=mock(DraftVersionStore.class);
        UUID task=UUID.randomUUID(),conversation=UUID.randomUUID();Actor actor=new Actor("tenant-yunshan",1001);
        var frozen=new View(task,1,4,UUID.randomUUID(),OffsetDateTime.now(),null,true,true,
                new Confirmation(UUID.randomUUID(),task,1,1001,OffsetDateTime.now(),"DRAFT_CONTENT_ONLY"));
        when(drafts.read(any(),eq(task),eq(1L))).thenReturn(frozen);
        var source=mock(DraftVersionStore.Basis.class);when(source.conversationId()).thenReturn(conversation);when(store.source(actor,task)).thenReturn(source);
        var access=new Access(actor,id->new HandoffModel.Receipt(id,null,HandoffModel.Mode.BOT,0,null,null,null,null,""));
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("offline-slow").build());
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i->{calls.incrementAndGet();entered.countDown();release.await(30,TimeUnit.SECONDS);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("late","function",SubmissionProbe.TOOL_NAME,
                "{\"taskId\":\""+task+"\",\"draftVersion\":1}"))).build())));});
        var service=new HitlLabService(model,new ObjectMapper(),drafts,store,false,Clock.systemUTC(),Duration.ofSeconds(5),Duration.ofMinutes(10));
        // 先用屏障证明两个模型工作者已进入，再等待调用超时。
        // 原测试把框架冷启动也塞进250ms预算，满负载时模型未开始便超时，无法验证“迟到工作者”。
        var callers=Executors.newFixedThreadPool(2);
        try {
            var first=callers.submit(()->service.start(access,task,1L,4L));
            var second=callers.submit(()->service.start(access,task,1L,4L));
            assertThat(entered.await(10,TimeUnit.SECONDS)).as("两个模型工作者确实进入阻塞点").isTrue();
            var one=first.get(10,TimeUnit.SECONDS);var two=second.get(10,TimeUnit.SECONDS);
            var three=service.start(access,task,1L,4L);assertThat(calls).hasValue(2);
            for(var view:List.of(one,two,three)){assertThat(view.phase()).isEqualTo(HitlLabSession.Phase.RECOVERY_REQUIRED);assertThat(view.card()).isNull();assertThat(view.simulatedExecutions()).isZero();}
            release.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(service.get(access,one.executionId()).phase()).isEqualTo(HitlLabSession.Phase.RECOVERY_REQUIRED));
            assertThat(service.get(access,one.executionId()).card()).isNull();
            try(var restarted=new CloseableService(model,drafts,store)){
                assertThatThrownBy(()->restarted.service.get(access,one.executionId())).hasMessageContaining("404");
            }
        }finally{release.countDown();callers.shutdownNow();service.close();}
    }
    /** 新 Service 没有重建旧 MemorySaver；不会因 taskId 仍存在就伪造原审批。 */
    record CloseableService(HitlLabService service) implements AutoCloseable {
        CloseableService(ChatModel model,DraftApplicationService drafts,DraftVersionStore store){this(new HitlLabService(model,new ObjectMapper(),drafts,store,false));}
        public void close(){service.close();}
    }
    @Test void revokingPermitDuringVerificationPreventsLateCounterIncrement()throws Exception {
        UUID task=UUID.randomUUID();var probe=new SubmissionProbe(task,1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        probe.permit(()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{var result=pool.submit(()->probe.simulate(task.toString(),1));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();probe.permit(null);release.countDown();
            assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(SecurityException.class);assertThat(probe.executions()).isZero();
        }finally{release.countDown();pool.shutdownNow();}
    }
}
