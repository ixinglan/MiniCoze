package com.ai.aiworkshop.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import com.ai.aiworkshop.mapper.ChatMemoryMapper;
import com.ai.aiworkshop.repository.MysqlChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * 阶段 1 新增：对话记忆。
     * MessageWindowChatMemory 是“滑动窗口”记忆：只保留最近 N 条消息，
     * 超过窗口的消息自动丢弃，避免上下文无限膨胀、token 爆炸、费用失控。
     * 底层用 MysqlChatMemoryRepository（MyBatis-Plus）落到 MySQL（mini_coze 库的 chat_memory 表），
     * 重启不丢、可跨实例共享；会话元数据在 conversation 表。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryMapper chatMemoryMapper) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new MysqlChatMemoryRepository(chatMemoryMapper))
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

    /**
     * 阶段 2 专用：无状态的结构化解析客户端。
     * 关键区别：不挂 MessageChatMemoryAdvisor —— 结构化解析是一次性的信息抽取，
     * 不该写进多轮对话记忆（chat_memory / chat_log），否则会污染历史、浪费 token。
     * 它和 chatClient 共用同一个 deepSeekChatModel，只是“身份/职责”不同。
     */
    @Bean
    public ChatClient parsingClient(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个严谨的结构化信息抽取助手，只输出符合要求的 JSON，不要包含任何解释性文字。")
                .build();
    }
}
