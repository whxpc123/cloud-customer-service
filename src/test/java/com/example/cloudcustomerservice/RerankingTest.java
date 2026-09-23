package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.ai.advisor.*;
import com.example.cloudcustomerservice.config.*;
import com.example.cloudcustomerservice.knowledge.*;
import com.example.cloudcustomerservice.rag.*;
import com.example.cloudcustomerservice.rag.config.*;
import com.example.cloudcustomerservice.rag.expansion.*;
import com.example.cloudcustomerservice.rag.rerank.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.tokenizer.*;
import org.springframework.ai.vectorstore.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 重排/预算/官方 HTTP 协议离线测试；不访问百炼、不写用户知识库。 */
class RerankingTest {
    private static final String TENANT="tenant-yunshan";
    private final RerankGateway gateway=mock(RerankGateway.class);
    private final TokenCountEstimator estimator=mock(TokenCountEstimator.class);
    private final QwenRerankDocumentPostProcessor ranker=new QwenRerankDocumentPostProcessor(gateway,estimator);
    private final ContextBudgetDocumentPostProcessor budget=new ContextBudgetDocumentPostProcessor(estimator);
    @BeforeEach void estimateByLength(){when(estimator.estimate(anyString())).thenAnswer(i->((String)i.getArgument(0)).length());}
    private Document doc(String id,double score,String text){return Document.builder().id(id).score(score).text(text).metadata(Map.of(
        "tenantId",TENANT,"status","PUBLISHED","knowledgeBase","after-sales","language","zh-CN","sourceId","source-"+id,"sourceVersion","1.0","chunkIndex",1)).build();}
    private Query query(boolean enabled,int topN,int tokens){return Query.builder().text("退货后优惠券是否返还？").context(Map.of(
        CustomerAdvisorContextKeys.TENANT_ID,TENANT,RerankTrace.KEY,new RerankTrace(new RerankOptions(enabled,6,topN,tokens)))).build();}
    private RerankTrace trace(Query q){return (RerankTrace)q.context().get(RerankTrace.KEY);}
    private RerankGateway.Result scores(RerankGateway.Score...s){return new RerankGateway.Result(List.of(s),123);}

    /** 低向量分数但直接回答的券规则升到首位，保留原分数、ID、全部元数据和原对象。 */
    @Test void reordersPreservesIdentityAndDoesNotMutateOriginal(){
        var docs=List.of(doc("noise",.9,"包邮规则"),doc("coupon",.51,"退款成功后券退回账户，过期券不返还。"));
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(scores(new RerankGateway.Score(1,.95),new RerankGateway.Score(0,.2)));
        Query q=query(true,2,5000);var result=ranker.process(q,docs);
        assertThat(result).extracting(Document::getId).containsExactly("coupon","noise");
        assertThat(result.get(0).getMetadata()).containsEntry("retrievalScore",.51).containsEntry("rerankScore",.95)
            .containsEntry("rerankRank",1).containsEntry("rerankModel","qwen3-rerank").containsEntry("rerankFallback",false).containsEntry("sourceId","source-coupon");
        assertThat(docs.get(1).getScore()).isEqualTo(.51);assertThat(docs.get(1).getMetadata()).doesNotContainKey("rerankScore");
        assertThat(trace(q).snapshot().totalTokens()).isEqualTo(123);
    }
    /** RAA 把原追问交给后处理器，必须用完整转换问题进行一次全局重排。 */
    @Test void usesFullTransformedQueryRatherThanShortFollowup(){
        Query q=query(true,2,5000);var context=new HashMap<>(q.context());
        var e=new QueryExpansionTrace("那券呢？",ExpansionMode.OFF,3,true);e.planned("商品退款后优惠券会返还吗？","DISABLED",List.of("商品退款后优惠券会返还吗？"),0,0,false);context.put(QueryExpansionTrace.KEY,e);
        q=q.mutate().text("那券呢？").context(context).build();
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(scores(new RerankGateway.Score(1,.9),new RerankGateway.Score(0,.3)));
        ranker.process(q,List.of(doc("a",.8,"运费"),doc("b",.7,"优惠券")));
        verify(gateway).rerank(eq("商品退款后优惠券会返还吗？"),anyList(),eq(2));
    }
    /** 网络失败只做一次请求，原顺序和原分数可继续使用；另一会话的轨迹不会混入。 */
    @Test void failureFallsBackAndRequestStateIsIsolated(){
        when(gateway.rerank(anyString(),anyList(),anyInt())).thenThrow(new IllegalStateException("private-error"));
        Query first=query(true,1,5000),second=query(false,2,5000);var docs=List.of(doc("a",.9,"A"),doc("b",.5,"B"));
        var fallback=ranker.process(first,docs);ranker.process(second,docs);
        assertThat(fallback).extracting(Document::getId).containsExactly("a");assertThat(fallback.get(0).getScore()).isEqualTo(.9);
        assertThat(fallback.get(0).getMetadata()).containsEntry("rerankFallback",true).doesNotContainKey("rerankScore");
        assertThat(trace(first).snapshot().status()).isEqualTo("FALLBACK_ERROR");assertThat(trace(second).snapshot().status()).isEqualTo("DISABLED");verify(gateway,times(1)).rerank(anyString(),anyList(),anyInt());
    }
    /** 未配置、空候选、单条、用户关闭分别报告真实状态，无伪造重排分数。 */
    @Test void skippedAndUnconfiguredStatesAreExplicit(){
        Query empty=query(true,6,5000);assertThat(ranker.process(empty,List.of())).isEmpty();
        Query single=query(true,6,5000);ranker.process(single,List.of(doc("a",.7,"A")));verifyNoInteractions(gateway);
        when(gateway.rerank(anyString(),anyList(),anyInt())).thenThrow(new RerankGateway.NotConfigured());
        Query missing=query(true,6,5000);ranker.process(missing,List.of(doc("a",.7,"A"),doc("b",.6,"B")));
        assertThat(trace(empty).snapshot().status()).isEqualTo("SKIPPED_EMPTY");assertThat(trace(single).snapshot().status()).isEqualTo("SKIPPED_SINGLE");assertThat(trace(missing).snapshot().status()).isEqualTo("FALLBACK_NOT_CONFIGURED");assertThat(trace(missing).snapshot().inputCount()).isZero();
    }
    /** 即使处理器直接调用，也不能把错误租户作为可降级候选发到外部服务。 */
    @Test void foreignTenantFailsBeforeProvider(){
        Query q=query(true,2,5000);Document foreign=doc("bad",.8,"PRIVATE").mutate().metadata(Map.of("tenantId","other")).build();
        assertThatThrownBy(()->ranker.process(q,List.of(doc("ok",.9,"ok"),foreign))).isInstanceOf(IllegalStateException.class);verifyNoInteractions(gateway);
    }
    /** 替换 Gateway 后仍严格校验整个结果，越界或缺项不能静默生成半组证据。 */
    @Test void invalidGatewayResultFallsBackWholeBatch(){
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(scores(new RerankGateway.Score(100,.8),new RerankGateway.Score(0,.9)));
        Query q=query(true,2,5000);assertThat(ranker.process(q,List.of(doc("a",.9,"A"),doc("b",.8,"B")))).extracting(Document::getId).containsExactly("a","b");
        assertThat(trace(q).snapshot().status()).isEqualTo("FALLBACK_ERROR");
    }
    /** 输入超长不截正文，候选超过24也不发送；不能靠返回 index 引用没送入的资料。 */
    @Test void inputLimitsAreVisibleAndBoundProviderCall(){
        var docs=new ArrayList<Document>();docs.add(doc("long",.99,"x".repeat(3501)));for(int i=0;i<25;i++)docs.add(doc("d"+i,.8,"d"+i));
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(scores(new RerankGateway.Score(0,.9),new RerankGateway.Score(1,.8)));
        Query q=query(true,2,5000);ranker.process(q,docs);
        ArgumentCaptor<List<String>> texts=ArgumentCaptor.forClass(List.class);verify(gateway).rerank(anyString(),texts.capture(),eq(2));
        assertThat(texts.getValue()).hasSize(23).doesNotContain("x".repeat(3501));
        assertThat(trace(q).snapshot().exclusions()).extracting(RerankTrace.Exclusion::reason).contains("RERANK_INPUT_BUDGET","CANDIDATE_LIMIT");
    }
    /** 排序后预算选完整块，第一块过长时仍能选后面的短块；拼接分隔符也计入估算。 */
    @Test void budgetSkipsWholeBlocksAndCountsSeparators(){
        Query q=query(false,6,100);var longDoc=doc("long",.9,"x".repeat(101));var first=doc("a",.8,"a".repeat(60));var second=doc("b",.7,"b".repeat(39));var small=doc("c",.6,"c".repeat(30));
        var result=budget.process(q,List.of(longDoc,first,second,small));
        assertThat(result).extracting(Document::getId).containsExactly("a","c");assertThat(result.get(0).getText()).hasSize(60);
        assertThat(trace(q).snapshot().estimatedContextTokens()).isEqualTo(92);assertThat(trace(q).snapshot().exclusions()).hasSize(2);
    }
    /** 所有知识块超预算时返回空列表，让后面的证据门真正停止生成。 */
    @Test void allOverBudgetProducesNoEvidence(){
        Query q=query(false,6,100);assertThat(budget.process(q,List.of(doc("long",.9,"x".repeat(101))))).isEmpty();
        assertThat(trace(q).snapshot().finalDocuments()).isEmpty();
        var docs=IntStream.range(0,9).mapToObj(i->doc("d"+i,.8,"短文")).toList();Query many=query(false,10,5000);
        assertThat(budget.process(many,docs)).hasSize(6);assertThat(trace(many).snapshot().exclusions()).hasSize(3);
    }
    /** 严格协议映射：Authorization 不进正文，真实分数排序，usage 保留。 */
    @Test void gatewayUsesOfficialTopLevelProtocol(){
        RestClient.Builder builder=RestClient.builder().baseUrl("https://example.maas.aliyuncs.com").defaultHeader("Authorization","Bearer offline-placeholder");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.maas.aliyuncs.com/compatible-api/v1/reranks")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization","Bearer offline-placeholder")).andExpect(jsonPath("$.model").value("qwen3-rerank"))
            .andExpect(jsonPath("$.query").value("优惠券" )).andExpect(jsonPath("$.top_n").value(2)).andExpect(jsonPath("$.documents[1]").value("退款返券规则"))
            .andExpect(jsonPath("$.input").doesNotExist()).andRespond(withSuccess("{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}],\"usage\":{\"total_tokens\":28}}",MediaType.APPLICATION_JSON));
        var result=new DashScopeQwenRerankGateway(builder.build(),"qwen3-rerank").rerank("优惠券",List.of("包邮","退款返券规则"),2);
        assertThat(result.scores()).extracting(RerankGateway.Score::index).containsExactly(1,0);assertThat(result.totalTokens()).isEqualTo(28);server.verify();
    }
    /** 缺字段、重复/越界下标、错类型、坏分数、缺项和非数组均不能当作有效排序。 */
    @ParameterizedTest @ValueSource(strings={"{}","{\"results\":[]}","{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}",
        "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8}]}",
        "{\"results\":[{\"index\":0},{\"index\":1,\"relevance_score\":0.8}]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":1.9},{\"index\":1,\"relevance_score\":0.8}]}",
        "{\"results\":[{\"index\":0.5,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8}]}"})
    void malformedHttpResultsAreRejected(String json){
        var builder=RestClient.builder().baseUrl("https://example.maas.aliyuncs.com");var server=MockRestServiceServer.bindTo(builder).build();server.expect(anything()).andRespond(withSuccess(json,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->new DashScopeQwenRerankGateway(builder.build(),"qwen3-rerank").rerank("q",List.of("a","b"),2)).isInstanceOf(IllegalStateException.class);server.verify();
    }
    /** 本地实验参数也受服务端限制；供应商地址只接受官方业务空间主机。 */
    @Test void validatesOptionsAndServerEndpoint(){
        assertThatThrownBy(()->new RerankOptions(true,11,6,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new LocalRerankController.Request("q",null,6,0,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RerankConfiguration().rerankGateway("https://evil.example", "offline-placeholder")).isInstanceOf(IllegalArgumentException.class);
    }
    /** 真正 RAA 链路必须把精排和预算后的顺序交给生成模型，并按同样顺序返回来源。 */
    @Test void actualAdvisorUsesPostProcessedContext(){
        var model=mock(ChatModel.class);var store=mock(VectorStore.class);var config=new CustomerModularRagConfiguration();var ec=new QueryExpansionConfiguration();
        var memory=new ChatMemoryConfig().customerChatMemory();var filters=new KnowledgeFilterFactory();var advisors=new CustomerAdvisorConfiguration();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("按资料核对券规则。")))));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("noise",.9,"包邮规则"),doc("coupon",.51,"退款后返券但过期券不退")));
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(scores(new RerankGateway.Score(1,.97),new RerankGateway.Score(0,.1)));
        var rag=config.customerModularRagAdvisor(config.compression(config.queryTransformerChatClientBuilder(model,false)),ec.guardedQueryExpander(ec.builder(model,false)),
            new MultiQueryRetrieval(store,new ConcatenationDocumentJoiner()),config.customerQueryAugmenter(),ranker,budget);
        ChatClient client=new KnowledgeChatClientConfiguration().knowledgeConversationChatClient(model,advisors.requestAuditAdvisor(filters),advisors.customerMemoryAdvisor(memory),rag,advisors.evidenceRequiredAdvisor(),false);
        clearInvocations(model);var response=new AdvisorKnowledgeAnswerService(client,filters,memory).answer(TENANT,"rerank-pipeline",1001L,"退款后券会返还吗？",ExpansionMode.OFF);
        assertThat(response.status()).isEqualTo(KnowledgeAnswerStatus.ANSWERED);assertThat(response.references()).extracting(KnowledgeReference::documentId).containsExactly("coupon","noise");
        assertThat(response.reranking().status()).isEqualTo("SUCCEEDED");var capture=ArgumentCaptor.forClass(Prompt.class);verify(model,times(1)).call(capture.capture());
        String prompt=capture.getValue().getUserMessage().getText();assertThat(prompt.indexOf("退款后返券但过期券不退")).isLessThan(prompt.indexOf("包邮规则"));
        verify(gateway,times(1)).rerank(eq("退款后券会返还吗？"),anyList(),eq(2));
    }
    /** 超时/503 只请求一次，不把供应商错误正文传给调用者的正常响应。 */
    @Test void gatewayDoesNotRetryHttpFailure() {
        var builder=RestClient.builder().baseUrl("https://example.maas.aliyuncs.com");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private-provider-body"));
        var http=new DashScopeQwenRerankGateway(builder.build(),"qwen3-rerank");
        var processor=new QwenRerankDocumentPostProcessor(http,estimator);
        Query q=query(true,2,5000);processor.process(q,List.of(doc("a",.9,"A"),doc("b",.8,"B")));
        assertThat(trace(q).snapshot().status()).isEqualTo("FALLBACK_ERROR");server.verify();
        var timeoutBuilder=RestClient.builder().baseUrl("https://example.maas.aliyuncs.com");
        var timeoutServer=MockRestServiceServer.bindTo(timeoutBuilder).build();
        timeoutServer.expect(anything()).andRespond(withException(new java.net.SocketTimeoutException("read timeout")));
        Query timeout=query(true,2,5000);new QwenRerankDocumentPostProcessor(new DashScopeQwenRerankGateway(timeoutBuilder.build(),"qwen3-rerank"),estimator)
            .process(timeout,List.of(doc("a",.9,"A"),doc("b",.8,"B")));
        assertThat(trace(timeout).snapshot().status()).isEqualTo("FALLBACK_ERROR");timeoutServer.verify();
    }

    /** 原本有命中，但后处理预算全部排除时，真实证据门必须阻止最终模型调用。 */
    @Test void advisorStopsGenerationAfterBudgetRemovesAllEvidence() {
        var model=mock(ChatModel.class);var store=mock(VectorStore.class);var config=new CustomerModularRagConfiguration();var ec=new QueryExpansionConfiguration();
        var memory=new ChatMemoryConfig().customerChatMemory();var filters=new KnowledgeFilterFactory();var advisors=new CustomerAdvisorConfiguration();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("oversized",.9,"x".repeat(5001))));
        var rag=config.customerModularRagAdvisor(config.compression(config.queryTransformerChatClientBuilder(model,false)),ec.guardedQueryExpander(ec.builder(model,false)),
            new MultiQueryRetrieval(store,new ConcatenationDocumentJoiner()),config.customerQueryAugmenter(),ranker,budget);
        var client=new KnowledgeChatClientConfiguration().knowledgeConversationChatClient(model,advisors.requestAuditAdvisor(filters),advisors.customerMemoryAdvisor(memory),rag,advisors.evidenceRequiredAdvisor(),false);
        clearInvocations(model);var result=new AdvisorKnowledgeAnswerService(client,filters,memory).answer(TENANT,"budget-empty",1001L,"退款后券会返还吗？",ExpansionMode.OFF);
        assertThat(result.status()).isEqualTo(KnowledgeAnswerStatus.NO_EVIDENCE);assertThat(result.references()).isEmpty();
        assertThat(result.reranking().finalDocuments()).isEmpty();verify(model,never()).call(any(Prompt.class));verifyNoInteractions(gateway);
    }

    /** 总输入预算必须为每个文档重复计算 Query，不能只加一次查询 token。 */
    @Test void inputBudgetCountsQueryForEachDocument() {
        Query q=query(true,2,5000).mutate().text("q".repeat(2000)).build();
        var docs=IntStream.range(0,24).mapToObj(i->doc("d"+i,.8,"x".repeat(2500))).toList();
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(scores(new RerankGateway.Score(0,.9),new RerankGateway.Score(1,.8)));
        ranker.process(q,docs);ArgumentCaptor<List<String>> texts=ArgumentCaptor.forClass(List.class);
        verify(gateway).rerank(anyString(),texts.capture(),eq(2));assertThat(texts.getValue()).hasSize(13);
    }

}
