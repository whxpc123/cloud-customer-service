package com.example.cloudcustomerservice.routing;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.example.cloudcustomerservice.routing.CustomerRouter.*;
import static org.assertj.core.api.Assertions.*;

/** 不访问模型的路由防御测试；不把假分类器的结果当成 Qwen 真实分类准确率。 */
class CustomerRouterTest {
    @ParameterizedTest @ValueSource(strings={"我要转人工！", "转人工", "人工客服", "找真人客服。", "谢谢！", "你好", "您好。", "再见"})
    void exactRulesSkipClassifier(String message) {
        var calls = new AtomicInteger();
        var router = new CustomerRouter(i -> { calls.incrementAndGet(); throw new AssertionError("不应调用模型"); });
        var d = router.route(new Input(message, ""));
        assertThat(d.source()).isEqualTo(Source.RULE);
        assertThat(d.route()).isEqualTo(message.contains("人工") || message.contains("真人") ? Route.HUMAN_SERVICE : Route.SMALL_TALK);
        assertThat(calls.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"不要转人工，先说退货规则", "人工客服几点上班？", "人工智能客服能查订单吗", "你好，订单发货了吗", "他说‘转人工’是什么意思", "我要查订单"})
    void substringsDoNotOverrideClassifier(String message) {
        var calls = new AtomicInteger();
        var router = new CustomerRouter(i -> { calls.incrementAndGet(); return new Proposal(Route.KNOWLEDGE, false, false); });
        assertThat(router.route(new Input(message, "")).source()).isEqualTo(Source.MODEL);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void nullMissingAndAmbiguousResultsNeverDispatchSuggestedRoute() {
        for (Proposal p : new Proposal[]{null, new Proposal(null,false,false), new Proposal(Route.ORDER_QUERY,null,false), new Proposal(Route.ORDER_QUERY,false,null)})
            assertThat(new CustomerRouter(i->p).route(new Input("查订单", "")).reasonCode()).isEqualTo("INVALID_MODEL_RESULT");
        assertThat(new CustomerRouter(i->new Proposal(Route.ORDER_QUERY,true,false)).route(new Input("那个呢", "")).reasonCode()).isEqualTo("AMBIGUOUS_INTENT");
        assertThat(new CustomerRouter(i->new Proposal(Route.ORDER_QUERY,false,true)).route(new Input("订单与开票", "")).reasonCode()).isEqualTo("MULTIPLE_INDEPENDENT_TASKS");
    }
    @Test void failureIsNotReportedAsUserAmbiguity() {
        var d = new CustomerRouter(i->{throw new IllegalStateException("provider");}).route(new Input("查订单 A10001", ""));
        assertThat(d).isEqualTo(new Decision(Route.CLARIFY,Source.GUARD,"MODEL_UNAVAILABLE"));
    }
    @Test void inputBoundsAndMissingOrderParameterAreDifferentFromAmbiguity() {
        assertThatThrownBy(()->new Input(" ","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Input("x".repeat(2001),"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Input("订单","x".repeat(6001))).isInstanceOf(IllegalArgumentException.class);
        var router = new CustomerRouter(i->new Proposal(Route.ORDER_QUERY,false,false));
        assertThat(router.route(new Input("我要查订单",null)).route()).isEqualTo(Route.ORDER_QUERY);
    }
}
