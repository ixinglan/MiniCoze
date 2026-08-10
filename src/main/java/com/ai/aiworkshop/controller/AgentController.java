package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.model.TaskTicket;
import com.ai.aiworkshop.tools.CreateTaskTool;
import com.ai.aiworkshop.tools.ToolCallRecorder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 4 控制器：工具调用（Tool Calling）演示端点。
 *
 * 核心思路：把“模型这次到底调了哪些工具”显式暴露给前端做可视化教学。
 * 由于 Spring AI 默认 call() 跑完内部 tool-execution loop 后，最终 ChatResponse 往往只剩最终文本、
 * 中间 toolCalls 未必保留，这里借助 {@link ToolCallRecorder}（ThreadLocal 在每个 @Tool 方法执行时记录）
 * 100% 可靠地拿到调用明细。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient agentClient;
    private final ToolCallRecorder recorder;
    private final CreateTaskTool createTaskTool;

    public AgentController(ChatClient agentClient,
                           ToolCallRecorder recorder,
                           CreateTaskTool createTaskTool) {
        this.agentClient = agentClient;
        this.recorder = recorder;
        this.createTaskTool = createTaskTool;
    }

    /**
     * 工具调用问答（非流式）。
     * 返回 { answer, toolCalls:[{name, params}] }，前端据此分步展示“调用了哪些工具”。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Map.of("error", "message 不能为空");
        }

        recorder.begin();   // 清空本次请求的调用记录
        try {
            ChatResponse response = agentClient.prompt()
                    .user(message)
                    .call()
                    .chatResponse();

            String answer = response.getResult().getOutput().getText();
            List<Map<String, Object>> toolCalls = recorder.collect();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("toolCalls", toolCalls);
            return result;
        } finally {
            recorder.clear();   // 释放 ThreadLocal，避免内存泄漏
        }
    }

    /** 查看已创建的工单（验证“创建工单”工具确实把数据落地了） */
    @GetMapping("/tasks")
    public List<TaskTicket> tasks() {
        return createTaskTool.getTickets();
    }
}
