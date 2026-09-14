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

/**
 * 知识向量角色适配测试：模拟底层 EmbeddingModel，但使用真实请求选项和响应 index。
 * 验证 query/document 区分、每批十条、响应重排和固定 1024 维数据检查。
 */

class KnowledgeEmbeddingModelTest {
    final EmbeddingModel delegate=mock(EmbeddingModel.class);
    final KnowledgeEmbeddingModel model=new KnowledgeEmbeddingModel(delegate);
    /**
     * 构造固定 1024 维、可预测的测试向量；只为验证批次或 SQL 行为，不代表真实语义嵌入。
     */
    static float[] vector(float value) {var v=new float[1024];v[0]=value;return v;}
    /**
     * 捕获两类向量请求，确认搜索用 query、入库用 document，且元数据不混入向量化正文。
     */
    @Test void usesQueryAndDocumentRolesWithoutEmbeddingMetadata() {
        when(delegate.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(vector(1),0))));
        model.embed("查询");model.embed(new Document("正文",Map.of("privateMarker","DO_NOT_EMBED")));
        var requests=ArgumentCaptor.forClass(EmbeddingRequest.class);verify(delegate,times(2)).call(requests.capture());
        var calls=requests.getAllValues();
        assertThat(((DashScopeEmbeddingOptions)calls.get(0).getOptions()).getTextType()).isEqualTo("query");
        assertThat(((DashScopeEmbeddingOptions)calls.get(1).getOptions()).getTextType()).isEqualTo("document");
        assertThat(calls.get(1).getInstructions()).containsExactly("正文");verify(delegate,never()).dimensions();
    }
    /**
     * 模拟乱序 index 的模型响应，检查每批十条限制以及结果重新对齐原输入。
     */
    @Test void batchesTenAndRestoresResponseIndexOrder() {
        when(delegate.call(any())).thenAnswer(inv->{ EmbeddingRequest req=inv.getArgument(0);
            var result=new ArrayList<Embedding>();for(int i=req.getInstructions().size()-1;i>=0;i--)result.add(new Embedding(vector(Float.parseFloat(req.getInstructions().get(i))),i));return new EmbeddingResponse(result);});
        var docs=IntStream.rangeClosed(1,21).mapToObj(i->new Document(String.valueOf(i))).toList();
        var vectors=model.embed(docs,EmbeddingOptions.builder().build(),new TokenCountBatchingStrategy());
        assertThat(vectors).extracting(v->v[0]).containsExactlyElementsOf(IntStream.rangeClosed(1,21).mapToObj(i->(float)i).toList());
        var args=ArgumentCaptor.forClass(EmbeddingRequest.class);verify(delegate,times(3)).call(args.capture());
        assertThat(args.getAllValues()).extracting(r->r.getInstructions().size()).containsExactly(10,10,1);
    }
    /**
     * 逐项构造非法响应索引、数量、维度、数值或零向量，防止不可用向量进入知识库。
     */
    @Test void invalidShapesAndNonfiniteOrZeroVectorsFail() {
        var nan=vector(1);nan[2]=Float.NaN;
        for(var response:List.of(new EmbeddingResponse(List.of()),new EmbeddingResponse(List.of(new Embedding(new float[]{1},0))),
                new EmbeddingResponse(List.of(new Embedding(vector(1),2))),new EmbeddingResponse(List.of(new Embedding(nan,0))),
                new EmbeddingResponse(List.of(new Embedding(new float[1024],0))))) {
            when(delegate.call(any())).thenReturn(response);assertThatThrownBy(()->model.embed("q")).isInstanceOf(IllegalStateException.class);
        }
    }
}
