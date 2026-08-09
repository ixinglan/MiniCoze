package com.ai.aiworkshop.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * 阶段 1 新增：对话记忆。
     * MessageWindowChatMemory 是“滑动窗口”记忆：只保留最近 N 条消息，
     * 超过窗口的消息自动丢弃，避免上下文无限膨胀、token 爆炸、费用失控。
     * 底层用 InMemoryChatMemoryRepository 存在 JVM 内存里（重启即清空）。
     * 生产可用 JDBC / Cassandra / MongoDB 等持久化仓库替换它。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)   // 保留最近 20 条（含用户+助手），可按需调大
                .build();
    }

    /**
     * ChatClient 是对话统一入口。阶段 1 把记忆 Advisor 挂成 defaultAdvisor，
     * 之后每次调用都会自动：before 注入历史 → after/流式结束写入本轮问答。
     * 业务代码完全不用关心记忆的存取，只管 conversationId 即可。
     */
    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                 ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个友好、专业的 AI 助手，使用简体中文回答。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
