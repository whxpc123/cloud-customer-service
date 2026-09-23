package com.example.cloudcustomerservice.rag.query;

import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * 第十一章转换保护层：仅采用模型返回的文本，保留服务端 history/context 和过滤器。
 * 结构校验不能证明语义正确；数字、订单号和软件否定状态仅做有限防错，仍需要固定评测集。
 */
public final class SafeQueryTransformer implements QueryTransformer {
    public static final String CLARIFY = "__NEEDS_CLARIFICATION__";
    private static final Logger log = LoggerFactory.getLogger(SafeQueryTransformer.class);
    private static final Pattern NUMBERS = Pattern.compile("[A-Za-z]*\\d+(?:[.-]\\d+)*");
    private static final Pattern ORDERS = Pattern.compile("A\\d{5}");
    // 在线验收曾把助手提到的“七日”补进软件追问；按等价组检查用户是否真正提出过限定。
    private static final List<Pattern> POLICY_QUALIFIERS = List.of(
            Pattern.compile("七日|七天|7日|7天"), Pattern.compile("无理由"));
    private final QueryTransformer delegate;
    private final String stage;

    /** 包装一个官方转换组件，stage 只表示逻辑阶段，不等于服务商请求次数。 */
    public SafeQueryTransformer(QueryTransformer delegate, String stage) {
        this.delegate = delegate;
        this.stage = stage;
    }

    /** 有限的显式指代检测用于无上下文/故障兜底，未声称覆盖全部自然语言省略表达。 */
    public static boolean referential(String text) {
        String value = text.strip().replaceAll("[？?。！!\\s]", "");
        return value.length() < 60 && (value.matches("^(那|它|这个|这件|刚才|第二个|然后|超过以后).*")
                || value.matches("(还)?(能|可以)退吗"));
    }

    /** RAA 1.1.2 的 history 包含系统消息和当前问题；只在接入 Advisor 的边界移除末尾当前 UserMessage。 */
    public static Query priorConversation(Query query) {
        var history = new ArrayList<>(query.history());
        if (!history.isEmpty()) {
            Message last = history.get(history.size() - 1);
            if (last.getMessageType() == MessageType.USER && Objects.equals(last.getText(), query.text())) {
                history.remove(history.size() - 1);
            }
        }
        return query.mutate().history(history).build();
    }

    /** 从有限窗口挑选用户/助手消息；系统提示、工具输出、权限 Context 均不发送给转换模型。 */
    @Override
    public Query transform(Query query) {
        long started = System.nanoTime();
        List<Message> history = query.history().stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT).toList();
        if (history.size() > 20) history = history.subList(history.size() - 20, history.size());
        Query input = query.mutate().history(List.copyOf(history)).context(Map.copyOf(query.context())).build();
        QueryTransformationTrace trace = input.context().get(QueryTransformationTrace.KEY) instanceof QueryTransformationTrace t ? t : null;
        String status = "UNCHANGED";
        String text = input.text();
        boolean clarification = trace != null && trace.clarificationRequired();
        try {
            if (clarification) status = "SKIPPED_CLARIFICATION";
            else if (stage.equals("COMPRESSION") && history.stream().noneMatch(m -> m.getMessageType() == MessageType.USER)) {
                clarification = referential(text);
                status = clarification ? "NEEDS_CLARIFICATION" : "SKIPPED_NO_HISTORY";
            } else {
                Query output = delegate.transform(input);
                if (output == null || output.text() == null || output.text().isBlank()) status = "FALLBACK_INVALID";
                else if (output.text().strip().equals(CLARIFY)) { status = "NEEDS_CLARIFICATION"; clarification = true; }
                else if (output.text().length() > 2000 || !preservesFacts(input, output.text())
                        || !output.text().equals(input.text()) && !output.text().matches("(?s).*(谁|哪|如何|是否|什么|几|多少|多久|怎么|能否|可否|吗|么|？|\\?).*")) status = "FALLBACK_INVALID";
                else {
                    text = output.text().strip();
                    status = text.equals(input.text()) ? "UNCHANGED" : "TRANSFORMED";
                }
            }
        } catch (RuntimeException ex) {
            // 不打印异常正文，服务商错误可能包含请求内容；回退到原 Query，不自动再次收费重试。
            status = "FALLBACK_ERROR";
        }
        if ((status.startsWith("FALLBACK") || status.equals("UNCHANGED")) && referential(text)) clarification = true;
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (trace != null) trace.record(text, history.size(), stage, status, elapsed, clarification);
        log.info("[QUERY TRANSFORM] requestId={} stage={} status={} historyMessages={} originalLength={} transformedLength={} durationMs={}",
                input.context().get("customer.requestId"), stage, status, history.size(), input.text().length(), text.length(), elapsed);
        // 绝不采用 delegate.context()/history()，避免恶意或错误实现替换租户过滤器。
        return input.mutate().text(text).build();
    }

    /** 新数字不能来自助手猜测；明显的“未激活→已激活”反转被拦截，不将此规则称作完整事实校验。 */
    private boolean preservesFacts(Query input, String output) {
        String userText = input.history().stream().filter(m -> m.getMessageType() == MessageType.USER)
                .map(Message::getText).reduce("", (a,b) -> a + "\n" + b) + "\n" + input.text();
        for (Pattern qualifier : POLICY_QUALIFIERS) {
            if (qualifier.matcher(output).find() && !qualifier.matcher(userText).find()) return false;
        }
        if (!tokens(NUMBERS, userText).containsAll(tokens(NUMBERS, output))) return false;
        Set<String> ids = tokens(ORDERS, userText);
        if (referential(input.text()) && ids.size() == 1 && !tokens(ORDERS, output).containsAll(ids)) return false;
        String latest = input.text().contains("激活") ? input.text() : input.history().stream()
                .filter(m -> m.getMessageType() == MessageType.USER && m.getText().contains("激活"))
                .map(Message::getText).reduce((a,b) -> b).orElse("");
        boolean negative = latest.matches("(?s).*(未激活|未曾激活|没有激活|尚未激活).*");
        return !negative || !output.matches("(?s).*(已经激活|已激活).*");
    }

    /** 提取数字或订单号集合，供有限事实保留检查使用。 */
    private Set<String> tokens(Pattern pattern, String value) {
        Set<String> result = new HashSet<>();
        var matcher = pattern.matcher(value);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
}
