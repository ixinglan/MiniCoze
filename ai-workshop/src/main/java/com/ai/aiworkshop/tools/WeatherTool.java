package com.ai.aiworkshop.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 工具 3：天气查询（模拟数据）。
 *
 * 真实场景这里会调第三方天气 API（如高德/和风）。为了不引入外部密钥、保持 demo 自包含，
 * 这里用“基于城市名的稳定伪随机”生成一份看起来合理的模拟天气，重点演示
 * “模型决定调工具 → 工具返回结构化信息 → 模型据此组织自然语言回答”的闭环。
 */
@Service
public class WeatherTool {

    private final ToolCallRecorder recorder;

    private static final List<String> CONDITIONS = List.of("晴", "多云", "阴", "小雨", "雷阵雨");

    public WeatherTool(ToolCallRecorder recorder) {
        this.recorder = recorder;
    }

    @Tool(description = "查询指定城市的天气信息（演示用模拟数据）。当用户询问某个城市的天气时调用。")
    public String getWeather(
            @ToolParam(description = "城市名称，如 北京、上海、广州") String city) {
        recorder.record("getWeather", Map.of("city", city));

        // 基于城市名生成稳定的伪随机结果（同一城市每次结果一致，更像“真实”数据）
        int hash = city.hashCode();
        String condition = CONDITIONS.get(Math.floorMod(hash, CONDITIONS.size()));
        int base = 15 + Math.floorMod(hash, 15);          // 15~29 度基准
        int low = base;
        int high = base + 4 + Math.floorMod(hash >> 3, 6); // 高 4~9 度
        int humidity = 35 + Math.floorMod(hash >> 5, 50);   // 35~84%
        int wind = 1 + Math.floorMod(hash >> 7, 5);         // 1~5 级

        return String.format("城市：%s，天气：%s，温度：%d~%d℃，湿度：%d%%，风力：%d 级（模拟数据）",
                city, condition, low, high, humidity, wind);
    }
}
