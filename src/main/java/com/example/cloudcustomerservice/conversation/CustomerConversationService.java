package com.example.cloudcustomerservice.conversation;

import java.util.HashMap;
import java.util.Map;
import com.example.cloudcustomerservice.tool.CustomerOrderTools;
import com.example.cloudcustomerservice.tool.OrderToolTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.example.cloudcustomerservice.intent.CustomerIntentRecognizer;

/**
 * 组织一轮客服对话：校验输入 → 读取历史识别意图 → 携带工具生成回答 → 汇总结果。
 * 分类输出不写入聊天历史，避免模型在后续轮次把内部 JSON 当成客户说过的话。
 * 记忆键组合用户和会话；同一会话的发送与清空由调用方串行执行，本类没有会话级锁。
 */
@Service
public class CustomerConversationService {
    private static final Logger log = LoggerFactory.getLogger(CustomerConversationService.class);
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final CustomerIntentRecognizer intentRecognizer;
    private final CustomerOrderTools customerOrderTools;

    /**
     * 注入客服客户端、共享记忆、分类器与订单工具；通过限定名称避免混淆三类 ChatClient。
     */
    public CustomerConversationService(@Qualifier("customerServiceChatClient") ChatClient chatClient,
            @Qualifier("customerChatMemory") ChatMemory chatMemory,
            CustomerIntentRecognizer intentRecognizer, CustomerOrderTools customerOrderTools) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.intentRecognizer = intentRecognizer;
        this.customerOrderTools = customerOrderTools;
    }

    /**
     * 学习版调用方须等待本轮完成，再发送同一会话的下一轮（包括清空操作）。
     *
     *
     * 兼容没有演示身份参数的调用，使用访客命名空间；订单工具会要求选择身份。
     */
    public ChatTurnResponse chat(String conversationId, String message) {
        return chat(conversationId, null, message);
    }

    /**
     * 完成带身份的一轮请求。身份与调用轨迹经 ToolContext 传给 Java 工具，不由模型指定。
     * @param conversationId 外部会话键，限制为字母、数字、下划线或短横线
     * @param currentUserId 演示身份，null 表示访客；非空必须为正数
     * @param message 本轮客户原文，长度上限 4000
     * @return 意图、答复及实际工具查询快照；答复调用失败则抛出 HTTP 502
     */
    public ChatTurnResponse chat(String conversationId, Long currentUserId, String message) {
        validateConversationId(conversationId);
        String memoryId = memoryId(conversationId, currentUserId);
        if (message == null || message.isBlank() || message.length() > CustomerIntentRecognizer.MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must contain 1 to 4000 characters");
        }
        // 分类器只读旧历史，JSON 不写入客户记忆。随后客服 Advisor 保存原始用户消息与自然语言回答。
        var intent = intentRecognizer.recognize(memoryId, message);
        var trace = new OrderToolTrace();
        Map<String, Object> context = new HashMap<>();
        if (currentUserId != null) context.put("currentUserId", currentUserId);
        context.put("conversationId", conversationId);
        context.put(OrderToolTrace.CONTEXT_KEY, trace);
        try {
            String answer = chatClient.prompt().user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryId))
                    .tools(customerOrderTools)
                    .toolContext(context)
                    .call().content();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("Empty model reply");
            }
            return new ChatTurnResponse(conversationId, intent, answer, trace.results());
        }
        catch (RuntimeException ex) {
            log.warn("Conversation reply failed, messageLength={}, errorType={}",
                    message.length(), ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model reply unavailable");
        }
    }

    /**
     * 访客版清空入口，复用带身份重载的校验与清理逻辑。
     */
    public void clearMemory(String conversationId) {
        clearMemory(conversationId, null);
    }

    /**
     * 根据与发送消息完全相同的规则计算内部记忆键，再清除该窗口。
     */
    public void clearMemory(String conversationId, Long currentUserId) {
        validateConversationId(conversationId);
        chatMemory.clear(memoryId(conversationId, currentUserId));
    }

    // 原无请求头接口保留访客会话；用户前缀含 /，外部 ID 不允许 /，避免命名空间碰撞。
    /**
     * 生成用户隔离的内部会话键；用户前缀中的斜杠不会与合法外部 ID 冲突。
     * 此方法只做命名空间隔离，不验证客户端是否有权声称该演示身份。
     */
    public static String memoryId(String conversationId, Long currentUserId) {
        if (currentUserId != null && currentUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid demo user id");
        }
        return currentUserId == null ? conversationId : "user/" + currentUserId + "/" + conversationId;
    }

    /**
     * 拒绝空 ID、超长 ID 和路径分隔符，避免不受控的记忆键及命名空间碰撞。
     */
    private void validateConversationId(String conversationId) {
        // 限制格式和长度；这不是用户身份认证或会话归属校验。
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversationId");
        }
    }
}
