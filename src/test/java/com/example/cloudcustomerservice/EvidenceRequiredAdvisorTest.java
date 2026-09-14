package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.ai.advisor.*;
import com.example.cloudcustomerservice.rag.NoKnowledgeEvidenceException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用真实 Document 和模拟后续链验证证据门槛，完全不调用向量库或模型。 */
class EvidenceRequiredAdvisorTest {
    final EvidenceRequiredAdvisor advisor = new EvidenceRequiredAdvisor();

    /** 创建已发布、属于指定租户的得分文档，可单独替换字段验证越界防御。 */
    static Document document(String text, String tenant) {
        return Document.builder().id(UUID.randomUUID().toString()).text(text).score(.9)
                .metadata(Map.of("tenantId",tenant,"status","PUBLISHED","knowledgeBase","after-sales","language","zh-CN",
                        "sourceId","policy","sourceName","售后制度","sourceVersion","3.2","chunkIndex",1,"category","REFUND_POLICY")).build();
    }

    /** Context 只表示本轮共享数据，不能作为单例字段保存。 */
    ChatClientRequest request(Object documents) {
        var context = new HashMap<String,Object>(); context.put(CustomerAdvisorContextKeys.TENANT_ID,"tenant-yunshan");
        context.put(CustomerAdvisorContextKeys.REQUEST_ID,"r-1");
        if (documents != null) context.put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS,documents);
        return new ChatClientRequest(new Prompt("q"),context);
    }

    /** 缺键、空列表或全部空白正文都属于无可用证据，nextCall 不能执行。 */
    @Test void absentEmptyAndBlankEvidenceShortCircuit() {
        for (Object docs : Arrays.asList(null,List.of(),List.of(document(" ","tenant-yunshan")))) {
            var chain=mock(CallAdvisorChain.class);
            assertThatThrownBy(()->advisor.adviseCall(request(docs),chain)).isInstanceOf(NoKnowledgeEvidenceException.class);
            verifyNoInteractions(chain);
        }
    }

    /** 有效证据按原样传给下一层，门槛本身没有第二次检索或生成。 */
    @Test void validDocumentsContinueOnce() {
        var request=request(List.of(document("七日内符合条件可申请退货。","tenant-yunshan")));var chain=mock(CallAdvisorChain.class);
        var response=ChatClientResponse.builder().context(request.context()).build();when(chain.nextCall(request)).thenReturn(response);
        assertThat(advisor.adviseCall(request,chain)).isSameAs(response);verify(chain).nextCall(request);verifyNoMoreInteractions(chain);
    }

    /** 检索返回任意跨范围元数据时，阻止整组 Prompt 进入模型，而不是只在响应中隐藏来源。 */
    @Test void foreignScopeCannotReachModel() {
        for (var entry:Map.of("tenantId","other","status","DRAFT","knowledgeBase","other","language","en-US").entrySet()) {
            var doc=document("PRIVATE_FOREIGN","tenant-yunshan");doc.getMetadata().put(entry.getKey(),entry.getValue());
            var chain=mock(CallAdvisorChain.class);
            assertThatThrownBy(()->advisor.adviseCall(request(List.of(doc)),chain)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(chain);
        }
    }

    /** 非文档对象、无效分数及混有空正文都不是可信的成功检索结果。 */
    @Test void malformedContextAndScoresAreRejected() {
        var badScore=Document.builder().text("x").score(Double.NaN).metadata(document("x","tenant-yunshan").getMetadata()).build();
        for(Object docs:List.of("forged",List.of("fake"),List.of(badScore),List.of(document("x","tenant-yunshan"),document(" ","tenant-yunshan")))) {
            var chain=mock(CallAdvisorChain.class);assertThatThrownBy(()->advisor.adviseCall(request(docs),chain)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(chain);
        }
    }
}
