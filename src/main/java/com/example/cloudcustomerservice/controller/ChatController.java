package com.example.cloudcustomerservice.controller;

import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatController(@Qualifier("customerServiceChatClient") ChatClient customerServiceChatClient,
            @Qualifier("customerChatMemory") ChatMemory chatMemory) {
        this.chatClient = customerServiceChatClient;
        this.chatMemory = chatMemory;
    }

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
