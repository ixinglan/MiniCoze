package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.service.ChatService;
import com.ai.aiworkshop.service.EmbeddingService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final EmbeddingService embeddingService;
    private final ChatMemory chatMemory;

    public ChatController(ChatService chatService, EmbeddingService embeddingService, ChatMemory chatMemory) {
        this.chatService = chatService;
        this.embeddingService = embeddingService;
        this.chatMemory = chatMemory;
    }

    /**
     * 流式对话：浏览器用 EventSource 订阅，逐字显示。
     * conversationId 用于隔离不同会话的记忆（缺省 "default" 表示共享一个会话）。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(required = false, defaultValue = "default") String conversationId) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不限超时
        chatService.stream(message, conversationId).subscribe(
                token -> {
                    try {
                        emitter.send(token);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
        return emitter;
    }

    /** 非流式对话：POST JSON { "message": "...", "conversationId": "..." } -> { "reply": "..." } */
    @PostMapping
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String conversationId = body.getOrDefault("conversationId", "default");
        return Map.of("reply", chatService.chat(message, conversationId));
    }

    /** 清空某个会话的记忆（前端“新对话”按钮调用），让助手“忘记”之前的内容 */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestParam(required = false, defaultValue = "default") String conversationId) {
        chatMemory.clear(conversationId);
        return Map.of("ok", true, "cleared", conversationId);
    }

    /**
     * 验证第二个模型（Ollama embedding）是否可用。
     * 直接浏览器访问：/api/chat/embed?text=你好世界
     */
    @GetMapping("/embed")
    public Map<String, Object> embed(@RequestParam String text) {
        try {
            float[] vec = embeddingService.embed(text);
            return Map.of(
                    "ok", true,
                    "dimensions", vec.length,
                    "sample", Arrays.copyOfRange(vec, 0, Math.min(5, vec.length))
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "error", "Ollama 未启动或未拉取模型 bge-m3？详情：" + e.getMessage()
            );
        }
    }
}
