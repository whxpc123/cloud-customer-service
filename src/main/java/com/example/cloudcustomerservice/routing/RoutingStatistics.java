package com.example.cloudcustomerservice.routing;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import static com.example.cloudcustomerservice.routing.CustomerRouter.*;

/** 进程级路由观测计数，不含问题原文、身份或订单号；重启归零，不是分类正确率评测。 */
@Component
@Profile("local & knowledge")
public class RoutingStatistics {
    private long decisions, rules, modelCalls, clarify, failures, human, nanos;
    private final Map<Route, Long> routes = new EnumMap<>(Route.class);
    public record Timing(long elapsedMs, int classifierCalls) { }
    /** 核心每个非规则分支只尝试一次分类调用；人工接管状态在核心之外拦截，不纳入分类统计。 */
    synchronized Timing record(Decision d, long elapsedNanos) {
        decisions++; nanos += elapsedNanos;
        int calls = d.source() == Source.RULE ? 0 : 1;
        modelCalls += calls;
        if (d.source() == Source.RULE) rules++;
        if (d.route() == Route.CLARIFY) clarify++;
        if (Set.of("MODEL_UNAVAILABLE", "INVALID_MODEL_RESULT").contains(d.reasonCode())) failures++;
        if (d.route() == Route.HUMAN_SERVICE) human++;
        routes.merge(d.route(), 1L, Long::sum);
        return new Timing(elapsedNanos / 1_000_000, calls);
    }
    /** 诊断和正式机器人请求共用计数；模型次数指分类器尝试次数，不统计下游模型或网络重试。 */
    public synchronized Map<String, Object> snapshot() {
        return Map.of("decisions", decisions, "ruleHits", rules, "classifierCalls", modelCalls,
                "clarifications", clarify, "classificationFailures", failures, "humanRequests", human,
                "averageRoutingMs", decisions == 0 ? 0.0 : nanos / 1_000_000.0 / decisions,
                "routes", Map.copyOf(routes), "scope", "PROCESS_SINCE_START_INCLUDING_DIAGNOSTICS");
    }
}
