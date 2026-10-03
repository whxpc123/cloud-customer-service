package com.example.cloudcustomerservice.routing;

import java.util.*;
import org.springframework.ai.chat.messages.*;

/** 由服务器 Session 持有的机器人会话；不接受浏览器上传的身份、历史或接管状态。不是完整聊天档案。 */
public final class RoutingConversation {
    enum Mode { BOT, HUMAN_WAITING, HUMAN_ACTIVE }
    // 外部 UUID 只用于归属查询；内部工作记忆键独立生成且不发送给浏览器。
    final String id = UUID.randomUUID().toString();
    final String workId = "routing-" + UUID.randomUUID();
    Mode mode = Mode.BOT;
    private final List<Message> messages = new ArrayList<>();

    /** 调用者持有本会话对象锁，读取只得到不可变快照。 */
    List<Message> history() { return List.copyOf(messages); }
    /** 统一入口只记真实的一问一答，最多十轮；分类 JSON、检索增强和工具中间消息不入此窗口。 */
    void append(String question, String answer) {
        messages.add(new UserMessage(question));
        messages.add(new AssistantMessage(answer));
        if (messages.size() > 20) messages.subList(0, messages.size() - 20).clear();
    }
    /** 清空上下文不应解除真实人工接管；当前教学环境没有创建人工接管的 API。 */
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
