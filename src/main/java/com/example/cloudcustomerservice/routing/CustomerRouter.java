package com.example.cloudcustomerservice.routing;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 第十六章路由核心：确定命令走规则，其余由模型建议，再由 Java 检查有限字段。路由不授予权限。 */
public final class CustomerRouter {
    /** 固定执行名单；没有退款执行、任意类名、方法名或 URL。 */
    public enum Route { SMALL_TALK, KNOWLEDGE, ORDER_QUERY, AFTER_SALE_PRECHECK, HUMAN_SERVICE, CLARIFY, OUT_OF_SCOPE }
    public enum Source { RULE, MODEL, GUARD }
    /** 历史只能来自已授权会话；单轮诊断使用空历史。长度按 Java 字符数限制。 */
    public record Input(String message, String recentConversation) {
        public Input {
            if (message == null || message.isBlank() || message.length() > 2000)
                throw new IllegalArgumentException("问题须为 1～2000 字符");
            message = message.strip();
            recentConversation = Objects.requireNonNullElse(recentConversation, "");
            if (recentConversation.length() > 6000) throw new IllegalArgumentException("路由历史超过 6000 字符");
        }
    }
    /** Boolean 保留缺失字段的 null，避免把缺失自动当成 false。 */
    public record Proposal(Route route, Boolean ambiguous, Boolean multipleIndependentTasks) { }
    public record Decision(Route route, Source source, String reasonCode) {
        public Decision { Objects.requireNonNull(route); Objects.requireNonNull(source); Objects.requireNonNull(reasonCode); }
    }
    @FunctionalInterface public interface Classifier { Proposal classify(Input input); }
    private static final Set<String> HUMAN = Set.of("转人工", "我要转人工", "人工客服", "我要人工客服", "找真人客服");
    private static final Set<String> GREETINGS = Set.of("你好", "您好", "谢谢", "谢谢你", "再见");
    private static final Pattern END = Pattern.compile("[\\s。！？!?.]+$");
    private final Classifier classifier;
    public CustomerRouter(Classifier classifier) { this.classifier = Objects.requireNonNull(classifier); }

    /** 只剥离句尾标点并匹配完整命令：否定句、引用句和带业务问题的问候不会被快捷规则截断。 */
    public Decision route(Input input) {
        Objects.requireNonNull(input);
        String command = END.matcher(input.message()).replaceAll("");
        if (HUMAN.contains(command)) return new Decision(Route.HUMAN_SERVICE, Source.RULE, "EXPLICIT_HUMAN_COMMAND");
        if (GREETINGS.contains(command)) return new Decision(Route.SMALL_TALK, Source.RULE, "EXACT_SMALL_TALK");
        Proposal p;
        try { p = classifier.classify(input); }
        catch (RuntimeException ex) { return clarify("MODEL_UNAVAILABLE"); }
        if (p == null || p.route() == null || p.ambiguous() == null || p.multipleIndependentTasks() == null)
            return clarify("INVALID_MODEL_RESULT");
        if (p.ambiguous()) return clarify("AMBIGUOUS_INTENT");
        if (p.multipleIndependentTasks()) return clarify("MULTIPLE_INDEPENDENT_TASKS");
        return new Decision(p.route(), Source.MODEL, "MODEL_CLASSIFIED");
    }
    private Decision clarify(String reason) { return new Decision(Route.CLARIFY, Source.GUARD, reason); }
}
