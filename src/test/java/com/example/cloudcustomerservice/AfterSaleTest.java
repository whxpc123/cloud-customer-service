package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.aftersale.*;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 规则、权限顺序与真实 Spring 工具执行器回归；模型只模拟工具决策，不访问百炼。 */
class AfterSaleTest {
    static final Instant NOW=Instant.parse("2026-08-30T02:00:00Z");
    static final Actor ACTOR=new Actor("tenant-yunshan",1001);
    static OrderFacts facts(String type,QualityVerification quality,OffsetDateTime signed,OffsetDateTime deadline){return new OrderFacts("A10001",type,signed,deadline,quality,"refund-policy","3.2");}
    static OrderFacts facts(){return facts("ORDINARY",QualityVerification.UNVERIFIED,OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),OffsetDateTime.parse("2026-08-27T23:59:59+08:00"));}
    static Document policy(String tenant,String version){return Document.builder().id("policy-1").text("质量问题需要核验，个人原因无理由退货还需核对商品完好条件。").score(.8).metadata(Map.of("tenantId",tenant,"knowledgeBase","after-sales","language","zh-CN","status","PUBLISHED","sourceId","refund-policy","sourceVersion",version)).build();}
    @Test void qualityClaimDoesNotBecomeVerifiedOrApproved(){
        assertThat(ReturnPrecheckRules.evaluate(facts(),ReturnReason.QUALITY_ISSUE,NOW).status()).isEqualTo(AssessmentStatus.NEED_QUALITY_VERIFICATION);
        for(var quality:List.of(QualityVerification.CONFIRMED,QualityVerification.REJECTED))
            assertThat(ReturnPrecheckRules.evaluate(facts("ORDINARY",quality,facts().signedAt(),facts().noReasonDeadline()),ReturnReason.QUALITY_ISSUE,NOW).status()).isEqualTo(AssessmentStatus.NEED_MANUAL_REVIEW);
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1}) void deadlineBoundary(long seconds){
        Instant limit=facts().noReasonDeadline().toInstant();
        assertThat(ReturnPrecheckRules.evaluate(facts(),ReturnReason.CHANGE_OF_MIND,limit.plusSeconds(seconds)).status())
            .isEqualTo(seconds>0?AssessmentStatus.NO_REASON_WINDOW_EXPIRED:AssessmentStatus.NEED_MANUAL_REVIEW);
    }
    @Test void missingFactsAndSpecialProductsCannotBeApproved(){
        assertThat(ReturnPrecheckRules.evaluate(facts(),ReturnReason.UNKNOWN,NOW).status()).isEqualTo(AssessmentStatus.NEED_MORE_INFORMATION);
        for(OffsetDateTime signed:new OffsetDateTime[]{null,OffsetDateTime.ofInstant(NOW.plusSeconds(1),ZoneOffset.UTC)})
            assertThat(ReturnPrecheckRules.evaluate(facts("ORDINARY",null,signed,null),ReturnReason.CHANGE_OF_MIND,NOW).status()).isEqualTo(AssessmentStatus.NEED_MORE_INFORMATION);
        assertThat(ReturnPrecheckRules.evaluate(facts("ACTIVATED_SOFTWARE",null,facts().signedAt(),null),ReturnReason.CHANGE_OF_MIND,NOW).status()).isEqualTo(AssessmentStatus.NEED_MANUAL_REVIEW);
        assertThat(ReturnPrecheckRules.evaluate(facts("ORDINARY",null,facts().signedAt(),facts().signedAt().minusSeconds(1)),ReturnReason.CHANGE_OF_MIND,NOW).status()).isEqualTo(AssessmentStatus.NEED_MANUAL_REVIEW);
    }
    @Test void unavailableOrInaccessibleOrderNeverSearchesPolicies(){
        var orders=mock(OrderFactsReader.class);var policies=mock(ApplicablePolicyRetriever.class);
        var service=new ReturnAssessmentService(orders,policies,Clock.fixed(NOW,ZoneOffset.UTC));
        when(orders.findOwned(ACTOR,"A10001")).thenReturn(Optional.empty());
        assertThat(service.assess(ACTOR,"A10001",ReturnReason.QUALITY_ISSUE).status()).isEqualTo(AssessmentStatus.NOT_ACCESSIBLE);
        verifyNoInteractions(policies);
        when(orders.findOwned(ACTOR,"A10001")).thenThrow(new IllegalStateException("PRIVATE_ORDER_ERROR"));
        var tools=new AfterSaleTools(service);var result=tools.inspect("A10001",ReturnReason.QUALITY_ISSUE,context(Set.of("A10001")));
        assertThat(result.status()).isEqualTo(AssessmentStatus.TEMPORARILY_UNAVAILABLE);assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.verifiedFacts()).isNull();assertThat(result.refundExecuted()).isFalse();verifyNoInteractions(policies);
    }
    @Test void localReaderRequiresTenantAndUserTogether(){
        var reader=new LocalOrderFactsReader();assertThat(reader.findOwned(ACTOR,"A10001")).isPresent();
        assertThat(reader.findOwned(ACTOR,"A10002")).isEmpty();
        assertThat(reader.findOwned(new Actor("other",1001),"A10001")).isEmpty();
        assertThat(reader.findOwned(new Actor("tenant-yunshan",2002),"A10001")).isEmpty();
    }
    @Test void exactVersionAndScopeFilterUseFactsButEmbeddingDoesNotContainOrderOrIdentity(){
        var store=mock(VectorStore.class);when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(policy("tenant-yunshan","3.2")));
        var result=new ApplicablePolicyRetriever(store).retrieve(ACTOR,facts(),ReturnReason.QUALITY_ISSUE);assertThat(result).hasSize(1);
        var request=org.mockito.ArgumentCaptor.forClass(SearchRequest.class);verify(store).similaritySearch(request.capture());
        assertThat(request.getValue().getQuery()).doesNotContain("A10001","1001","tenant-yunshan");
        String filter=request.getValue().getFilterExpression().toString();assertThat(filter).contains("tenantId","sourceVersion","3.2","refund-policy","PUBLISHED","zh-CN");
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(policy("other","3.2")));
        assertThatThrownBy(()->new ApplicablePolicyRetriever(store).retrieve(ACTOR,facts(),ReturnReason.QUALITY_ISSUE)).isInstanceOf(IllegalStateException.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(policy("tenant-yunshan","4.0")));
        assertThatThrownBy(()->new ApplicablePolicyRetriever(store).retrieve(ACTOR,facts(),ReturnReason.QUALITY_ISSUE)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingPolicyNeverBorrowsOtherVersionsOrApproves(){
        var orders=mock(OrderFactsReader.class);var policies=mock(ApplicablePolicyRetriever.class);
        when(orders.findOwned(ACTOR,"A10001")).thenReturn(Optional.of(facts()));when(policies.retrieve(any(),any(),any())).thenReturn(List.of());
        var result=new ReturnAssessmentService(orders,policies,Clock.fixed(NOW,ZoneOffset.UTC)).assess(ACTOR,"a10001",ReturnReason.CHANGE_OF_MIND);
        assertThat(result.status()).isEqualTo(AssessmentStatus.NO_EVIDENCE);assertThat(result.verifiedFacts()).isEqualTo(facts());assertThat(result.refundExecuted()).isFalse();
        var order=inOrder(orders,policies);order.verify(orders).findOwned(ACTOR,"A10001");order.verify(policies).retrieve(ACTOR,facts(),ReturnReason.CHANGE_OF_MIND);
    }
    @Test void toolsCannotInventOrderOrActorAndTraceDoesNotCrossRequests(){
        var service=mock(ReturnAssessmentService.class);var tools=new AfterSaleTools(service);
        assertThatThrownBy(()->tools.inspect("A10001",ReturnReason.UNKNOWN,new ToolContext(Map.of()))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->tools.inspect("A10002",ReturnReason.UNKNOWN,context(Set.of("A10001")))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(service);
        assertThat(new AfterSaleTools(service).assessments()).isEmpty();
    }
    @Test void frameworkExecutesOnlyReadToolAndReturnsActualEvidenceEveryTurn(){
        ChatModel model=mock(ChatModel.class);ChatMemory memory=MessageWindowChatMemory.builder().maxMessages(20).build();
        var orders=mock(OrderFactsReader.class);when(orders.findOwned(ACTOR,"A10001")).thenReturn(Optional.of(facts()));
        var policies=mock(ApplicablePolicyRetriever.class);when(policies.retrieve(any(),any(),any())).thenReturn(List.of(new PolicyEvidence("p","refund-policy","3.2","核验后继续审核")));
        var service=new ReturnAssessmentService(orders,policies,Clock.fixed(NOW,ZoneOffset.UTC));
        stubTool(model,"{\"orderNo\":\"A10001\",\"claimedReason\":\"QUALITY_ISSUE\"}","请先核验质量问题，没有提交申请或执行退款。");
        var chat=chat(model,memory,service);
        var first=chat.answer(ACTOR,"after-sale/test","A10001有质量问题，能退吗？");
        assertThat(first.assessments()).hasSize(1);assertThat(first.assessments().get(0).status()).isEqualTo(AssessmentStatus.NEED_QUALITY_VERIFICATION);
        chat.answer(ACTOR,"after-sale/test","它现在能退吗？");verify(orders,times(2)).findOwned(ACTOR,"A10001");
        assertThat(memory.get("after-sale/test")).hasSize(4);
    }
    @Test void ambiguousHistoryStopsBeforeModelAndNoToolResultCannotBecomeApproval(){
        ChatModel model=mock(ChatModel.class);ChatMemory memory=MessageWindowChatMemory.builder().build();var service=mock(ReturnAssessmentService.class);var chat=chat(model,memory,service);
        memory.add("ambiguous",List.of(new UserMessage("A10001 与 A10004"),new AssistantMessage("历史")));
        assertThat(chat.answer(ACTOR,"ambiguous","它能退吗？").status()).isEqualTo("NOT_CHECKED");verify(model,never()).call(any(Prompt.class));verifyNoInteractions(service);
        when(model.call(any(Prompt.class))).thenReturn(reply("已经批准退款"));
        var result=chat.answer(ACTOR,"no-tool","A10001能退吗？");assertThat(result.status()).isEqualTo("NOT_CHECKED");assertThat(result.answer()).doesNotContain("已经批准");
        assertThat(memory.get("no-tool").get(1).getText()).isEqualTo(result.answer());
    }
    @Test void unsafeExplanationIsReplacedAndNeverStoredAsApproval(){
        var model=mock(ChatModel.class);var memory=MessageWindowChatMemory.builder().build();var service=mock(ReturnAssessmentService.class);
        var assessment=new Assessment(AssessmentStatus.NEED_QUALITY_VERIFICATION,facts(),ReturnReason.QUALITY_ISSUE,"尚需质量核验",List.of(),NOW,false);
        when(service.assess(any(),anyString(),any())).thenReturn(assessment);
        stubTool(model,"{\"orderNo\":\"A10001\",\"claimedReason\":\"QUALITY_ISSUE\"}","已批准退款，可以直接退款。");
        var result=chat(model,memory,service).answer(ACTOR,"unsafe","A10001质量问题能退吗？");assertThat(result.explanationFiltered()).isTrue();assertThat(result.answer()).doesNotContain("已批准","可以直接退款");
        assertThat(memory.get("unsafe").get(1).getText()).isEqualTo(result.answer());
    }
    static AfterSaleChatService chat(ChatModel model,ChatMemory memory,ReturnAssessmentService service){return new AfterSaleChatService(ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build(),memory,service);}
    static ToolContext context(Set<String> allowed){return new ToolContext(Map.of("actor",ACTOR,"allowedOrders",allowed,"requestId","test"));}
    static ChatResponse reply(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    /** 真实 ToolCallingManager 解析 Schema、注入上下文与调用 Java 方法，模拟模型不直接调用业务类。 */
    static void stubTool(ChatModel model,String args,String answer){when(model.call(any(Prompt.class))).thenAnswer(inv->{
        Prompt p=inv.getArgument(0);var options=(ToolCallingChatOptions)p.getOptions();assertThat(options.getToolCallbacks()).hasSize(1);
        var definition=options.getToolCallbacks().get(0).getToolDefinition();assertThat(definition.name()).isEqualTo("inspectReturnRequest");
        assertThat(definition.inputSchema()).doesNotContain("tenantId","userId","actor","policyVersion","allowedOrders");
        var call=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-1","function","inspectReturnRequest",args))).build();
        var executed=ToolCallingManager.builder().build().executeToolCalls(p,new ChatResponse(List.of(new Generation(call))));
        assertThat(executed.conversationHistory().get(executed.conversationHistory().size()-1)).isInstanceOf(ToolResponseMessage.class);
        return reply(answer);
    });}
    @Test void toolCallBudgetAndModelFailurePreserveActualResults() {
        var service=mock(ReturnAssessmentService.class);var a=new Assessment(AssessmentStatus.NEED_MANUAL_REVIEW,facts(),ReturnReason.UNKNOWN,"仍需审核",List.of(),NOW,false);
        when(service.assess(any(),anyString(),any())).thenReturn(a);var tools=new AfterSaleTools(service);
        for(int i=0;i<3;i++)tools.inspect("A10001",ReturnReason.UNKNOWN,context(Set.of("A10001")));
        assertThatThrownBy(()->tools.inspect("A10001",ReturnReason.UNKNOWN,context(Set.of("A10001")))).isInstanceOf(IllegalStateException.class);
        assertThat(tools.assessments()).hasSize(3);verify(service,times(3)).assess(ACTOR,"A10001",ReturnReason.UNKNOWN);
        var model=mock(ChatModel.class);var memory=MessageWindowChatMemory.builder().build();
        when(model.call(any(Prompt.class))).thenAnswer(inv->{Prompt p=inv.getArgument(0);var call=AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("c","function","inspectReturnRequest","{\"orderNo\":\"A10001\",\"claimedReason\":\"UNKNOWN\"}"))).build();
            ToolCallingManager.builder().build().executeToolCalls(p,new ChatResponse(List.of(new Generation(call))));throw new IllegalStateException("PRIVATE_MODEL_ERROR");});
        var result=chat(model,memory,service).answer(ACTOR,"failure","A10001能退吗？");
        assertThat(result.status()).isEqualTo("EXPLANATION_UNAVAILABLE");assertThat(result.assessments()).containsExactly(a);
        assertThat(result.answer()).doesNotContain("PRIVATE_MODEL_ERROR");
    }
    @Test void toolResultDatesAreIsoAndExplanationCannotChangeFactsOrInventSteps() {
        var a=new Assessment(AssessmentStatus.NEED_QUALITY_VERIFICATION,facts(),ReturnReason.QUALITY_ISSUE,"尚需质量核验",List.of(),NOW,false);
        String result=new AfterSaleToolResultConverter().convert(a,Assessment.class);
        assertThat(result).contains("2026-08-20T10:00:00+08:00","2026-08-27T23:59:59+08:00");
        assertThat(AfterSaleExplanationGuard.shouldReplace("签收日期2026-08-19，需核验",List.of(a))).isTrue();
        assertThat(AfterSaleExplanationGuard.shouldReplace("签收时间2026-08-20 00:00:00",List.of(a))).isTrue();
        assertThat(AfterSaleExplanationGuard.shouldReplace("NOT_ACCESSIBLE",List.of(a))).isTrue();
        assertThat(AfterSaleExplanationGuard.shouldReplace("等待售后人员联系，提供照片视频并寄回",List.of(a))).isTrue();
        assertThat(AfterSaleExplanationGuard.shouldReplace("请按页面提示上传问题凭证",List.of(a))).isTrue();
        assertThat(AfterSaleExplanationGuard.shouldReplace("用户反馈质量问题尚需核验，没有执行退款。",List.of(a))).isFalse();
    }
}
