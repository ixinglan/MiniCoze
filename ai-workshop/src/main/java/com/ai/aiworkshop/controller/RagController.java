package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.service.ChatLogService;
import com.ai.aiworkshop.service.ConversationService;
import com.ai.aiworkshop.service.RagService;
import org.springframework.http.MediaType;
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
 * 历史持久化与 /api/chat 完全一致：touch 会话排序/标题 + 双写 chat_log（完整日志），
 * 因此刷新、切换会话都不会丢 RAG 历史，左侧会话列表也能统一管理。
 * 默认 conversationId = "rag"，避免和常规聊天（default）混在同一记忆空间。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final ConversationService conversationService;
    private final ChatLogService chatLogService;

    public RagController(RagService ragService, ConversationService conversationService,
                         ChatLogService chatLogService) {
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.chatLogService = chatLogService;
    }

    /** 流式 RAG 问答：浏览器用 EventSource 订阅，逐字显示。每轮问答写入 chat_log（完整日志） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(required = false, defaultValue = "rag") String conversationId) {
        conversationService.touch(conversationId, message);
        chatLogService.append(conversationId, "user", message);   // 记完整日志：用户侧
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不限超时
        StringBuilder fullReply = new StringBuilder();
        ragService.stream(message, conversationId).subscribe(
                token -> {
                    fullReply.append(token);
                    try {
                        emitter.send(token);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                () -> {
                    // 流结束：把完整助手回复也记入 chat_log（前端历史从此表读，不随窗口裁剪丢失）
                    chatLogService.append(conversationId, "assistant", fullReply.toString());
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
        conversationService.touch(conversationId, message);
        chatLogService.append(conversationId, "user", message);   // 记完整日志：用户侧
        String reply = ragService.chat(message, conversationId);
        chatLogService.append(conversationId, "assistant", reply); // 记完整日志：助手侧
        return Map.of("reply", reply);
    }
}
