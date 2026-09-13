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

@Service
public class CustomerConversationService {
    private static final Logger log = LoggerFactory.getLogger(CustomerConversationService.class);
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final CustomerIntentRecognizer intentRecognizer;
    private final CustomerOrderTools customerOrderTools;

    public CustomerConversationService(@Qualifier("customerServiceChatClient") ChatClient chatClient,
            @Qualifier("customerChatMemory") ChatMemory chatMemory,
            CustomerIntentRecognizer intentRecognizer, CustomerOrderTools customerOrderTools) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.intentRecognizer = intentRecognizer;
        this.customerOrderTools = customerOrderTools;
    }

    /** 学习版调用方须等待本轮完成，再发送同一会话的下一轮（包括清空操作）。 */
    public ChatTurnResponse chat(String conversationId, String message) {
        return chat(conversationId, null, message);
    }

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

    public void clearMemory(String conversationId) {
        clearMemory(conversationId, null);
    }

    public void clearMemory(String conversationId, Long currentUserId) {
        validateConversationId(conversationId);
        chatMemory.clear(memoryId(conversationId, currentUserId));
    }

    // 原无请求头接口保留访客会话；用户前缀含 /，外部 ID 不允许 /，避免命名空间碰撞。
    public static String memoryId(String conversationId, Long currentUserId) {
        if (currentUserId != null && currentUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid demo user id");
        }
        return currentUserId == null ? conversationId : "user/" + currentUserId + "/" + conversationId;
    }

    private void validateConversationId(String conversationId) {
        // 限制格式和长度；这不是用户身份认证或会话归属校验。
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversationId");
        }
    }
}
