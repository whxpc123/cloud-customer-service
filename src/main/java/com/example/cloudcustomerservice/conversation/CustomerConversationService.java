package com.example.cloudcustomerservice.conversation;

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

    public CustomerConversationService(@Qualifier("customerServiceChatClient") ChatClient chatClient,
            @Qualifier("customerChatMemory") ChatMemory chatMemory,
            CustomerIntentRecognizer intentRecognizer) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.intentRecognizer = intentRecognizer;
    }

    /** 学习版调用方须等待本轮完成，再发送同一会话的下一轮（包括清空操作）。 */
    public ChatTurnResponse chat(String conversationId, String message) {
        validateConversationId(conversationId);
        if (message == null || message.isBlank() || message.length() > CustomerIntentRecognizer.MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must contain 1 to 4000 characters");
        }
        // 分类器只读旧历史，JSON 不写入客户记忆。随后客服 Advisor 保存原始用户消息与自然语言回答。
        var intent = intentRecognizer.recognize(conversationId, message);
        try {
            String answer = chatClient.prompt().user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call().content();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("Empty model reply");
            }
            return new ChatTurnResponse(conversationId, intent, answer);
        }
        catch (RuntimeException ex) {
            log.warn("Conversation reply failed, messageLength={}, errorType={}",
                    message.length(), ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model reply unavailable");
        }
    }

    public void clearMemory(String conversationId) {
        validateConversationId(conversationId);
        chatMemory.clear(conversationId);
    }

    private void validateConversationId(String conversationId) {
        // 限制格式和长度；这不是用户身份认证或会话归属校验。
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversationId");
        }
    }
}
