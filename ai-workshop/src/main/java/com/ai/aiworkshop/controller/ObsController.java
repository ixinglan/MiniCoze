package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.auth.CurrentUser;
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
 * 阶段 9 起按用户隔离：只统计当前登录用户自己的调用记录。
 *
 * <ul>
 *   <li>/api/obs/stats —— 总体 KPI + 按模型分布 + 近 7 天趋势（当前用户）</li>
 *   <li>/api/obs/logs  —— 最近调用明细（当前用户，倒序）</li>
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
        Long userId = CurrentUser.id();
        Map<String, Object> result = new HashMap<>();
        result.put("overall", aiCallLogMapper.selectOverallStats(userId));
        result.put("byModel", aiCallLogMapper.selectStatsByModel(userId));
        result.put("dailyTrend", aiCallLogMapper.selectDailyTrend(days, userId));
        return result;
    }

    /** 最近 N 条调用明细（默认 20 条） */
    @GetMapping("/logs")
    public List<AiCallLogDO> logs(@RequestParam(defaultValue = "20") int limit) {
        return aiCallLogMapper.selectRecent(limit, CurrentUser.id());
    }
}
