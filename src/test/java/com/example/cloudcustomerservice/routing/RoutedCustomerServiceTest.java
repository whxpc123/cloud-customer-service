package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.aftersale.*;
import com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import com.example.cloudcustomerservice.rag.*;
import com.example.cloudcustomerservice.tool.*;
import com.example.cloudcustomerservice.order.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.cloudcustomerservice.routing.CustomerRouter.*;

/** 真正检查分派副作用、跨流程历史和原结果保留；假模型仅决定预设路由。 */
class RoutedCustomerServiceTest {
    final Actor actor = new Actor("tenant-yunshan",1001);
    AdvisorKnowledgeAnswerService knowledge;
    AfterSaleChatService afterSale;
    RoutingOrderHandler orders;
    ChatMemory memory;
    CustomerRouter.Classifier classifier;
    RoutedCustomerService service;
    RoutingConversation conversation;
    @BeforeEach void setup() {
        knowledge=mock(AdvisorKnowledgeAnswerService.class); afterSale=mock(AfterSaleChatService.class);
        orders=mock(RoutingOrderHandler.class); classifier=mock(CustomerRouter.Classifier.class);
        memory=MessageWindowChatMemory.builder().maxMessages(20).build();
        service=new RoutedCustomerService(new CustomerRouter(classifier),knowledge,afterSale,orders,memory,new RoutingStatistics());
        conversation=new RoutingConversation();
    }
    void select(Route route) { when(classifier.classify(any())).thenReturn(new Proposal(route,false,false)); }
    @Test void humanRouteReturnsCandidateOnlyWithoutClaimingHandoff() {
        service.answer(actor,conversation,"你好！");
        var r=service.answer(actor,conversation,"我要转人工");
        assertThat(r.humanStatus()).isNull();
        assertThat(r.status()).isEqualTo("HANDOFF_REQUIRED");
        assertThat(conversation.history()).hasSize(4);
        assertThat(conversation.history().get(3).getText()).isEqualTo(r.answer());
        verifyNoInteractions(classifier,knowledge,afterSale,orders);
    }
    @Test void multiTaskAndFailureDoNotInvokeAnyBusinessHandler() {
        when(classifier.classify(any())).thenReturn(new Proposal(Route.ORDER_QUERY,false,true));
        var r=service.answer(actor,conversation,"查订单并问开票");
        assertThat(r.status()).isEqualTo("MULTIPLE_INDEPENDENT_TASKS");
        when(classifier.classify(any())).thenThrow(new IllegalStateException());
        assertThat(service.answer(actor,conversation,"查订单").answer()).contains("暂时不可用").doesNotContain("请补充");
        verifyNoInteractions(knowledge,afterSale,orders);
    }
    @Test void selectedOrderHandlerReceivesServerActorAndTranscriptOnly() {
        service.answer(actor,conversation,"你好"); select(Route.ORDER_QUERY);
        var order=new RoutingOrderHandler.Result("MISSING_ORDER_NO","请提供订单号",OrderLookupResult.missingOrderNo());
        when(orders.answer(eq(actor),eq("我要查订单"),anyList())).thenReturn(order);
        var r=service.answer(actor,conversation,"我要查订单");
        assertThat(r.order()).isSameAs(order);
        assertThat(r.knowledge()).isNull(); assertThat(r.afterSale()).isNull();
        verify(orders).answer(eq(actor),eq("我要查订单"),argThat(h->h.size()==2));
        verify(classifier).classify(argThat(i->i.recentConversation().contains("您好，我是云杉")));
        verifyNoInteractions(knowledge,afterSale);
    }
    @Test void ragGetsPrivateWorkHistoryPreservesEvidenceAndLeavesNoWorkingMemory() {
        service.answer(actor,conversation,"你好"); select(Route.KNOWLEDGE);
        var reference=new KnowledgeReference("block","refund-policy","退货规则","3.2",0,"refund",0.8,"实际证据");
        var key=new AtomicReference<String>();
        when(knowledge.answer(eq(actor.tenantId()),anyString(),eq(actor.userId()),eq("退货运费"))).thenAnswer(i->{
            String id=i.getArgument(1);key.set(AdvisorKnowledgeAnswerService.memoryId(actor.tenantId(),id,actor.userId()));
            assertThat(memory.get(key.get())).hasSize(2);
            memory.add(key.get(),List.of(new UserMessage("增强问题"),new AssistantMessage("未经检查的中间文本")));
            return new AdvisorKnowledgeAnswerResponse("rag-id",id,"退货运费",KnowledgeAnswerStatus.ANSWERED,"实际答复",List.of(reference));
        });
        var r=service.answer(actor,conversation,"退货运费");
        assertThat(r.knowledge().conversationId()).isEqualTo(conversation.id).isNotEqualTo(conversation.workId);
        assertThat(r.knowledge().references()).containsExactly(reference);
        assertThat(memory.get(key.get())).isEmpty();
        assertThat(conversation.history()).hasSize(4);
        assertThat(conversation.history().get(2).getText()).isEqualTo("退货运费");
        assertThat(conversation.routingHistory()).doesNotContain("增强问题","未经检查","MODEL_CLASSIFIED");
        verifyNoInteractions(afterSale,orders);
    }
    @Test void afterSaleFollowupCanUseOrderFromDifferentFlowAndKeepsAssessmentPayload() {
        select(Route.ORDER_QUERY);
        when(orders.answer(any(),anyString(),anyList())).thenReturn(new RoutingOrderHandler.Result("FOUND","已查询本地订单",null));
        service.answer(actor,conversation,"查 A10001"); select(Route.AFTER_SALE_PRECHECK);
        var assessment=new Assessment(AssessmentStatus.NO_EVIDENCE,null,ReturnReason.QUALITY_ISSUE,"依据不足",List.of(),java.time.Instant.now(),false);
        var result=new AfterSaleChatService.Result("check-id","CHECKED","依据不足",List.of(assessment),true,"LOCAL_FIXTURE");
        var workKey=new AtomicReference<String>();
        when(afterSale.answer(eq(actor),anyString(),eq("它有质量问题，能退吗"))).thenAnswer(i->{
            workKey.set(i.getArgument(1));assertThat(memory.get(workKey.get()).get(0).getText()).contains("A10001");return result;
        });
        var r=service.answer(actor,conversation,"它有质量问题，能退吗");
        assertThat(r.afterSale()).isSameAs(result);assertThat(memory.get(workKey.get())).isEmpty();
        assertThat(conversation.history()).hasSize(4); verifyNoInteractions(knowledge);
    }
    @Test void handlerFailureCleansPrivateMemoryAndNeverFallsBackToOtherFlow() {
        select(Route.KNOWLEDGE);
        when(knowledge.answer(anyString(),anyString(),anyLong(),anyString())).thenThrow(new IllegalStateException("secret"));
        var r=service.answer(actor,conversation,"退货规则");
        assertThat(r.status()).isEqualTo("HANDLER_UNAVAILABLE"); assertThat(r.answer()).doesNotContain("secret");
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(actor.tenantId(),conversation.workId,actor.userId()))).isEmpty();
        verifyNoInteractions(afterSale,orders);
    }
    @Test void diagnosticAndOtherConversationsCannotReadOrChangeExistingHistory() {
        service.answer(actor,conversation,"你好"); select(Route.CLARIFY);
        service.decide("那个怎么办");verify(classifier).classify(new Input("那个怎么办",""));
        var other=new RoutingConversation();service.answer(actor,other,"那个怎么办");
        verify(classifier,times(2)).classify(new Input("那个怎么办",""));
        assertThat(conversation.history()).hasSize(2);service.clear(conversation);
        assertThat(conversation.history()).isEmpty();assertThat(other.history()).hasSize(2);
    }
    @Test void transcriptIsBoundedWithoutSavingClassificationJson() {
        for(int i=0;i<15;i++)service.answer(actor,conversation,"你好");
        assertThat(conversation.history()).hasSize(20);
        assertThat(conversation.routingHistory().length()).isLessThanOrEqualTo(6000);
        assertThat(conversation.routingHistory()).doesNotContain("reasonCode");
    }
    @Test void orderLookupUsesOnlyUserOrderIdsAndRechecksOwnershipEveryTime() {
        OrderService repository=mock(OrderService.class);
        when(repository.findOwnedOrder(anyLong(),anyString())).thenReturn(Optional.empty());
        var handler=new RoutingOrderHandler(new CustomerOrderTools(repository,false));
        assertThat(handler.answer(actor,"查订单",List.of(new AssistantMessage("例如 A10001"))).status()).isEqualTo("MISSING_ORDER_NO");
        verifyNoInteractions(repository);
        assertThat(handler.answer(actor,"它发货了吗",List.of(new UserMessage("A10002"))).lookup().code()).isEqualTo(OrderLookupCode.NOT_FOUND);
        handler.answer(actor,"A10002 发货了吗",List.of());
        verify(repository,times(2)).findOwnedOrder(1001L,"A10002");
        assertThat(handler.answer(actor,"它发货了吗",List.of(new UserMessage("A10001 A10002"))).status()).isEqualTo("NEED_ORDER_SELECTION");
        assertThatThrownBy(()->handler.answer(new Actor("other-tenant",1001),"A10001",List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(repository);
    }
}
