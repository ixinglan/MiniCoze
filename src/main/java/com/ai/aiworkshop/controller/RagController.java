package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.service.RagService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 阶段 3 RAG 检索增强问答接口。
 * 与 /api/chat 平行的另一条对话通道：问题会先经向量库检索相关资料，再交给模型回答。
 * 默认 conversationId = "rag"，避免和常规聊天（default）混在同一记忆空间。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 流式 RAG 问答：浏览器用 EventSource 订阅，逐字显示 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(required = false, defaultValue = "rag") String conversationId) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不限超时
        ragService.stream(message, conversationId).subscribe(
                token -> {
                    try {
                        emitter.send(token);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                () -> {
                    try {
                        emitter.send("[DONE]");   // 发送结束标记，前端据此正常收尾
                    } catch (IOException ignored) { }
                    emitter.complete();
                }
        );
        return emitter;
    }

    /** 非流式 RAG 问答：POST JSON { "message": "...", "conversationId": "..." } -> { "reply": "..." } */
    @PostMapping
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String conversationId = body.getOrDefault("conversationId", "rag");
        return Map.of("reply", ragService.chat(message, conversationId));
    }
}
