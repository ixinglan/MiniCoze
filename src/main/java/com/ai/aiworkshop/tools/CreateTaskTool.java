package com.ai.aiworkshop.tools;

import com.ai.aiworkshop.model.TaskTicket;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具 5：创建工单（业务闭环的另一半 —— 复用阶段 2 的 TaskTicket 结构化对象）。
 *
 * 价值：阶段 2 我们学会了“把自然语言解析成结构化对象”，阶段 4 让模型在理解用户意图后，
 * 主动把一件事“落地”成一个工单并登记。这正是 Agent 工作台“理解需求 → 产出可执行动作”的闭环：
 * 模型不再只是回答问题，而是能“动手”创建记录。
 *
 * 这里用一个线程安全的内存列表暂存工单（demo 用，重启即丢；生产可换 DB）。
 * 额外暴露 getTickets() 供 /api/agent/tasks 查看，方便验证“工单确实被创建了”。
 */
@Service
public class CreateTaskTool {

    private final ToolCallRecorder recorder;
    private final List<TaskTicket> tickets = new CopyOnWriteArrayList<>();

    public CreateTaskTool(ToolCallRecorder recorder) {
        this.recorder = recorder;
    }

    @Tool(description = "创建一个新的工单/任务并登记。当用户希望把一件事记录成待办工单"
            + "（含标题、分类、优先级、描述）时调用，例如“帮我建个工单：上线前做一次安全扫描，优先级 P0”。")
    public String createTask(
            @ToolParam(description = "工单标题，一句话概括，不超过 20 字") String title,
            @ToolParam(description = "分类，只能从 [开发, 运维, 设计, 文档, 测试, 其他] 中选一个") String category,
            @ToolParam(description = "优先级，只能从 [P0(最高), P1, P2, P3(最低)] 中选一个") String priority,
            @ToolParam(description = "需求详细描述，用 1 到 3 句话把事情说清楚") String description) {
        recorder.record("createTask", Map.of("title", title, "category", category, "priority", priority));

        TaskTicket t = new TaskTicket();
        t.setTitle(title);
        t.setCategory(category);
        t.setPriority(priority);
        t.setDescription(description);
        tickets.add(t);

        return "已创建工单 #" + tickets.size() + "：[" + priority + "] " + title
                + "（分类：" + category + "）。可在 /api/agent/tasks 查看全部工单。";
    }

    /** 供 Controller 暴露，便于验证“工单确实落地了” */
    public List<TaskTicket> getTickets() {
        return List.copyOf(tickets);
    }
}
