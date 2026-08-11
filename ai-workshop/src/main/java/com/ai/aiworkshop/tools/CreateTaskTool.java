package com.ai.aiworkshop.tools;

import com.ai.aiworkshop.entity.TaskTicketDO;
import com.ai.aiworkshop.mapper.TaskTicketMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具 5：创建工单（业务闭环的另一半 —— 复用阶段 2 的 TaskTicket 结构化对象）。
 *
 * 价值：阶段 2 我们学会了“把自然语言解析成结构化对象”，阶段 4 让模型在理解用户意图后，
 * 主动把一件事“落地”成一个工单并登记。这正是 Agent 工作台“理解需求 → 产出可执行动作”的闭环：
 * 模型不再只是回答问题，而是能“动手”创建记录。
 *
 * 阶段 4 补全：落库。建单即写入 task_ticket 表（不再用内存列表，重启不丢）。
 *  - status 默认 open；source 默认 agent；conversation_id 取自 ToolCallRecorder 的 ThreadLocal
 *    （工具方法拿不到请求级 conversationId，由 Controller 在 begin 时传入），关联“哪个对话产生了这张工单”。
 *  - tags 字段在库中存 JSON 字符串；本工具当前的 @Tool 参数未捕获 tags，故落空数组。
 */
@Service
public class CreateTaskTool {

    private final ToolCallRecorder recorder;
    private final TaskTicketMapper taskTicketMapper;
    private final ObjectMapper objectMapper;

    public CreateTaskTool(ToolCallRecorder recorder, TaskTicketMapper taskTicketMapper, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.taskTicketMapper = taskTicketMapper;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "创建一个新的工单/任务并登记。当用户希望把一件事记录成待办工单"
            + "（含标题、分类、优先级、描述）时调用，例如“帮我建个工单：上线前做一次安全扫描，优先级 P0”。")
    public String createTask(
            @ToolParam(description = "工单标题，一句话概括，不超过 20 字") String title,
            @ToolParam(description = "分类，只能从 [开发, 运维, 设计, 文档, 测试, 其他] 中选一个") String category,
            @ToolParam(description = "优先级，只能从 [P0(最高), P1, P2, P3(最低)] 中选一个") String priority,
            @ToolParam(description = "需求详细描述，用 1 到 3 句话把事情说清楚") String description) {
        recorder.record("createTask", Map.of("title", title, "category", category, "priority", priority));

        TaskTicketDO t = new TaskTicketDO();
        t.setId(UUID.randomUUID().toString());
        t.setTitle(title);
        t.setCategory(category);
        t.setPriority(priority);
        t.setDescription(description);
        t.setStatus("open");
        t.setSource("agent");
        t.setConversationId(recorder.getConversationId());   // 关联触发它的 agent 会话
        t.setTags("[]");
        taskTicketMapper.insert(t);

        return "已创建工单 #" + t.getId().substring(0, 8) + "：[" + priority + "] " + title
                + "（分类：" + category + "）。可在 /api/agent/tasks 查看全部工单。";
    }
}
