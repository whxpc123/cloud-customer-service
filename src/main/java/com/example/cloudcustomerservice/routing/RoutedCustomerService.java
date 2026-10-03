package com.example.cloudcustomerservice.routing;

import com.example.cloudcustomerservice.aftersale.*;
import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.rag.*;
import java.util.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.example.cloudcustomerservice.routing.CustomerRouter.*;

/** 统一入口固定分派表。身份/会话归属在控制器先检查，订单归属与证据规则仍由被选中的业务处理器检查。 */
@Service
@Profile("local & knowledge")
public class RoutedCustomerService {
    private final CustomerRouter router;
    private final AdvisorKnowledgeAnswerService knowledge;
    private final AfterSaleChatService afterSale;
    private final RoutingOrderHandler orders;
    private final ChatMemory memory;
    private final RoutingStatistics statistics;
    public RoutedCustomerService(CustomerRouter router, AdvisorKnowledgeAnswerService knowledge,
            AfterSaleChatService afterSale, RoutingOrderHandler orders,
            @Qualifier("customerChatMemory") ChatMemory memory, RoutingStatistics statistics) {
        this.router = router; this.knowledge = knowledge; this.afterSale = afterSale;
        this.orders = orders; this.memory = memory; this.statistics = statistics;
    }
    /** 外层统一追踪，内层保留原契约；最多一个业务结果非空，避免把事实、来源和状态压缩丢失。 */
    public record Response(String requestId, String conversationId, Decision decision, RoutingStatistics.Timing routing,
            String status, String answer, AdvisorKnowledgeAnswerResponse knowledge,
            RoutingOrderHandler.Result order, AfterSaleChatService.Result afterSale, String humanStatus) { }
    public record Diagnostic(Decision decision, RoutingStatistics.Timing routing) { }

    /** 单轮只分类，不读取会话、不调业务处理器，也不保存分类 JSON。 */
    public Diagnostic decide(String message) { return classify(new Input(message, "")); }
    private Diagnostic classify(Input input) {
        long start = System.nanoTime();
        Decision decision = router.route(input);
        return new Diagnostic(decision, statistics.record(decision, System.nanoTime() - start));
    }

    /**
     * 会话对象锁串行化发送/清空/过期。每轮将真实对话快照交给选中处理器的私有工作记忆，最后释放工作键。
     * 内层 Advisor 可照常维护本轮上下文；外层只保存实际给客户的一问一答，不与同一记忆键重复追加。
     */
    public Response answer(Actor actor, RoutingConversation conversation, String message) {
        // 验证发生在访问历史前；目前只支持本地样例租户，不能借旧订单仓库跨租户查询。
        if (!"tenant-yunshan".equals(actor.tenantId())) throw new IllegalArgumentException("仅支持本地教学租户");
        new Input(message, "");
        synchronized (conversation) {
            String requestId = UUID.randomUUID().toString();
            if (conversation.mode != RoutingConversation.Mode.BOT) {
                var result = new Response(requestId, conversation.id,
                        new Decision(Route.HUMAN_SERVICE, Source.GUARD, "HUMAN_SESSION_OWNED"),
                        new RoutingStatistics.Timing(0, 0), "HUMAN_CHANNEL_UNAVAILABLE",
                        "当前会话已由人工流程接管，机器人不再回答。本地教学版未连接坐席通道，消息未转发。",
                        null, null, null, conversation.mode.name());
                conversation.append(message, result.answer()); return result;
            }
            var diagnosis = classify(new Input(message, conversation.routingHistory()));
            Response response;
            try { response = dispatch(actor, conversation, message, requestId, diagnosis); }
            catch (RuntimeException ex) {
                // 处理器异常不改走另一条流程，不泄露异常正文，也不伪造查到的事实。
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("[ROUTING HANDLER] requestId={} route={} errorType={}",
                        requestId, diagnosis.decision().route(), ex.getClass().getSimpleName());
                response = simple(requestId, conversation.id, diagnosis, "HANDLER_UNAVAILABLE",
                        "所选业务服务暂时不可用，本轮未取得有效结果，请稍后重试。", null);
            }
            conversation.append(message, response.answer());
            return response;
        }
    }

    /** switch 是唯一执行目标映射；模型无法指定 Bean、工具、方法或网址。 */
    private Response dispatch(Actor actor, RoutingConversation c, String message, String requestId, Diagnostic d) {
        return switch (d.decision().route()) {
            case SMALL_TALK -> simple(requestId, c.id, d, "FIXED_REPLY", greeting(message), null);
            case OUT_OF_SCOPE -> simple(requestId, c.id, d, "OUT_OF_SCOPE",
                    "这里可以咨询云杉商城政策、查询本地样例订单和进行只读售后预检查。请提供商城相关问题。", null);
            case HUMAN_SERVICE -> simple(requestId, c.id, d, "HUMAN_NOT_CONNECTED",
                    "已识别您希望由人工接待。当前教学项目尚未接入人工坐席，未创建排队或转接，请使用您已知的官方人工渠道。", "NOT_CONNECTED");
            case CLARIFY -> simple(requestId, c.id, d, d.decision().reasonCode(), clarify(d.decision().reasonCode()), null);
            case ORDER_QUERY -> {
                var result = orders.answer(actor, message, c.history());
                yield new Response(requestId, c.id, d.decision(), d.routing(), result.status(), result.answer(), null, result, null, null);
            }
            case KNOWLEDGE -> {
                String key = AdvisorKnowledgeAnswerService.memoryId(actor.tenantId(), c.workId, actor.userId());
                try {
                    memory.clear(key); memory.add(key, c.history());
                    var r = knowledge.answer(actor.tenantId(), c.workId, actor.userId(), message);
                    // 保留来源和完整检索轨迹，响应中的会话号替换为公开 ID，工作键不能从旧实验接口访问。
                    var result = new AdvisorKnowledgeAnswerResponse(r.requestId(), c.id, r.retrievalQuery(), r.status(), r.answer(),
                            r.references(), r.transformation(), r.expansion(), r.reranking());
                    yield new Response(requestId, c.id, d.decision(), d.routing(), result.status().name(), result.answer(), result, null, null, null);
                } finally { memory.clear(key); }
            }
            case AFTER_SALE_PRECHECK -> {
                String key = "routing-after-sale/" + c.workId;
                try {
                    memory.clear(key); memory.add(key, c.history());
                    var result = afterSale.answer(actor, key, message);
                    yield new Response(requestId, c.id, d.decision(), d.routing(), result.status(), result.answer(), null, null, result, null);
                } finally { memory.clear(key); }
            }
        };
    }
    private Response simple(String requestId, String id, Diagnostic d, String status, String answer, String human) {
        return new Response(requestId, id, d.decision(), d.routing(), status, answer, null, null, null, human);
    }
    private String greeting(String message) {
        if (message.contains("谢谢")) return "不客气，还有商城相关的问题可以继续告诉我。";
        if (message.contains("再见")) return "再见，祝您生活愉快。";
        return "您好，我是云杉商城客服。您想咨询政策、查询订单，还是进行售后预检查？";
    }
    /** 系统故障不责怪客户表达；多任务不偷偷选择其中一项。 */
    private String clarify(String reason) {
        return switch (reason) {
            case "MODEL_UNAVAILABLE" -> "自动分流服务暂时不可用，本轮没有调用后续业务。请稍后重试。";
            case "INVALID_MODEL_RESULT" -> "暂时无法可靠分流，本轮没有调用后续业务。请稍后重试。";
            case "MULTIPLE_INDEPENDENT_TASKS" -> "您提到了多个需要不同流程处理的事项。请先选择一项：咨询政策、查询订单，或售后预检查。";
            default -> "请补充您希望解决的事情：咨询一般规则、查询订单状态，还是检查某笔订单能否退货？";
        };
    }
    /** 只清除此对象的实际对话；不会读取或清空其他会话、旧章节的历史。 */
    public void clear(RoutingConversation conversation) { synchronized (conversation) { conversation.clear(); } }
}
