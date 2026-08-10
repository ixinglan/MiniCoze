package com.ai.aiworkshop.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import com.ai.aiworkshop.mapper.ChatMemoryMapper;
import com.ai.aiworkshop.repository.MysqlChatMemoryRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ai.aiworkshop.tools.CalculatorTool;
import com.ai.aiworkshop.tools.CreateTaskTool;
import com.ai.aiworkshop.tools.DateTimeTool;
import com.ai.aiworkshop.tools.RagQueryTool;
import com.ai.aiworkshop.tools.WeatherTool;

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
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel deepSeek,
                                 @Qualifier("ollamaChatModel") ChatModel ollama,
                                 ChatMemory chatMemory,
                                 @Value("${rag.offline.enabled:false}") boolean offline) {
        // 离线模式：生成改用本地 Ollama LLM（嵌入/向量库本就本地），实现全链路离线可用
        ChatModel gen = offline ? ollama : deepSeek;
        return ChatClient.builder(gen)
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

    /**
     * 阶段 3 专用：RAG 检索增强客户端。
     * 挂着两个 Advisor：
     *  1) QuestionAnswerAdvisor(vectorStore)：每次提问前，先从向量库检索最相关的文档片段，
     *     注入 prompt，让模型"基于资料回答"而不是凭空编造；similarityThreshold 过滤弱相关。
     *  2) MessageChatMemoryAdvisor(chatMemory)：RAG 对话也带多轮记忆（按 conversationId 隔离，
     *     与 /api/chat 共享同一记忆仓库但用不同 conversationId 互不干扰）。
     * 检索增强 + 记忆 叠加，得到"既懂你的私有资料、又记得上下文"的助手。
     */
    @Bean
    public ChatClient ragClient(@Qualifier("deepSeekChatModel") ChatModel deepSeek,
                               @Qualifier("ollamaChatModel") ChatModel ollama,
                               ChatMemory chatMemory,
                               VectorStore vectorStore,
                               @Value("${rag.offline.enabled:false}") boolean offline) {
        // 离线模式：生成改用本地 Ollama LLM；检索增强（向量库）与向量化（Ollama bge-m3）本就本地
        ChatModel gen = offline ? ollama : deepSeek;
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.6)   // 低于该相似度的片段不注入，避免噪声
                        .topK(4)                    // 每次最多取 4 个最相关片段
                        .build())
                .build();
        return ChatClient.builder(gen)
                .defaultSystem("你是一个基于知识库回答的 AI 助手。请仅依据提供的资料回答，"
                        + "若资料中没有相关信息，请如实说明'资料中未提及'，不要编造。使用简体中文。")
                .defaultAdvisors(qaAdvisor, MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 阶段 4 专用：工具调用（Tool Calling）客户端。
     * 把 5 个 @Tool 工具 Bean 通过 defaultTools 注册进去 —— Spring AI 会扫描每个 Bean 里的
     * @Tool 方法、自动生成 JSON Schema 交给模型。模型根据用户意图自主决定：调不调、调哪个、传什么参数，
     * Spring AI 在内部执行工具方法并把结果回灌模型，最终模型生成自然语言回答（内置 tool-execution loop）。
     *
     * 工具集合（详见 tools/ 包）：
     *  - DateTimeTool   获取当前时间
     *  - CalculatorTool 四则运算
     *  - WeatherTool    天气查询（模拟）
     *  - RagQueryTool   知识库检索（复用阶段 3 的 VectorStore，把 RAG 变成“模型主动调”的工具）
     *  - CreateTaskTool 创建工单（复用阶段 2 的 TaskTicket 结构化对象，体现“动手落地”）
     */
    @Bean
    public ChatClient agentClient(@Qualifier("deepSeekChatModel") ChatModel deepSeek,
                                  @Qualifier("ollamaChatModel") ChatModel ollama,
                                  DateTimeTool dateTimeTool,
                                  CalculatorTool calculatorTool,
                                  WeatherTool weatherTool,
                                  RagQueryTool ragQueryTool,
                                  CreateTaskTool createTaskTool,
                                  ChatMemory chatMemory,
                                  @Value("${rag.offline.enabled:false}") boolean offline) {
        // 离线模式：生成改用本地 Ollama LLM（工具本身是本地确定性逻辑，不受影响）
        ChatModel gen = offline ? ollama : deepSeek;
        // 阶段 4 补全：挂记忆 Advisor（复用 chat/rag 同一套 JDBC 记忆仓库，按 conversationId 隔离），
        // 让 agent 对话也能多轮记忆 + 持久化；工具调用与记忆可共存（ragClient 即同款叠加）。
        return ChatClient.builder(gen)
                .defaultSystem("你是一个具备工具调用能力的 AI 助手。你拥有多个工具：获取当前时间、四则运算计算器、"
                        + "查询天气(模拟)、检索本地知识库、创建工单。请根据用户的需求，自主判断是否需要调用工具、"
                        + "调用哪一个、传什么参数，并基于工具的返回结果用简体中文回答。不要编造工具不存在的信息。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(dateTimeTool, calculatorTool, weatherTool, ragQueryTool, createTaskTool)
                .build();
    }
}
