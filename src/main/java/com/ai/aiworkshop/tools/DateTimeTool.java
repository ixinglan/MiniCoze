package com.ai.aiworkshop.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 工具 1：获取当前日期时间（最经典的 Tool Calling 演示）。
 *
 * 痛点：LLM 训练数据有截止时间，本身不知道“此刻”是几号几点。把它暴露成工具，
 * 模型在回答“现在几点”“今天星期几”这类问题时，会主动调用本方法拿真实时间。
 *
 * 注意每个 @Tool 方法第一行都 record 一次 —— 这是给前端可视化“模型调了哪些工具”用的，
 * 不影响工具本身逻辑。
 */
@Service
public class DateTimeTool {

    private final ToolCallRecorder recorder;

    public DateTimeTool(ToolCallRecorder recorder) {
        this.recorder = recorder;
    }

    @Tool(description = "获取当前的日期和时间，返回 'yyyy-MM-dd HH:mm:ss' 格式字符串。"
            + "当用户询问“现在几点”“今天几号”“当前日期/时间”时调用。")
    public String getCurrentDateTime() {
        recorder.record("getCurrentDateTime", Map.of());
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
