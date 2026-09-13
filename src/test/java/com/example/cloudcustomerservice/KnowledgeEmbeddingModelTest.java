package com.example.cloudcustomerservice;

import java.util.*;
import java.util.stream.IntStream;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.example.cloudcustomerservice.knowledge.KnowledgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeEmbeddingModelTest {
    final EmbeddingModel delegate=mock(EmbeddingModel.class);
    final KnowledgeEmbeddingModel model=new KnowledgeEmbeddingModel(delegate);
    static float[] vector(float value) {var v=new float[1024];v[0]=value;return v;}
    @Test void usesQueryAndDocumentRolesWithoutEmbeddingMetadata() {
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(vector(1),0))));
        model.embed("查询");model.embed(new Document("正文",Map.of("privateMarker","DO_NOT_EMBED")));
        var requests=ArgumentCaptor.forClass(EmbeddingRequest.class);verify(delegate,times(2)).call(requests.capture());
        var calls=requests.getAllValues();
        assertThat(((DashScopeEmbeddingOptions)calls.get(0).getOptions()).getTextType()).isEqualTo("query");
        assertThat(((DashScopeEmbeddingOptions)calls.get(1).getOptions()).getTextType()).isEqualTo("document");
        assertThat(calls.get(1).getInstructions()).containsExactly("正文");verify(delegate,never()).dimensions();
    }
    @Test void batchesTenAndRestoresResponseIndexOrder() {
        when(delegate.call(any())).thenAnswer(inv->{ EmbeddingRequest req=inv.getArgument(0);
            var result=new ArrayList<Embedding>();for(int i=req.getInstructions().size()-1;i>=0;i--)result.add(new Embedding(vector(Float.parseFloat(req.getInstructions().get(i))),i));return new EmbeddingResponse(result);});
        var docs=IntStream.rangeClosed(1,21).mapToObj(i->new Document(String.valueOf(i))).toList();
        var vectors=model.embed(docs,EmbeddingOptions.builder().build(),new TokenCountBatchingStrategy());
        assertThat(vectors).extracting(v->v[0]).containsExactlyElementsOf(IntStream.rangeClosed(1,21).mapToObj(i->(float)i).toList());
        var args=ArgumentCaptor.forClass(EmbeddingRequest.class);verify(delegate,times(3)).call(args.capture());
        assertThat(args.getAllValues()).extracting(r->r.getInstructions().size()).containsExactly(10,10,1);
    }
    @Test void invalidShapesAndNonfiniteOrZeroVectorsFail() {
        var nan=vector(1);nan[2]=Float.NaN;
        for(var response:List.of(new EmbeddingResponse(List.of()),new EmbeddingResponse(List.of(new Embedding(new float[]{1},0))),
                new EmbeddingResponse(List.of(new Embedding(vector(1),2))),new EmbeddingResponse(List.of(new Embedding(nan,0))),
                new EmbeddingResponse(List.of(new Embedding(new float[1024],0))))) {
            when(delegate.call(any())).thenReturn(response);assertThatThrownBy(()->model.embed("q")).isInstanceOf(IllegalStateException.class);
        }
    }
}
