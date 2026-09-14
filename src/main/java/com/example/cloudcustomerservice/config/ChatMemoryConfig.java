package com.example.cloudcustomerservice.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第四章的消息窗口配置：以会话键保存有限的上下文，供客服回答与意图识别共用。
 * 这里使用进程内存，适合观察原理；重启不会恢复历史，也不支持多实例共享。
 */
@Configuration
public class ChatMemoryConfig {
    /**
     * 构建最多保留 20 条消息的窗口；一轮通常含用户和助手两条，不是保留 20 轮。
     * 使用相同 memoryId 的调用共享窗口，清空操作也必须使用该内部键。
     */
    @Bean("customerChatMemory")
    public ChatMemory customerChatMemory() {
        // 20 条消息，不是 20 轮；进程重启后丢失，不是完整聊天档案。
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
