package com.ai.aiworkshop.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 非流式：一次性返回完整回答。
     * 关键：用 .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
     * 把“会话 ID”塞进请求上下文，MessageChatMemoryAdvisor 据此读写对应会话的记忆。
     */
    public String chat(String message, String conversationId) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(message)
                .call()
                .content();
    }

    /** 流式：逐 token 返回（打字机效果），同样按 conversationId 隔离记忆 */
    public Flux<String> stream(String message, String conversationId) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(message)
                .stream()
                .content();
    }
}
