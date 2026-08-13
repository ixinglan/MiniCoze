package com.ai.aiworkshop.service;

import com.ai.aiworkshop.config.GuardrailAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final GuardrailAdvisor guardrailAdvisor;

    public ChatService(ChatClient chatClient, GuardrailAdvisor guardrailAdvisor) {
        this.chatClient = chatClient;
        this.guardrailAdvisor = guardrailAdvisor;
    }

    /**
     * 非流式：一次性返回完整回答。
     * 关键：用 .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
     * 把“会话 ID”塞进请求上下文，MessageChatMemoryAdvisor 据此读写对应会话的记忆。
     * 输出 PII 脱敏由 GuardrailAdvisor 的 adviseCall after 完成。
     */
    public String chat(String message, String conversationId) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(message)
                .call()
                .content();
    }

    /**
     * 流式：逐 token 返回（打字机效果），同样按 conversationId 隔离记忆。
     * 流式输出脱敏策略（用户选定：缓冲后整体脱敏）：
     * DeepSeek 流式块的 textContent 为 null（真实文本由 content() 内部聚合器提取），
     * 无法在 Advisor 层逐块/合并处理 —— 所以这里先把所有 token 收集成完整文本，
     * 整体过 maskPii() 后作为单条发出（代价：失去打字机效果，换来 PII 100% 脱敏）。
     */
    public Flux<String> stream(String message, String conversationId) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(message)
                .stream()
                .content()
                .collectList()
                .flatMapMany(tokens -> {
                    String full = String.join("", tokens);
                    return Flux.just(guardrailAdvisor.maskPii(full));
                });
    }
}
