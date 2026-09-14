package com.example.cloudcustomerservice.controller;

import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 保留第一至三章的单次聊天接口，返回 UTF-8 纯文本。
 * 虽然复用带记忆的客服客户端，但每次创建临时会话并在 finally 清理，避免陌生请求串话。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    /**
     * 复用客服 ChatClient 与同一个 ChatMemory，使临时会话在 finally 中能被正确清理。
     */
    public ChatController(@Qualifier("customerServiceChatClient") ChatClient customerServiceChatClient,
            @Qualifier("customerChatMemory") ChatMemory chatMemory) {
        this.chatClient = customerServiceChatClient;
        this.chatMemory = chatMemory;
    }

    /**
     * 调用客服客户端完成一次无状态问答；正常返回或抛错都会清理临时记忆。
     * @param message 查询参数中的客户消息
     * @return 模型返回的自然语言文本
     */
    @GetMapping(value = "/chat", produces = "text/plain;charset=UTF-8")
    public String chat(@RequestParam("message") String message) {
        // 保留第一至三章的无状态接口，不使用 Memory Advisor 的共享默认会话。
        String temporaryId = UUID.randomUUID().toString();
        try {
            return chatClient.prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, temporaryId))
                    .call().content();
        }
        finally {
            chatMemory.clear(temporaryId);
        }
    }
}
