package com.ai.aiworkshop.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * ChatClient 是与大模型对话的统一入口（屏蔽不同厂商差异）。
     * DashScopeChatModel 已由 spring-ai-alibaba-starter-dashscope 自动配置好，
     * 只需把 ChatModel 注入进来即可。
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个友好、专业的 AI 助手，使用简体中文回答。")
                .build();
    }
}
