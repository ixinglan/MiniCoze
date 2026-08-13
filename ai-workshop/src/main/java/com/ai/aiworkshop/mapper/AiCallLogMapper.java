package com.ai.aiworkshop.mapper;

import com.ai.aiworkshop.entity.AiCallLogDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * ai_call_log 表 Mapper（阶段 8 可观测性）。
 * <p>
 * 普通写入走 MyBatis-Plus 的 BaseMapper.insert；
 * 监控页的聚合统计用原生 SQL（GROUP BY）更直观，直接写 @Select。
 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLogDO> {

    /**
     * 总体统计：总调用数 / 成功数 / 失败数 / 平均耗时 / 最大耗时 / 累计 token。
     * 返回一条 Map：{total, success, fail, avgDurationMs, maxDurationMs, totalTokens}
     */
    @Select("""
            SELECT COUNT(*)                              AS total,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS success,
                   SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS fail,
                   ROUND(IFNULL(AVG(duration_ms), 0))    AS avgDurationMs,
                   IFNULL(MAX(duration_ms), 0)           AS maxDurationMs,
                   IFNULL(SUM(total_tokens), 0)          AS totalTokens
            FROM ai_call_log
            """)
    Map<String, Object> selectOverallStats();

    /**
     * 按模型分组统计：每个模型的调用次数 / 总 token / 平均耗时。
     * 用于监控页"模型分布"。
     */
    @Select("""
            SELECT IFNULL(model, 'unknown') AS model,
                   COUNT(*)                AS calls,
                   IFNULL(SUM(total_tokens), 0) AS totalTokens,
                   ROUND(IFNULL(AVG(duration_ms), 0)) AS avgDurationMs
            FROM ai_call_log
            GROUP BY model
            ORDER BY calls DESC
            """)
    List<Map<String, Object>> selectStatsByModel();

    /**
     * 最近 N 天按天统计：每天的调用次数 / 成功数 / token 总量。
     * 用于监控页"调用趋势"。
     *
     * @param days 最近几天（如 7）
     */
    @Select("""
            SELECT DATE(created_at)                       AS day,
                   COUNT(*)                               AS calls,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS success,
                   IFNULL(SUM(total_tokens), 0)           AS totalTokens
            FROM ai_call_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} - 1 DAY)
            GROUP BY DATE(created_at)
            ORDER BY day
            """)
    List<Map<String, Object>> selectDailyTrend(@Param("days") int days);

    /**
     * 最近 N 条调用明细（倒序）。
     */
    @Select("""
            SELECT id, operation_type, provider, model, prompt_tokens, completion_tokens,
                   total_tokens, duration_ms, success, error_msg, created_at
            FROM ai_call_log
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AiCallLogDO> selectRecent(@Param("limit") int limit);
}
