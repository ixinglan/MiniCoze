package com.ai.aiworkshop.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具 2：四则运算计算器。
 *
 * 痛点：LLM 做精确数值计算经常出错（尤其多步、大数、小数）。把计算交给确定性的 Java 方法，
 * 模型只负责“理解题意 + 拆参数 + 调工具”，结果 100% 准确。
 *
 * operator 用枚举式字符串（add/subtract/multiply/divide），比让模型自由发挥运算符更稳。
 */
@Service
public class CalculatorTool {

    private final ToolCallRecorder recorder;

    public CalculatorTool(ToolCallRecorder recorder) {
        this.recorder = recorder;
    }

    @Tool(description = "对两个数字做四则运算。当用户需要精确的数值计算（加减乘除）时调用。"
            + "operator 只能是 add(加)/subtract(减)/multiply(乘)/divide(除) 之一。")
    public String calculate(
            @ToolParam(description = "第一个操作数（数字）") double a,
            @ToolParam(description = "运算符，只能是 add(加)/subtract(减)/multiply(乘)/divide(除)") String operator,
            @ToolParam(description = "第二个操作数（数字）") double b) {
        recorder.record("calculate", Map.of("a", a, "operator", operator, "b", b));
        return switch (operator) {
            case "add" -> String.valueOf(a + b);
            case "subtract" -> String.valueOf(a - b);
            case "multiply" -> String.valueOf(a * b);
            case "divide" -> {
                if (b == 0) yield "错误：除数不能为 0";
                yield String.valueOf(a / b);
            }
            default -> "错误：不支持的运算符 " + operator + "，只能用 add/subtract/multiply/divide";
        };
    }
}
