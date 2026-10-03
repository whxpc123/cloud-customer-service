package com.example.cloudcustomerservice.routing;

import java.util.*;
import org.springframework.ai.chat.messages.*;

/** 单次生成使用的临时窗口；由已授权的正式消息构造，不保存任何接待状态，生命周期仅限本轮。 */
public final class RoutingConversation {
    // 外部 UUID 只用于归属查询；内部工作记忆键独立生成且不发送给浏览器。
    final String id;
    final String workId = "routing-" + UUID.randomUUID();
    private final List<Message> messages = new ArrayList<>();
    /** 旧离线测试可构造独立窗口；实际入口必须使用数据库会话 ID 与正式历史。 */
    public RoutingConversation() { this(UUID.randomUUID().toString(), List.of()); }
    public RoutingConversation(String id, List<Message> history) {
        this.id = UUID.fromString(id).toString();
        messages.addAll(history.subList(Math.max(0, history.size()-20), history.size()));
    }

    /** 外部会话号只作追踪使用，不能代替权限检查。 */
    public String id() { return id; }
    /** 读取只得到不可变快照，窗口仅属于当前生成过程。 */
    public List<Message> history() { return List.copyOf(messages); }
    /** 统一入口只记真实的一问一答，最多十轮；分类 JSON、检索增强和工具中间消息不入此窗口。 */
    void append(String question, String answer) {
        messages.add(new UserMessage(question));
        messages.add(new AssistantMessage(answer));
        if (messages.size() > 20) messages.subList(0, messages.size() - 20).clear();
    }
    /** 仅释放当前候选生成窗口；数据库正式历史与接待状态不受影响。 */
    void clear() { messages.clear(); }
    /** 从最新消息倒推有限上下文；只使用完整消息，过长的早期上下文留在本地窗口中。 */
    String routingHistory() {
        var lines = new LinkedList<String>();
        int length = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            String line = "[" + m.getMessageType() + "] " + m.getText() + "\n";
            if (length + line.length() > 6000) break;
            lines.addFirst(line); length += line.length();
        }
        return String.join("", lines);
    }
}
