package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.ai.advisor.*;
import com.example.cloudcustomerservice.config.*;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.rag.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.*;
import org.springframework.boot.test.system.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 第十章完整同步链离线测试：真实 Audit、Memory、Modular RAG、Gate、ChatClient，只有 VectorStore 与 ChatModel 是替身。
 * 从同一次终结结果检查来源、调用次数、原始记忆和 HTTP 契约，不消耗真实百炼额度。
 */
@ExtendWith(OutputCaptureExtension.class)
class AdvisorKnowledgeAnswerTest {
    final String tenant="tenant-yunshan";
    final ChatModel model=mock(ChatModel.class);
    final ChatModel transformerModel=mock(ChatModel.class);
    final com.example.cloudcustomerservice.rag.config.CustomerModularRagConfiguration modular=new com.example.cloudcustomerservice.rag.config.CustomerModularRagConfiguration();
    final VectorStore store=mock(VectorStore.class);
    final ChatMemory memory=new ChatMemoryConfig().customerChatMemory();
    final KnowledgeFilterFactory filters=new KnowledgeFilterFactory();
    final CustomerAdvisorConfiguration advisors=new CustomerAdvisorConfiguration();
    final ChatClient client=new KnowledgeChatClientConfiguration().knowledgeConversationChatClient(model,advisors.requestAuditAdvisor(filters),
            advisors.customerMemoryAdvisor(memory),modular.customerModularRagAdvisor(modular.compression(modular.queryTransformerChatClientBuilder(transformerModel,false)), modular.customerDocumentRetriever(store),modular.customerQueryAugmenter()),advisors.evidenceRequiredAdvisor(),false);
    final AdvisorKnowledgeAnswerService service=new AdvisorKnowledgeAnswerService(client,filters,memory);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new LocalAdvisorKnowledgeController(service)).build();
    final ObjectMapper mapper=new ObjectMapper();

    /** 模型配置探测不算业务调用；默认文档属于当前租户，默认模型返回简短中文。 */
    @BeforeEach void defaults() {
        clearInvocations(model,transformerModel);
        when(transformerModel.call(any(Prompt.class))).thenReturn(reply("消费者因买错衣服申请退货时，退货运费由谁承担？"));when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("符合条件可申请退货。")));
        when(model.call(any(Prompt.class))).thenReturn(reply("如果情况经核实，按本次资料处理。"));
    }
    /** 本例的来源始终使用真实可校验 Document 字段。 */
    Document doc(String text) {return EvidenceRequiredAdvisorTest.document(text,tenant);}
    /** 构造单候选助手答复，真实 Advisor 响应侧仍会保存记忆与附带来源。 */
    ChatResponse reply(String text) {return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
    /** 生成隔离测试会话。 */
    String id() {return UUID.randomUUID().toString();}

    /** 一次响应取得文字和 Context 来源，同时只检索和生成各一次，不向 Prompt 注入审计/身份字段。 */
    @Test void oneTerminalCallReturnsActualSourcesAndFilter(CapturedOutput output) {
        var document=doc("正文含 {query}、引号及\n例外条件。");when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        var result=service.answer(tenant,"c-1",1001L,"完整问题 {question_answer_context}");
        assertThat(result.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        assertThat(result.references()).hasSize(1);assertThat(result.references().get(0).content()).isEqualTo(document.getText());
        assertThat(result.references().get(0).documentId()).isEqualTo(document.getId());
        assertThat(result.references().get(0).sourceName()).isEqualTo("售后制度");assertThat(result.references().get(0).sourceVersion()).isEqualTo("3.2");
        var query=ArgumentCaptor.forClass(SearchRequest.class);verify(store).similaritySearch(query.capture());
        assertThat(query.getValue().getQuery()).isEqualTo(result.retrievalQuery());assertThat(query.getValue().getTopK()).isEqualTo(5);
        assertThat(query.getValue().getSimilarityThreshold()).isEqualTo(.60);
        assertThat(query.getValue().getFilterExpression().toString()).contains("tenant-yunshan","PUBLISHED","after-sales","zh-CN");
        var prompt=ArgumentCaptor.forClass(Prompt.class);verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).contains(document.getText(),"完整问题 {question_answer_context}")
                .doesNotContain(result.requestId(),"knowledge/tenant-yunshan","customer.userId");
        assertThat(prompt.getValue().getOptions() instanceof ToolCallingChatOptions options&&!options.getToolCallbacks().isEmpty()).isFalse();
        assertThat(output.getAll()).contains("requestId="+result.requestId(),"hasResponse=true").doesNotContain("完整问题","正文含");
    }

    /** 第二轮先补全检索问题；最终模型和记忆仍保留用户原文，不把证据或转换结果写回历史。 */
    @Test void memoryPrecedesCompressionWithoutStoringTransformedQueryOrEvidence() {
        String id=id();service.answer(tenant,id,1001L,"我买错衣服，想退货。");
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("个人原因退货运费由消费者承担。")));
        service.answer(tenant,id,1001L,"那运费呢？");
        var prompts=ArgumentCaptor.forClass(Prompt.class);verify(model,times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions()).extracting(Message::getText).contains("我买错衣服，想退货。");
        assertThat(prompts.getAllValues().get(1).getContents()).contains("那运费呢？","个人原因退货运费由消费者承担。");
        var searches=ArgumentCaptor.forClass(SearchRequest.class);verify(store,times(2)).similaritySearch(searches.capture());
        assertThat(searches.getAllValues()).extracting(SearchRequest::getQuery).containsExactly("我买错衣服，想退货。","消费者因买错衣服申请退货时，退货运费由谁承担？");
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(tenant,id,1001L))).extracting(Message::getText)
                .containsExactly("我买错衣服，想退货。","如果情况经核实，按本次资料处理。","那运费呢？","如果情况经核实，按本次资料处理。");
    }

    /** 无命中由 Gate 阻断模型；Memory.before 已保存原问题，这一点必须如实记录而不是假装回滚。 */
    @Test void noEvidenceSkipsGenerationButLeavesOriginalUserMessage() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var result=service.answer(tenant,"none",null,"咖啡喜好？");
        assertThat(result.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);assertThat(result.references()).isEmpty();verifyNoInteractions(model);
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(tenant,"none",null))).extracting(Message::getText).containsExactly("咖啡喜好？");
    }

    /** 第九章之后继续防止空文档当作证据；任意跨租户返回必须在模型前阻断。 */
    @Test void blankAndForeignEvidenceNeverReachModel() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc(" ")));
        assertThat(service.answer(tenant,id(),"q").status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(EvidenceRequiredAdvisorTest.document("FOREIGN_SECRET","other")));
        var result=service.answer(tenant,id(),"q");assertThat(result.status()).isEqualTo(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(result.references()).isEmpty();verifyNoInteractions(model);
    }

    /** 同一外部会话 ID 在不同用户、访客、租户和普通客服之间均不共享，清空也按相同范围执行。 */
    @Test void identitiesTenantsAndLegacyChatHaveSeparateMemory() {
        String id=id();String user1=AdvisorKnowledgeAnswerService.memoryId(tenant,id,1001L);
        memory.add(user1,new UserMessage("USER1_SECRET"));
        memory.add(id,new UserMessage("LEGACY_SECRET"));
        memory.add(AdvisorKnowledgeAnswerService.memoryId("other",id,2002L),new UserMessage("TENANT_SECRET"));
        service.answer(tenant,id,2002L,"q");service.answer(tenant,id,null,"visitor");
        var prompts=ArgumentCaptor.forClass(Prompt.class);verify(model,times(2)).call(prompts.capture());
        prompts.getAllValues().forEach(p->assertThat(p.getContents()).doesNotContain("USER1_SECRET","LEGACY_SECRET","TENANT_SECRET"));
        service.clearMemory(tenant,id,2002L);
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(tenant,id,2002L))).isEmpty();
        assertThat(memory.get(user1)).hasSize(1);assertThat(memory.get(id)).hasSize(1);
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(tenant,id,null))).hasSize(2);
        assertThat(memory.get(ChatMemory.DEFAULT_CONVERSATION_ID)).isEmpty();
    }

    /** 清空后下一轮不携带旧问答，且没有伪造“已经清空”的助手消息进入窗口。 */
    @Test void clearRemovesKnowledgeContextForNextTurn() {
        service.answer(tenant,"clear",1001L,"FIRST_SECRET");service.clearMemory(tenant,"clear",1001L);
        service.answer(tenant,"clear",1001L,"新问题");
        var prompts=ArgumentCaptor.forClass(Prompt.class);verify(model,times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getContents()).doesNotContain("FIRST_SECRET");
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(tenant,"clear",1001L))).hasSize(2);
    }

    /** 检索故障、生成故障和空白回答分别降级，不能返回部分来源或底层异常正文。 */
    @Test void failuresAndEmptyAnswersReturnStableUnavailable(CapturedOutput output) {
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("PRIVATE_STORE"));
        assertThat(service.answer(tenant,id(),"q").status()).isEqualTo(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE);verifyNoInteractions(model);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("规则")));
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("PRIVATE_MODEL"));
        var failed=service.answer(tenant,id(),"q");assertThat(failed.references()).isEmpty();assertThat(failed.answer()).doesNotContain("PRIVATE");
        when(model.call(any(Prompt.class))).thenReturn(reply(" "));
        assertThat(service.answer(tenant,id(),"q").status()).isEqualTo(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(output.getAll()).doesNotContain("PRIVATE_STORE","PRIVATE_MODEL");
    }

    /** 有检索块但模型主动拒答仍为生成成功，不能把 ANSWERED 展示成已验证正确。 */
    @Test void generatedRefusalRemainsAnswered() {
        when(model.call(any(Prompt.class))).thenReturn(reply("当前知识库中没有找到足够依据。"));
        assertThat(service.answer(tenant,id(),"q").status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
    }

    /** 空、超长和非法标识在调用链之前失败，收费依赖没有交互。 */
    @Test void invalidBusinessInputNeverCallsDependencies() {
        for(String q:Arrays.asList(null," ","字".repeat(2001)))assertThatIllegalArgumentException().isThrownBy(()->service.answer(tenant,"c",q));
        for(String id:Arrays.asList(null,"","a/b","字","x".repeat(101)))assertThatIllegalArgumentException().isThrownBy(()->service.answer(tenant,id,"q"));
        assertThatIllegalArgumentException().isThrownBy(()->service.answer("t' OR true","c","q"));
        assertThatIllegalArgumentException().isThrownBy(()->service.answer(tenant,"c",-1L,"q"));
        verifyNoInteractions(store,model);
    }

    /** 检查配置严格递增，并故意让 Gate 早于 RAG：反例必须跳过检索和模型，说明顺序具有因果关系。 */
    @Test void explicitOrdersAndWrongOrderCounterexample() {
        assertThat(CustomerAdvisorOrders.AUDIT).isLessThan(CustomerAdvisorOrders.MEMORY);
        assertThat(advisors.customerMemoryAdvisor(memory).getOrder()).isEqualTo(CustomerAdvisorOrders.MEMORY);
        assertThat(modular.customerModularRagAdvisor(modular.compression(modular.queryTransformerChatClientBuilder(transformerModel,false)), modular.customerDocumentRetriever(store),modular.customerQueryAugmenter()).getOrder()).isEqualTo(CustomerAdvisorOrders.RAG);
        assertThat(CustomerAdvisorOrders.MEMORY).isLessThan(CustomerAdvisorOrders.RAG);
        assertThat(CustomerAdvisorOrders.RAG).isLessThan(new EvidenceRequiredAdvisor().getOrder());
        var wrongRag=RetrievalAugmentationAdvisor.builder().documentRetriever(modular.customerDocumentRetriever(store)).taskExecutor(new org.springframework.core.task.SyncTaskExecutor()).order(CustomerAdvisorOrders.EVIDENCE_GATE+1).build();
        var wrongClient=ChatClient.builder(model).defaultAdvisors(wrongRag,new EvidenceRequiredAdvisor()).build();
        var wrongService=new AdvisorKnowledgeAnswerService(wrongClient,filters,memory);clearInvocations(model);
        assertThat(wrongService.answer(tenant,id(),"q").status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);verifyNoInteractions(store);verify(model,never()).call(any(Prompt.class));
    }

    /** 直接漏传会话 ID 的 ChatClient 请求必须在 Audit 阻断，验证不会落入框架默认共享窗口。 */
    @Test void missingConversationCannotUseFrameworkDefaultMemory() {
        assertThatThrownBy(()->client.prompt().user("q").advisors(a->a.param(CustomerAdvisorContextKeys.REQUEST_ID,"r-1")
                .param(CustomerAdvisorContextKeys.TENANT_ID,tenant).param(VectorStoreDocumentRetriever.FILTER_EXPRESSION,filters.publishedAfterSales(tenant)))
                .call().chatClientResponse()).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store,model);assertThat(memory.get(ChatMemory.DEFAULT_CONVERSATION_ID)).isEmpty();
    }

    /** 两个租户的请求同时穿过共享 Advisor，验证局部 Context、过滤和历史都不互相覆盖。 */
    @Test void concurrentTenantsKeepIndependentContext() throws Exception {
        var barrier=new CyclicBarrier(2);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(inv->{
            SearchRequest q=inv.getArgument(0);barrier.await(5,TimeUnit.SECONDS);
            String t=q.getFilterExpression().toString().contains("tenant-a")?"tenant-a":"tenant-b";
            return List.of(EvidenceRequiredAdvisorTest.document("EVIDENCE_"+t,t));
        });
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->service.answer("tenant-a","same",1001L,"QUESTION_A"));
            var b=executor.submit(()->service.answer("tenant-b","same",1001L,"QUESTION_B"));
            var ra=a.get(10,TimeUnit.SECONDS);var rb=b.get(10,TimeUnit.SECONDS);
            assertThat(ra.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);assertThat(rb.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
            assertThat(ra.references().get(0).content()).isEqualTo("EVIDENCE_tenant-a");assertThat(rb.references().get(0).content()).isEqualTo("EVIDENCE_tenant-b");
            assertThat(ra.requestId()).isNotEqualTo(rb.requestId());
            assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId("tenant-a","same",1001L))).extracting(Message::getText).contains("QUESTION_A").doesNotContain("QUESTION_B");
        } finally {executor.shutdownNow();}
    }

    /** 走 HTTP 校验新建、发送、清空和服务端租户，客户端声称的租户不得进入过滤器。 */
    @Test void httpContractFixesTenantAndSupportsCreateSendClear() throws Exception {
        var created=mvc.perform(post("/internal/advisor-rag/conversations")).andExpect(status().isCreated()).andReturn();
        String id=mapper.readTree(created.getResponse().getContentAsString()).get("conversationId").asText();UUID.fromString(id);verifyNoInteractions(store,model);
        mvc.perform(post("/internal/advisor-rag/conversations/{id}/messages",id).header("X-Demo-User-Id",1001).header("X-Tenant-Id","other")
                .contentType("application/json").content("{\"question\":\"退货\",\"tenantId\":\"other\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED")).andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.references[0].sourceVersion").value("3.2")).andExpect(jsonPath("$.requestId").isNotEmpty());
        var captured=ArgumentCaptor.forClass(SearchRequest.class);verify(store).similaritySearch(captured.capture());
        assertThat(captured.getValue().getFilterExpression().toString()).contains(tenant).doesNotContain("other");
        mvc.perform(delete("/internal/advisor-rag/conversations/{id}/memory",id).header("X-Demo-User-Id",1001)).andExpect(status().isNoContent());
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(tenant,id,1001L))).isEmpty();
    }

    /** 页面 GET 不调用依赖；错误 JSON、空问题、非法 ID 与身份应返回 400。 */
    @Test void pageAndInvalidHttpRequestsDoNotCallModels() throws Exception {
        mvc.perform(get("/internal/advisor-rag")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ADVISOR CHAIN")));
        for(String body:List.of("{}","null","{bad","{\"question\":\" \"}"))mvc.perform(post("/internal/advisor-rag/conversations/c/messages").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(String user:List.of("0","-1","not-number"))mvc.perform(post("/internal/advisor-rag/conversations/c/messages").header("X-Demo-User-Id",user)
                .contentType("application/json").content("{\"question\":\"q\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/advisor-rag/conversations/bad id/messages").contentType("application/json").content("{\"question\":\"q\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(store,model);
    }
}
