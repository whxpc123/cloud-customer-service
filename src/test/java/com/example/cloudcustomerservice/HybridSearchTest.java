package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.knowledge.search.*;
import com.example.cloudcustomerservice.rag.rerank.*;
import com.example.cloudcustomerservice.ai.advisor.CustomerAdvisorContextKeys;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 第十四章算法与资源边界：无云调用，使用可手算排名和恶意输入。 */
class HybridSearchTest {
    static Document doc(String id,double score){return Document.builder().id(id).text("测试规则 "+id).score(score).metadata(Map.of("tenantId","tenant-yunshan","status","PUBLISHED","knowledgeBase","after-sales","language","zh-CN")).build();}
    @Test void identifiersHaveAsciiBoundariesAndNoPartialMatches(){
        assertThat(BusinessIdentifierExtractor.extract("请问cpn-88a7退款与SKU-E100、POLICY-4.2？")).containsExactly("CPN-88A7","SKU-E100","POLICY-4.2");
        assertThat(BusinessIdentifierExtractor.extract("XCPN-88A7 CPN-88A7-more SKU-E1000")).containsExactly("SKU-E1000");
        assertThat(BusinessIdentifierExtractor.extract("CPN-88A7 cpn-88a7")).containsExactly("CPN-88A7");
    }
    @Test void rejectsOversizedInputBeforeSearch(){
        assertThatThrownBy(()->BusinessIdentifierExtractor.extract("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->BusinessIdentifierExtractor.extract(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->BusinessIdentifierExtractor.extract(java.util.stream.IntStream.range(1000,1009).mapToObj(i->"SKU-"+i).reduce("",(a,b)->a+" "+b))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void realtimeOrdersAreNotKnowledgeCodes(){
        assertThat(BusinessIdentifierExtractor.requiresTool("A10001 发货了吗？")).isTrue();
        assertThat(BusinessIdentifierExtractor.requiresTool("A10002 发货了吗？")).isTrue();
        assertThat(BusinessIdentifierExtractor.requiresTool("我的退款到账了吗？")).isTrue();
        assertThat(BusinessIdentifierExtractor.requiresTool("CPN-88A7退款规则")).isFalse();
    }
    @Test void rrfUsesRanksAndCountsEachRouteOnce(){
        var a=doc("A",.1);var b=doc("B",.9);var c=doc("C",.4);var d=doc("D",.8);
        var result=ReciprocalRankFusion.fuse(List.of(a,b,c),List.of(c,a,d),List.of(),24);
        assertThat(result).extracting(Document::getId).containsExactly("A","C","B","D");
        assertThat(result.get(0).getScore()).isCloseTo(1d/61+1d/62,within(1e-12));
        assertThat(a.getMetadata()).doesNotContainKey("rrfScore");
        assertThat(ReciprocalRankFusion.fuse(List.of(a,a),List.of(),List.of(),10).get(0).getScore()).isCloseTo(1d/61,within(1e-12));
    }
    @Test void exactMatchSurvivesFusionCutoffAndKeepsProvenance(){
        var exact=doc("specific",.1);var general=doc("general",.99);
        var result=ReciprocalRankFusion.fuse(List.of(general),List.of(general,exact),List.of(exact),1);
        assertThat(result).extracting(Document::getId).containsExactly("specific");
        assertThat(result.get(0).getMetadata().get("retrievalSources")).isEqualTo(List.of("KEYWORD","EXACT"));
        assertThat(result.get(0).getMetadata()).containsEntry("keywordRank",2).doesNotContainKey("vectorScore");
    }
    @Test void rejectsWrongScopeEvenWhenDuplicateIdWouldHideIt(){
        var repository=mock(PostgresKeywordSearchRepository.class);
        var bad=doc("A",.9).mutate().metadata(Map.of("tenantId","other")).build();
        when(repository.keyword(anyString(),anyString(),anyInt())).thenReturn(List.of(bad));
        when(repository.exact(anyString(),anyList(),anyInt())).thenReturn(List.of());
        assertThatThrownBy(()->new HybridKnowledgeRetriever(repository).combine("tenant-yunshan","退款",List.of(doc("A",.8)),10)).isInstanceOf(IllegalStateException.class);
    }
    @Test void exactPrioritySurvivesSuccessfulModelTopNWithoutFakingScores(){
        var a=doc("specific",.1);var b=doc("general",.9);
        var candidates=ReciprocalRankFusion.fuse(List.of(b),List.of(),List.of(a),10);
        RerankGateway gateway=mock(RerankGateway.class);
        when(gateway.rerank(anyString(),anyList(),eq(2))).thenReturn(new RerankGateway.Result(List.of(new RerankGateway.Score(1,.99),new RerankGateway.Score(0,.1)),20));
        var trace=new RerankTrace(new RerankOptions(true,6,1,5000));
        var query=Query.builder().text("CPN-88A7退款规则").context(Map.of(RerankTrace.KEY,trace,CustomerAdvisorContextKeys.TENANT_ID,"tenant-yunshan")).build();
        var result=new QwenRerankDocumentPostProcessor(gateway,new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator()).process(query,candidates);
        assertThat(result).extracting(Document::getId).containsExactly("specific");
        assertThat(result.get(0).getMetadata()).containsEntry("rerankScore",.1).containsEntry("rerankRank",2);
        assertThat(trace.snapshot().status()).isEqualTo("SUCCEEDED");
    }
    /** 正式多路回调确实接入词法召回；向量轨迹仍只统计向量，不能出现负的重复数。 */
    @Test void productionJoinAddsExactEvidenceButLegacyLabKeepsVectorOnly() {
        var store=mock(org.springframework.ai.vectorstore.VectorStore.class);
        var repo=mock(PostgresKeywordSearchRepository.class);
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(doc("vector",.8)));
        when(repo.keyword(anyString(),anyString(),anyInt())).thenReturn(List.of());
        when(repo.exact(anyString(),eq(List.of("CPN-88A7")),anyInt())).thenReturn(List.of(doc("exact",1)));
        var retrieval=new com.example.cloudcustomerservice.rag.expansion.MultiQueryRetrieval(store,
                new org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner(),new HybridKnowledgeRetriever(repo));
        var expansion=new com.example.cloudcustomerservice.rag.expansion.QueryExpansionTrace("CPN-88A7",com.example.cloudcustomerservice.rag.expansion.ExpansionMode.OFF,3,true);
        expansion.planned("CPN-88A7","DISABLED",List.of("CPN-88A7"),0,0,false);
        var context=new HashMap<String,Object>();context.put(com.example.cloudcustomerservice.rag.expansion.QueryExpansionTrace.KEY,expansion);
        context.put(CustomerAdvisorContextKeys.TENANT_ID,"tenant-yunshan");context.put(HybridKnowledgeRetriever.ENABLED,true);
        var q=Query.builder().text("CPN-88A7").context(context).build();
        assertThat(retrieval.searchAndJoin(List.of(q))).extracting(Document::getId).containsExactly("exact","vector");
        assertThat(expansion.snapshot().duplicateDocumentCount()).isZero();
        clearInvocations(repo);context.remove(HybridKnowledgeRetriever.ENABLED);
        assertThat(retrieval.searchAndJoin(List.of(q.mutate().context(context).build()))).extracting(Document::getId).containsExactly("vector");
        verifyNoInteractions(repo);
    }
    /** 编码提取与两路查询必须使用同一个完整问题，关键词失败不能伪装为成功的单路结果。 */
    @Test void lexicalFailureStopsFusion() {
        var repo=mock(PostgresKeywordSearchRepository.class);
        when(repo.keyword(anyString(),anyString(),anyInt())).thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        assertThatThrownBy(()->new HybridKnowledgeRetriever(repo).combine("tenant-yunshan","CPN-88A7 退款规则",List.of(doc("A",.8)),10))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(repo).keyword("tenant-yunshan","CPN-88A7",10);
        verify(repo,never()).exact(anyString(),anyList(),anyInt());
    }
    @Test void expansionCannotSwapCodePrefixWhileKeepingDigits() {
        assertThat(com.example.cloudcustomerservice.rag.expansion.ExpansionQueryGuard.valid("SKU-E100能退吗？","SKU-X100能退吗？")).isFalse();
        assertThat(com.example.cloudcustomerservice.rag.expansion.ExpansionQueryGuard.valid("CPN-88A7能退吗？","SKU-88A7能退吗？")).isFalse();
    }
    @Test void compressionRetainsExplicitCodesAndKnownReferentialCode() {
        var guard=new com.example.cloudcustomerservice.rag.query.SafeQueryTransformer(q->q.mutate().text("SKU-X100能退吗？").build(),"REWRITE");
        assertThat(guard.transform(Query.builder().text("SKU-E100能退吗？").build()).text()).isEqualTo("SKU-E100能退吗？");
        var followup=new com.example.cloudcustomerservice.rag.query.SafeQueryTransformer(q->q.mutate().text("商品能退吗？").build(),"COMPRESSION");
        var q=Query.builder().text("它能退吗？").history(List.of(new org.springframework.ai.chat.messages.UserMessage("SKU-E100支持什么功能？"))).build();
        assertThat(followup.transform(q).text()).isEqualTo("它能退吗？");
    }
}
