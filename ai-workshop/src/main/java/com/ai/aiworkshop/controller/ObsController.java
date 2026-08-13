package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.entity.AiCallLogDO;
import com.ai.aiworkshop.mapper.AiCallLogMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 8 可观测：AI 调用监控接口（供 obs.html 消费）。
 *
 * <ul>
 *   <li>/api/obs/stats —— 总体 KPI + 按模型分布 + 近 7 天趋势，一次拿全</li>
 *   <li>/api/obs/logs  —— 最近调用明细（倒序）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/obs")
public class ObsController {

    private final AiCallLogMapper aiCallLogMapper;

    public ObsController(AiCallLogMapper aiCallLogMapper) {
        this.aiCallLogMapper = aiCallLogMapper;
    }

    /** 监控页主数据：overall(总体) + byModel(模型分布) + dailyTrend(近 N 天趋势) */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> result = new HashMap<>();
        result.put("overall", aiCallLogMapper.selectOverallStats());
        result.put("byModel", aiCallLogMapper.selectStatsByModel());
        result.put("dailyTrend", aiCallLogMapper.selectDailyTrend(days));
        return result;
    }

    /** 最近 N 条调用明细（默认 20 条） */
    @GetMapping("/logs")
    public List<AiCallLogDO> logs(@RequestParam(defaultValue = "20") int limit) {
        return aiCallLogMapper.selectRecent(limit);
    }
}
