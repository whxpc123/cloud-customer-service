package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.ai.advisor.*;
import com.example.cloudcustomerservice.config.*;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.rag.*;
import com.example.cloudcustomerservice.rag.config.*;
import com.example.cloudcustomerservice.rag.expansion.*;
import com.example.cloudcustomerservice.rag.query.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.*;
import org.springframework.boot.test.system.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 第十二章真实 Spring AI 扩展/合并/Advisor 集成，只有收费模型与底层检索使用确定性替身。 */
@ExtendWith(OutputCaptureExtension.class)
class QueryExpansionTest {
    private static final String TENANT = "tenant-yunshan";
    private static final String QUESTION = "商品签收十天后发现质量问题，还能不能退、运费谁承担、退款多久提交？";
    private static final List<String> VARIANTS = List.of("签收十天发现质量问题，能申请退货吗？",
            "签收十天发现质量问题，退货运费由谁承担？", "签收十天发现质量问题，退货退款多久提交？");
    private final ChatModel compressionModel = mock(ChatModel.class), expansionModel = mock(ChatModel.class), answerModel = mock(ChatModel.class);
    private final VectorStore store = mock(VectorStore.class);
    private final ChatMemory memory = new ChatMemoryConfig().customerChatMemory();
    private final KnowledgeFilterFactory filters = new KnowledgeFilterFactory();
    private final CustomerModularRagConfiguration modular = new CustomerModularRagConfiguration();
    private final QueryExpansionConfiguration expansionConfig = new QueryExpansionConfiguration();
    private final SafeQueryTransformer compression = modular.compression(modular.queryTransformerChatClientBuilder(compressionModel,false));
    private final GuardedQueryExpander expander = expansionConfig.guardedQueryExpander(expansionConfig.builder(expansionModel,false));
    private final MultiQueryRetrieval retrieval = new MultiQueryRetrieval(store, new ConcatenationDocumentJoiner());
    private final CustomerAdvisorConfiguration advisors = new CustomerAdvisorConfiguration();
    private final ChatClient client = new KnowledgeChatClientConfiguration().knowledgeConversationChatClient(answerModel,
            advisors.requestAuditAdvisor(filters), advisors.customerMemoryAdvisor(memory),
            modular.customerModularRagAdvisor(compression,expander,retrieval,modular.customerQueryAugmenter(), RerankTestSupport.ranker(), RerankTestSupport.budget()),advisors.evidenceRequiredAdvisor(),false);
    private final AdvisorKnowledgeAnswerService answers = new AdvisorKnowledgeAnswerService(client,filters,memory);
    private final LocalQueryExpansionService lab = new LocalQueryExpansionService(memory,compression,expander,retrieval,filters);

    /** 配置探测不计为模型调用；所有默认候选都属于已发布中文售后范围。 */
    @BeforeEach void defaults() {
        clearInvocations(compressionModel,expansionModel,answerModel);
        when(expansionModel.call(any(Prompt.class))).thenReturn(reply(String.join("\n",VARIANTS)));
        when(answerModel.call(any(Prompt.class))).thenReturn(reply("1. 按质量售后资料核对。2. 核对运费依据。3. 没有退款提交时间依据。"));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a", .75, "质量问题按质量售后流程核对。")));
    }
    /** 模拟一个供应商助手消息，仍由真正 MultiQueryExpander 按行解析。 */
    private ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    /** 构造可通过权限门的知识块，ID 和 score 可显式控制。 */
    private Document doc(String id, double score, String text) {
        return Document.builder().id(id).text(text).score(score).metadata(Map.of("tenantId",TENANT,"status","PUBLISHED",
                "knowledgeBase","after-sales","language","zh-CN","sourceId","policy-"+id,"sourceVersion","1.0","chunkIndex",1)).build();
    }
    /** 仅给测试直接调用构造与正式服务一致的可信 Context。 */
    private Query query(String text, int count, boolean include) {
        return Query.builder().text(text).context(Map.of(QueryExpansionTrace.KEY,new QueryExpansionTrace(text,ExpansionMode.ON,count,include),
                QueryTransformationTrace.KEY,new QueryTransformationTrace(text),CustomerAdvisorContextKeys.TENANT_ID,TENANT,
                VectorStoreDocumentRetriever.FILTER_EXPRESSION,filters.publishedAfterSales(TENANT))).build();
    }

    /** 四路原始候选合并去重，第一次命中的 score 保留；最终模型只调用一次且收到原问题。 */
    @Test void realAdvisorExpandsRetrievesJoinsAndAnswersOnce(CapturedOutput logs) {
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(inv->{
            String q=((SearchRequest)inv.getArgument(0)).getQuery();
            if(q.equals(QUESTION))return List.of(doc("a",.70,"资料 A"),doc("b",.80,"资料 B"));
            if(q.equals(VARIANTS.get(0)))return List.of(doc("a",.99,"资料 A"),doc("c",.77,"资料 C"));
            if(q.equals(VARIANTS.get(1)))return List.of(doc("b",.98,"资料 B"),doc("d",.90,"资料 D"));
            return List.of(doc("c",.97,"资料 C"),doc("e",.65,"资料 E"));
        });
        var r=answers.answer(TENANT,"multi",1001L,QUESTION);
        assertThat(r.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        assertThat(r.retrievalQuery()).isEqualTo(QUESTION);
        var e=r.expansion();
        assertThat(e.queries()).containsExactly(QUESTION,VARIANTS.get(0),VARIANTS.get(1),VARIANTS.get(2));
        assertThat(e.status()).isEqualTo("EXPANDED");
        assertThat(e.rawDocumentCount()).isEqualTo(8);assertThat(e.joinedDocumentCount()).isEqualTo(5);
        assertThat(e.duplicateDocumentCount()).isEqualTo(3);
        assertThat(r.references()).extracting(KnowledgeReference::documentId).containsExactly("d","b","c","a","e");
        assertThat(r.references().get(3).score()).isEqualTo(.70);
        assertThat(e.contextCharacters()).isEqualTo(String.join("\n\n",r.references().stream().map(KnowledgeReference::content).toList()).length());
        var requests=ArgumentCaptor.forClass(SearchRequest.class);verify(store,times(4)).similaritySearch(requests.capture());
        requests.getAllValues().forEach(q->{assertThat(q.getTopK()).isEqualTo(6);assertThat(q.getSimilarityThreshold()).isEqualTo(.45);
            assertThat(q.getFilterExpression().toString()).contains(TENANT,"PUBLISHED","after-sales","zh-CN");});
        var prompt=ArgumentCaptor.forClass(Prompt.class);verify(answerModel).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).contains(QUESTION,"资料 A","资料 E").doesNotContain(VARIANTS.get(0));
        assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(TENANT,"multi",1001L))).extracting(Message::getText).contains(QUESTION).doesNotContain(VARIANTS.get(0));
        verify(expansionModel).call(any(Prompt.class));verifyNoInteractions(compressionModel);
        assertThat(logs.getAll()).contains("[QUERY EXPANSION]","raw=8","joined=5").doesNotContain(QUESTION,"资料 A");
    }

    /** 单主题默认不付扩展成本，显式 OFF 即使是三问题也只检索一次 Top 5。 */
    @Test void autoSimpleAndExplicitOffUseOnlySingleSearch() {
        var simple=answers.answer(TENANT,"simple","退货运费谁承担？");
        var off=answers.answer(TENANT,"off",null,QUESTION,ExpansionMode.OFF);
        assertThat(simple.expansion().status()).isEqualTo("SKIPPED_SIMPLE");
        assertThat(off.expansion().status()).isEqualTo("DISABLED");
        verifyNoInteractions(expansionModel,compressionModel);
        var requests=ArgumentCaptor.forClass(SearchRequest.class);verify(store,times(2)).similaritySearch(requests.capture());
        assertThat(requests.getAllValues()).extracting(SearchRequest::getTopK).containsOnly(6);
    }

    /** 用户明确打开多路可比较单问题，AUTO 不需要为此加入隐藏分类模型。 */
    @Test void explicitOnCanExpandASimpleQuestion() {
        when(expansionModel.call(any(Prompt.class))).thenReturn(reply("退货运费由谁承担？\n退货邮费如何处理？\n退货产生的运输费用由谁支付？"));
        var r=answers.answer(TENANT,"on",null,"退货运费谁承担？",ExpansionMode.ON);
        assertThat(r.expansion().queries()).hasSize(4);verify(store,times(4)).similaritySearch(any(SearchRequest.class));
    }

    /** 无历史含糊追问在扩展和检索之前停止，也不伪造空命中指标。 */
    @Test void clarificationSkipsExpansionAndSearch() {
        var r=answers.answer(TENANT,"unclear",null,"那运费呢？",ExpansionMode.ON);
        assertThat(r.status()).isEqualTo(KnowledgeAnswerStatus.NEEDS_CLARIFICATION);
        assertThat(r.expansion().status()).isEqualTo("SKIPPED_CLARIFICATION");
        assertThat(r.expansion().queries()).isEmpty();assertThat(r.expansion().retrievals()).isEmpty();
        verifyNoInteractions(expansionModel,answerModel,store);
    }

    /** 官方行数解析失败和服务商异常都回退完整 Query；单路可继续提供已有依据。 */
    @Test void malformedOrFailedExpansionFallsBackWithoutRetry() {
        when(expansionModel.call(any(Prompt.class))).thenReturn(reply("只有一条错误输出"));
        var malformed=answers.answer(TENANT,"bad",null,QUESTION,ExpansionMode.ON);
        assertThat(malformed.expansion().status()).isEqualTo("FALLBACK_INVALID");
        assertThat(malformed.expansion().queries()).containsExactly(QUESTION);
        assertThat(malformed.expansion().perQueryTopK()).isEqualTo(6);
        when(expansionModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("PRIVATE_ERROR"));
        assertThat(answers.answer(TENANT,"err",null,QUESTION,ExpansionMode.ON).expansion().status()).isEqualTo("FALLBACK_ERROR");
        verify(expansionModel,times(2)).call(any(Prompt.class));verify(store,times(2)).similaritySearch(any(SearchRequest.class));
    }

    /** 重复 Query 先归一化，避免 RAA 收集 Map 时重复 Key 导致整轮失败。 */
    @Test void duplicateQueriesDoNotCauseDuplicateMapKeys() {
        when(expansionModel.call(any(Prompt.class))).thenReturn(reply(VARIANTS.get(0)+"\n1. "+VARIANTS.get(0)+"\n"+VARIANTS.get(0)));
        var r=answers.answer(TENANT,"dupe",null,QUESTION,ExpansionMode.ON);
        assertThat(r.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);
        assertThat(r.expansion().queries()).containsExactly(QUESTION,VARIANTS.get(0));
        assertThat(r.expansion().rejectedVariants()).isEqualTo(2);verify(store,times(2)).similaritySearch(any(SearchRequest.class));
    }

    /** 文本相似甚至相同，但不同 ID，官方 Joiner 都保留；不伪称语义去重。 */
    @Test void differentIdsKeepSemanticallySimilarDocuments() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a",.8,"质量问题退货运费平台承担。"),doc("b",.7,"质量问题退货运费平台承担。")));
        var r=answers.answer(TENANT,"near",QUESTION);
        assertThat(r.expansion().rawDocumentCount()).isEqualTo(8);
        assertThat(r.expansion().joinedDocumentCount()).isEqualTo(2);
        assertThat(r.references()).extracting(KnowledgeReference::documentId).containsExactly("a","b");
    }

    /** 任一路出错即停止后续收费调用，最终模型不能拿前面几路的半组资料生成完整回答。 */
    @Test void retrievalFailureStopsLaterRoutesAndFinalGeneration() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a",.8,"资料"))).thenThrow(new IllegalStateException("DB_PRIVATE"));
        var r=answers.answer(TENANT,"failure",QUESTION);
        assertThat(r.status()).isEqualTo(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(r.expansion().retrievalStatus()).isEqualTo("FAILED");
        assertThat(r.expansion().retrievals()).hasSize(2);assertThat(r.expansion().joinedDocuments()).isEmpty();
        verify(store,times(2)).similaritySearch(any(SearchRequest.class));verifyNoInteractions(answerModel);
    }

    /** 去重前也验证范围，不能靠同一个 ID 的合法首条把外租户资料隐藏掉。 */
    @Test void foreignDuplicateCannotHideBehindValidDocumentId() {
        Document foreign=doc("a",.9,"FOREIGN_PRIVATE");foreign.getMetadata().put("tenantId","other");
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("a",.8,"本租户资料"))).thenReturn(List.of(foreign));
        var r=answers.answer(TENANT,"foreign",QUESTION);
        assertThat(r.status()).isEqualTo(KnowledgeAnswerStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(r.expansion().retrievals().get(1).documents()).isEmpty();verifyNoInteractions(answerModel);
    }

    /** 多路全空仍由 Java 门硬拒答，扩展已经发生不能宣称完全没有模型调用。 */
    @Test void allEmptyEvidenceBlocksOnlyFinalGeneration() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var r=answers.answer(TENANT,"empty",QUESTION);
        assertThat(r.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);
        assertThat(r.expansion().retrievals()).hasSize(4);assertThat(r.expansion().joinedDocumentCount()).isZero();
        verify(expansionModel).call(any(Prompt.class));verifyNoInteractions(answerModel);
    }

    /** 数字、关键条件、否定词、提交/到账边界和提前回答的确定性回归，不依赖模型固定措辞。 */
    @Test void invalidVariantsAreRejected() {
        for(String invalid:List.of("签收七天发现质量问题，能退吗？","签收十天，能退吗？","订单 A10001 签收十天发现质量问题能退吗？",
                "签收十天发现质量问题，退款申请应在多长时间内提交？",
                "签收十天发现质量问题，退款多久到账？","签收十天发现质量问题，退款立即提交。"))
            assertThat(ExpansionQueryGuard.valid(QUESTION,invalid)).as(invalid).isFalse();
        assertThat(ExpansionQueryGuard.valid("软件还没有激活，能不能退、邮费谁承担？","已激活的软件能退吗？")).isFalse();
        assertThat(ExpansionQueryGuard.valid("软件还没有激活，能不能退、邮费谁承担？","软件退货邮费谁承担？")).isFalse();
        assertThat(ExpansionQueryGuard.valid("软件还没有激活，能不能退、邮费谁承担？","尚未激活的软件退货邮费由谁承担？")).isTrue();
        assertThat(ExpansionQueryGuard.valid(QUESTION,VARIANTS.get(0))).isTrue();
        assertThat(ExpansionQueryGuard.valid(QUESTION,"商品签收十天后发现质量问题，商家应在多久提交退款？")).isTrue();
        assertThat(ExpansionQueryGuard.valid(QUESTION,"商品签收十天后发现质量问题，商家应在多长时间内处理退款申请？")).isTrue();
    }

    /** 只采纳文本，恶意 delegate 构造的新过滤器/历史不会进入检索。 */
    @Test void delegatedContextAndExcessVariantsCannotOverrideServerScope() {
        Query input=query(QUESTION,1,true);
        GuardedQueryExpander malicious=new GuardedQueryExpander((n,i)->q->List.of(
                new Query(VARIANTS.get(0),List.of(new UserMessage("foreign history")),Map.of("filter","evil")),new Query(VARIANTS.get(1))));
        var output=malicious.expand(input);
        assertThat(output).hasSize(2);
        for(Query q:output){assertThat(q.context()).isEqualTo(input.context());assertThat(q.history()).isEqualTo(input.history());}
    }

    /** 1/3/5 参数使用官方配置，includeOriginal=false 确实不额外检索完整问题；原历史不被实验写入。 */
    @Test void laboratoryHonorsCountsOriginalSwitchAndNoWrite() {
        for(int count:List.of(1,3,5)) {
            List<String> variants=new ArrayList<>();
            for(int i=0;i<count;i++)variants.add(VARIANTS.get(0).replace("能申请",List.of("能申请","能办理","是否能申请","能否申请","可以申请").get(i)));
            when(expansionModel.call(any(Prompt.class))).thenReturn(reply(String.join("\n",variants)));
            var result=lab.expand(TENANT,"lab-"+count,1001L,new QueryExpansionRequest(QUESTION,count,false,false));
            assertThat(result.expansion().queries()).hasSize(count).doesNotContain(QUESTION);
            assertThat(result.expansion().originalIncluded()).isFalse();
            assertThat(result.expansion().retrievalStatus()).isEqualTo("NOT_REQUESTED");
            assertThat(memory.get(AdvisorKnowledgeAnswerService.memoryId(TENANT,"lab-"+count,1001L))).isEmpty();
        }
        verifyNoInteractions(answerModel,store);
        var prompts=ArgumentCaptor.forClass(Prompt.class);verify(expansionModel,times(3)).call(prompts.capture());
        prompts.getAllValues().forEach(p->{assertThat(p.getOptions().getTemperature()).isEqualTo(.2);assertThat(p.getContents()).doesNotContain(TENANT,"PUBLISHED","knowledge/");});
    }

    /** 单路基线和多路结果来自真实不同查询调用，分别计时，不把单路结果复制成“多路”。 */
    @Test void laboratoryCompareExecutesBaselinePlusFourRoutes() {
        var r=lab.expand(TENANT,"compare",1001L,new QueryExpansionRequest(QUESTION,null,null,true));
        assertThat(r.comparisonStatus()).isEqualTo("COMPLETED");assertThat(r.baseline().topK()).isEqualTo(5);
        assertThat(r.expansion().retrievals()).hasSize(4);assertThat(r.expansion().joinedDocumentCount()).isEqualTo(1);
        var captor=ArgumentCaptor.forClass(SearchRequest.class);verify(store,times(5)).similaritySearch(captor.capture());
        assertThat(captor.getAllValues()).extracting(SearchRequest::getTopK).containsExactly(5,3,3,3,3);
        verifyNoInteractions(answerModel);
    }

    /** 基线失败必须显式显示失败，不能误报“原检索零命中、扩展提升召回”。 */
    @Test void failedBaselineIsNotReportedAsRecallGain() {
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("fail")).thenReturn(List.of(doc("a",.8,"资料")));
        var r=lab.expand(TENANT,"bad-baseline",1001L,new QueryExpansionRequest(QUESTION,3,true,true));
        assertThat(r.comparisonStatus()).isEqualTo("FAILED");assertThat(r.baseline().status()).isEqualTo("FAILED");
        assertThat(r.expansion().retrievalStatus()).isEqualTo("COMPLETED");
    }

    /** 实验仅看当前身份的会话；历史在补全阶段使用，不直接传给扩展模型。 */
    @Test void compressionPrecedesExpansionAndHistoryIsScoped() {
        var key=AdvisorKnowledgeAnswerService.memoryId(TENANT,"scoped",1001L);
        memory.add(key,List.of(new UserMessage("我签收十天发现质量问题。"),new AssistantMessage("请说明诉求。")));
        var before=List.copyOf(memory.get(key));
        when(compressionModel.call(any(Prompt.class))).thenReturn(reply(QUESTION));
        var r=lab.expand(TENANT,"scoped",1001L,new QueryExpansionRequest("那能退吗，运费和退款时间呢？",3,true,false));
        assertThat(r.expansion().transformedQuery()).isEqualTo(QUESTION);assertThat(memory.get(key)).isEqualTo(before);
        var prompt=ArgumentCaptor.forClass(Prompt.class);verify(expansionModel).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).contains(QUESTION).doesNotContain("请说明诉求。");
        var other=lab.expand(TENANT,"scoped",2002L,new QueryExpansionRequest("那运费呢？",3,true,true));
        assertThat(other.comparisonStatus()).isEqualTo("SKIPPED_CLARIFICATION");
        assertThat(other.transformation().historyMessageCount()).isZero();
    }

    /** 非法数量和模式在 HTTP 边界拒绝；不会把实验开放为无限扩展请求。 */
    @Test void httpValidationRejectsUnboundedAndUnknownOptions() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new LocalQueryExpansionController(lab),new LocalAdvisorKnowledgeController(answers)).build();
        mvc.perform(get("/internal/query-expansion")).andExpect(status().isOk());
        for(String body:List.of("{}","{\"question\":\"问题\",\"numberOfQueries\":0}","{\"question\":\"问题\",\"numberOfQueries\":6}","{bad"))
            mvc.perform(post("/internal/query-expansion/c/expand").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/advisor-rag/conversations/c/messages").contentType("application/json").content("{\"question\":\"问题\",\"expansionMode\":\"ALL\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(expansionModel,compressionModel,store,answerModel);
    }
}
