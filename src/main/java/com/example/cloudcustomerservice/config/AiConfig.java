package com.example.cloudcustomerservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient customerServiceChatClient(ChatClient.Builder builder) {
        // Starter 自动配置 ChatModel 和 Builder，业务代码只负责构建客户端。
        // 第一章暂不设置客服身份；System Prompt 在下一章加入。
        return builder.build();
    }
}
