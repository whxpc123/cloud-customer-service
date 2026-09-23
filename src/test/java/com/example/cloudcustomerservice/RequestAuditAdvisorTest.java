package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.ai.advisor.*;
import com.example.cloudcustomerservice.knowledge.KnowledgeFilterFactory;
import com.example.cloudcustomerservice.rag.NoKnowledgeEvidenceException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 审计和洋葱顺序的离线验证，不连接数据库或百炼。 */
@ExtendWith(OutputCaptureExtension.class)
class RequestAuditAdvisorTest {
    final KnowledgeFilterFactory filters = new KnowledgeFilterFactory();
    final RequestAuditAdvisor advisor = new RequestAuditAdvisor(filters);

    /** 构造合法的本次上下文；问题包含唯一标记，方便断言审计不打印原文。 */
    Map<String, Object> context() {
        return new HashMap<>(Map.of(CustomerAdvisorContextKeys.REQUEST_ID, "r-1",
                CustomerAdvisorContextKeys.TENANT_ID, "tenant-yunshan",
                ChatMemory.CONVERSATION_ID, "knowledge/tenant-yunshan/guest/c-1",
                VectorStoreDocumentRetriever.FILTER_EXPRESSION, filters.publishedAfterSales("tenant-yunshan")));
    }

    /** 合法上下文向后传一次，原响应对象与 Context 保持不变，日志只含审计字段。 */
    @Test void continuesExactlyOnceAndLogsOnlyMetadata(CapturedOutput output) {
        var request = new ChatClientRequest(new Prompt("PRIVATE_QUESTION"), context());
        var chain = mock(CallAdvisorChain.class);
        var response = ChatClientResponse.builder().context(request.context())
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_ANSWER"))))).build();
        when(chain.nextCall(request)).thenReturn(response);
        assertThat(advisor.adviseCall(request, chain)).isSameAs(response);
        verify(chain).nextCall(request); verifyNoMoreInteractions(chain);
        assertThat(output.getAll()).contains("requestId=r-1", "tenantId=tenant-yunshan", "durationMs=", "hasResponse=true")
                .doesNotContain("PRIVATE_QUESTION", "PRIVATE_ANSWER", "knowledge/tenant-yunshan/guest/c-1");
    }

    /** requestId、tenantId、会话键、动态过滤器缺任一项，都应在记忆/检索/模型之前被拒绝。 */
    @Test void missingRequiredContextNeverContinues() {
        for (String key : List.of(CustomerAdvisorContextKeys.REQUEST_ID, CustomerAdvisorContextKeys.TENANT_ID,
                ChatMemory.CONVERSATION_ID, VectorStoreDocumentRetriever.FILTER_EXPRESSION)) {
            var values = context(); values.remove(key); var chain = mock(CallAdvisorChain.class);
            assertThatIllegalArgumentException().isThrownBy(() -> advisor.adviseCall(new ChatClientRequest(new Prompt("q"), values), chain));
            verifyNoInteractions(chain);
        }
    }

    /** 拦住伪造日志 ID、跨租户记忆键和放宽过滤器，不能让缺失安全上下文回落默认值。 */
    @Test void rejectsInvalidIdentifiersAndMismatchedFiltersWithoutEcho(CapturedOutput output) {
        for (var entry : Map.of(CustomerAdvisorContextKeys.REQUEST_ID, "BAD_MARKER\nINJECT",
                CustomerAdvisorContextKeys.TENANT_ID, "tenant' OR true", ChatMemory.CONVERSATION_ID, "knowledge/other/guest/c-1",
                VectorStoreDocumentRetriever.FILTER_EXPRESSION, "status == 'PUBLISHED'").entrySet()) {
            var values = context(); values.put(entry.getKey(), entry.getValue()); var chain = mock(CallAdvisorChain.class);
            assertThatIllegalArgumentException().isThrownBy(() -> advisor.adviseCall(new ChatClientRequest(new Prompt("q"), values), chain));
            verifyNoInteractions(chain);
        }
        assertThat(output.getAll()).contains("status=INVALID_CONTEXT").doesNotContain("BAD_MARKER", "INJECT", "OR true");
    }

    /** 普通异常和无证据短路均向上抛出；审计不把原始错误文本写日志。 */
    @Test void failureAndNoEvidenceAreLoggedAndRethrown(CapturedOutput output) {
        var request = new ChatClientRequest(new Prompt("q"), context()); var chain = mock(CallAdvisorChain.class);
        var failure = new IllegalStateException("PRIVATE_PROVIDER_ERROR");
        when(chain.nextCall(request)).thenThrow(failure);
        assertThatThrownBy(() -> advisor.adviseCall(request, chain)).isSameAs(failure);
        doThrow(new NoKnowledgeEvidenceException()).when(chain).nextCall(request);
        assertThatThrownBy(() -> advisor.adviseCall(request, chain)).isInstanceOf(NoKnowledgeEvidenceException.class);
        assertThat(output.getAll()).contains("status=FAILED", "errorType=IllegalStateException", "status=NO_EVIDENCE", "generationCalled=false")
                .doesNotContain("PRIVATE_PROVIDER_ERROR");
    }

    /** 故意乱序注册 A/B/C，真实 ChatClient 按 order 进入、反向返回，仅执行一次模型。 */
    @Test void realChainUsesOrderAndUnwindsLikeAnOnion() {
        var events = new ArrayList<String>(); var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> { events.add("model"); return new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))); });
        var client = ChatClient.builder(model).defaultAdvisors(probe("C",30,events),probe("A",10,events),probe("B",20,events)).build();
        client.prompt().user("q").call().chatClientResponse();
        assertThat(events).containsExactly("A-before","B-before","C-before","model","C-after","B-after","A-after");
        verify(model).call(any(Prompt.class));
    }

    /** 无请求字段的测试 Advisor，记录同步 nextCall 两侧的实际访问顺序。 */
    private CallAdvisor probe(String name, int order, List<String> events) {
        return new CallAdvisor() {
            /** 请求先记录 before，响应返回后再记录 after。 */
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                events.add(name+"-before"); var result=chain.nextCall(request); events.add(name+"-after"); return result;
            }
            /** 使用固定测试顺序值。 */
            public int getOrder() { return order; }
            /** 使用固定测试名称。 */
            public String getName() { return name; }
        };
    }
}
