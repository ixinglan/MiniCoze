package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.service.ChatService;
import com.ai.aiworkshop.service.ConversationService;
import com.ai.aiworkshop.service.EmbeddingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final EmbeddingService embeddingService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, EmbeddingService embeddingService,
                          ConversationService conversationService) {
        this.chatService = chatService;
        this.embeddingService = embeddingService;
        this.conversationService = conversationService;
    }

    /**
     * 流式对话：浏览器用 EventSource 订阅，逐字显示。
     * conversationId 用于隔离不同会话的记忆（缺省 "default" 表示共享一个会话）。
     * 每次发送都会 touch 会话（刷新排序 + 首句设标题）。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(required = false, defaultValue = "default") String conversationId) {
        conversationService.touch(conversationId, message);
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
        conversationService.touch(conversationId, message);
        return Map.of("reply", chatService.chat(message, conversationId));
    }

    /** 会话列表（左侧栏） */
    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations() {
        return conversationService.listConversations();
    }

    /** 新建会话，返回新会话 ID（前端“新对话”按钮调用） */
    @PostMapping("/conversations")
    public Map<String, String> newConversation() {
        return Map.of("id", conversationService.createConversation());
    }

    /** 某个会话的历史消息（前端点开会话时拉取） */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam String conversationId) {
        return conversationService.getHistory(conversationId);
    }

    /** 彻底删除一个会话（手动点击删除时调用，真删 DB 数据） */
    @DeleteMapping("/conversation")
    public Map<String, Object> deleteConversation(@RequestParam String conversationId) {
        conversationService.deleteConversation(conversationId);
        return Map.of("ok", true, "deleted", conversationId);
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
