package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.service.ChatService;
import com.ai.aiworkshop.service.EmbeddingService;
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

    public ChatController(ChatService chatService, EmbeddingService embeddingService) {
        this.chatService = chatService;
        this.embeddingService = embeddingService;
    }

    /**
     * 流式对话：浏览器用 EventSource 订阅，逐字显示。
     * 在 Servlet 容器下用 SseEmitter 把 Reactor 的 Flux 推给前端。
     *
     * 代码解读：
     * 1. @GetMapping 指定路径 /api/chat/stream，produces 声明返回格式为 text/event-stream（SSE 协议）。
     * 2. new SseEmitter(0L)：创建 SSE 发射器，参数 0 表示不限制超时时间，长连接一直保持。
     * 3. chatService.stream(message)：调用 ChatService 的流式方法，返回 Flux<String>，
     *    这是一个响应式流，会逐个 token（文字片段）推送数据。
     * 4. .subscribe(...)：订阅这个 Flux 流，传入三个回调：
     *    - onNext(token)：每收到一个 token，就通过 emitter.send(token) 推送给浏览器。
     *      如果发送时发生 IOException（比如客户端断开连接），调用 completeWithError 结束。
     *    - onError(throwable)：流中出现异常时，调用 emitter.completeWithError 通知浏览器出错。
     *    - onComplete()：流正常结束时，调用 emitter.complete 关闭 SSE 连接。
     * 5. return emitter：立即返回 SseEmitter 对象给 Spring，Spring 会用这个 emitter 异步推送数据。
     *
     * 整体效果：浏览器用 EventSource 连接到这个接口后，AI 的回答会像打字机一样逐字显示。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不限超时
        chatService.stream(message).subscribe(
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

    /** 非流式对话：POST JSON { "message": "..." } -> { "reply": "..." } */
    @PostMapping
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        return Map.of("reply", chatService.chat(body.get("message")));
    }

    /**
     * 验证第二个模型（Ollama embedding）是否可用。
     * 直接浏览器访问：/api/chat/embed?text=你好世界
     * 返回向量维度 + 前 5 个分量，方便确认 Ollama 已启动且模型已拉取。
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
