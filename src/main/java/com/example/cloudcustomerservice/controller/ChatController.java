package com.example.cloudcustomerservice.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient customerServiceChatClient) {
        this.chatClient = customerServiceChatClient;
    }

    @GetMapping(value = "/chat", produces = "text/plain;charset=UTF-8")
    public String chat(@RequestParam("message") String message) {
        return chatClient
                .prompt()
                .user(message)
                .call()
                .content();
    }
}
