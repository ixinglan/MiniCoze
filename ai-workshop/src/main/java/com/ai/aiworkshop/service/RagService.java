package com.ai.aiworkshop.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 阶段 3 RAG 问答服务。
 * 封装 ragClient 的流式 / 非流式调用。conversationId 通过 Advisor 参数传入，
 * 让 RAG 对话也按会话隔离记忆（与 /api/chat 共享仓库、用不同 id 互不干扰）。
 */
@Service
public class RagService {

    private final ChatClient ragClient;

    public RagService(@Qualifier("ragClient") ChatClient ragClient) {
        this.ragClient = ragClient;
    }

    /** 流式问答：逐字返回，供 SSE 推给前端 */
    public Flux<String> stream(String message, String conversationId) {
        return ragClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    /** 非流式问答：一次性返回完整文本 */
    public String chat(String message, String conversationId) {
        return ragClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
