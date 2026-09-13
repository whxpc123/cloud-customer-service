package com.example.cloudcustomerservice;

import java.util.*;
import com.example.cloudcustomerservice.knowledge.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeServiceTest {
    final VectorStore store=mock(VectorStore.class);
    final LocalKnowledgeDocuments fixtures=new LocalKnowledgeDocuments();
    final KnowledgeSearchService search=new KnowledgeSearchService(store);
    final LocalKnowledgeImportService importer=new LocalKnowledgeImportService(store,fixtures);
    @Test void fixturesHaveStableIdsAndTraceableEmbeddingMetadata() {
        var first=fixtures.documents();var second=fixtures.documents();
        assertThat(first).extracting(Document::getId).containsExactlyElementsOf(second.stream().map(Document::getId).toList());
        assertThat(first).hasSize(4).extracting(Document::getId).doesNotHaveDuplicates();
        first.forEach(d->{ UUID.fromString(d.getId());assertThat(d.getMetadata()).containsEntry("tenantId","tenant-yunshan")
                .containsEntry("embeddingDimensions",1024).containsEntry("embeddingModel","text-embedding-v4"); });
    }
    @Test void importUsesUpsertWithoutDeletingFirst() {
        assertThat(importer.importDocuments()).isEqualTo(4);assertThat(importer.importDocuments()).isEqualTo(4);
        verify(store,times(2)).add(anyList());verifyNoMoreInteractions(store);
    }
    @Test void importFailureDoesNotRequestDeleteOrLeakProviderText() {
        doThrow(new RuntimeException("PRIVATE_PROVIDER_ERROR")).when(store).add(anyList());
        assertThatThrownBy(importer::importDocuments).isInstanceOf(KnowledgeUnavailableException.class).hasNoCause();
        verify(store).add(anyList());verifyNoMoreInteractions(store);
    }
    @Test void searchBuildsAllFiltersAndAppliesControls() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(search.search("tenant-yunshan","退货",3,0.7).hits()).isEmpty();
        var captor=ArgumentCaptor.forClass(SearchRequest.class);verify(store).similaritySearch(captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(3);assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.7);
        assertThat(captor.getValue().getFilterExpression().toString()).contains("tenantId","tenant-yunshan","status","PUBLISHED","knowledgeBase","after-sales","language","zh-CN");
    }
    @Test void metadataDefenseRejectsAnyForeignScopeAndPreservesSource() {
        List<Document> docs=new ArrayList<>();var original=fixtures.documents().get(0);
        docs.add(scored(original,original.getMetadata()));
        for(var entry:Map.of("tenantId","tenant-songguo","status","DRAFT","knowledgeBase","other","language","en-US").entrySet()) {
            var metadata=new HashMap<>(original.getMetadata());metadata.put(entry.getKey(),entry.getValue());docs.add(scored(original,metadata));
        }
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(docs);
        var hits=search.search("tenant-yunshan","退货",null,null).hits();
        assertThat(hits).hasSize(1);assertThat(hits.get(0).sourceVersion()).isEqualTo("3.2");assertThat(hits.get(0).chunkIndex()).isEqualTo(1);
    }
    @Test void invalidRequestsNeverCallStore() {
        for(String q:Arrays.asList(null," ","x".repeat(2001)))assertThatIllegalArgumentException().isThrownBy(()->search.search("tenant-yunshan",q,null,null));
        for(int k:new int[]{0,11})assertThatIllegalArgumentException().isThrownBy(()->search.search("tenant-yunshan","q",k,null));
        for(double t:new double[]{-0.1,1.1,Double.NaN,Double.POSITIVE_INFINITY})assertThatIllegalArgumentException().isThrownBy(()->search.search("tenant-yunshan","q",null,t));
        assertThatIllegalArgumentException().isThrownBy(()->search.search("tenant' OR true","q",null,null));verifyNoInteractions(store);
    }
    @Test void controllerIgnoresClientTenantAndReturnsStableErrors() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new LocalKnowledgeSearchController(search),new LocalKnowledgeImportController(importer)).setControllerAdvice(new KnowledgeErrors()).build();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        mvc.perform(post("/internal/knowledge/search").contentType("application/json").header("X-Tenant-Id","tenant-songguo")
                .content("{\"query\":\"tenant-songguo 的政策\",\"tenantId\":\"tenant-songguo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits").isEmpty());
        var captured=ArgumentCaptor.forClass(SearchRequest.class);verify(store).similaritySearch(captured.capture());
        assertThat(captured.getValue().getFilterExpression().toString()).contains("tenant-yunshan").doesNotContain("tenant-songguo");
        mvc.perform(post("/internal/knowledge/search").contentType("application/json").content("{\"query\":\" \"}")).andExpect(status().isBadRequest());
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("PRIVATE_UPSTREAM"));
        mvc.perform(post("/internal/knowledge/search").contentType("application/json").content("{\"query\":\"q\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"));
    }
    private Document scored(Document source,Map<String,Object> metadata) {
        return Document.builder().id(source.getId()).text(source.getText()).metadata(metadata).score(0.9).build();
    }
}
