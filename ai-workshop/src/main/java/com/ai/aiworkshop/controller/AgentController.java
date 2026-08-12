package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.entity.TaskTicketDO;
import com.ai.aiworkshop.mapper.TaskTicketMapper;
import com.ai.aiworkshop.service.ChatLogService;
import com.ai.aiworkshop.service.ConversationService;
import com.ai.aiworkshop.tools.CreateTaskTool;
import com.ai.aiworkshop.tools.ToolCallRecorder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 阶段 4 控制器：工具调用（Tool Calling）演示端点。
 *
 * 核心思路：把“模型这次到底调了哪些工具”显式暴露给前端做可视化教学。
 * 由于 Spring AI 默认 call() 跑完内部 tool-execution loop 后，最终 ChatResponse 往往只剩最终文本、
 * 中间 toolCalls 未必保留，这里借助 {@link ToolCallRecorder}（ThreadLocal 在每个 @Tool 方法执行时记录）
 * 100% 可靠地拿到调用明细。
 *
 * 阶段 4 补全（持久化）：
 *  - 对话记录落库：复用 chat/rag 同一套 ConversationService + ChatLogService（type='agent'），
 *    并通过 ChatMemory.CONVERSATION_ID 把多轮记忆隔离到对应会话（agentClient 已挂记忆 Advisor）。
 *  - 工单落库：/api/agent/tasks 改从 task_ticket 表读（CreateTaskTool 建单即写库，不再用内存列表）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient agentClient;
    private final ToolCallRecorder recorder;
    private final CreateTaskTool createTaskTool;
    private final ConversationService conversationService;
    private final ChatLogService chatLogService;
    private final TaskTicketMapper taskTicketMapper;
    private final ObjectMapper objectMapper;

    public AgentController(ChatClient agentClient,
                           ToolCallRecorder recorder,
                           CreateTaskTool createTaskTool,
                           ConversationService conversationService,
                           ChatLogService chatLogService,
                           TaskTicketMapper taskTicketMapper,
                           ObjectMapper objectMapper) {
        this.agentClient = agentClient;
        this.recorder = recorder;
        this.createTaskTool = createTaskTool;
        this.conversationService = conversationService;
        this.chatLogService = chatLogService;
        this.taskTicketMapper = taskTicketMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 工具调用问答（非流式）。
     * 入参 body: { message, conversationId? }。
     * 返回 { answer, toolCalls:[{name, params}], conversationId }，前端据此分步展示“调用了哪些工具”。
     *
     * 持久化动作：
     *  1) conversationId 缺省则新建 type='agent' 会话；
     *  2) touch 会话（刷新排序 + 首句设标题）；
     *  3) 本轮 user/assistant 各写一条 chat_log（完整历史，不被窗口裁剪）；
     *  4) 通过 ChatMemory.CONVERSATION_ID 让 agentClient 的记忆 Advisor 读写对应会话。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Map.of("error", "message 不能为空");
        }
        final String cid;
        String provided = body.get("conversationId");
        if (provided == null || provided.isBlank()) {
            cid = conversationService.createConversation("agent");
        } else {
            cid = provided;
        }

        conversationService.touch(cid, message);
        chatLogService.append(cid, "user", message);

        recorder.begin(cid);   // 重置调用记录 + 携带会话 ID（供工具落库关联）
        try {
            ChatResponse response = agentClient.prompt()
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                    .user(message)
                    .call()
                    .chatResponse();

            String answer = response.getResult().getOutput().getText();
            List<Map<String, Object>> toolCalls = recorder.collect();
            chatLogService.append(cid, "assistant", answer);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("toolCalls", toolCalls);
            result.put("conversationId", cid);
            return result;
        } finally {
            recorder.clear();   // 释放 ThreadLocal，避免内存泄漏
        }
    }

    /** 查看已创建的工单（从 task_ticket 表读，按创建时间倒序；验证“创建工单”确实落库了） */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        List<TaskTicketDO> list = taskTicketMapper.selectList(
                Wrappers.<TaskTicketDO>query().orderByDesc("created_at"));
        return list.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("title", d.getTitle());
            m.put("category", d.getCategory());
            m.put("priority", d.getPriority());
            m.put("description", d.getDescription());
            m.put("status", d.getStatus());
            m.put("source", d.getSource());
            m.put("conversationId", d.getConversationId());
            m.put("createdAt", d.getCreatedAt());
            // tags 在库里是 JSON 字符串，解析回列表方便前端展示
            try {
                m.put("tags", d.getTags() == null ? List.of()
                        : objectMapper.readValue(d.getTags(), List.class));
            } catch (Exception e) {
                m.put("tags", List.of());
            }
            return m;
        }).collect(Collectors.toList());
    }
}
