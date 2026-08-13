package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_call_log 表映射：AI 调用观测日志（阶段 8 可观测性）。
 * <p>
 * 由 {@code AiCallLogObservationHandler} 在每次 LLM 调用结束时写入一条，
 * 记录：模型 / 供应商 / token 用量 / 耗时 / 是否成功。
 * append-only，供监控页 obs.html 做聚合统计。
 */
@Data
@TableName("ai_call_log")
public class AiCallLogDO {

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作类型：chat / image / embedding */
    @TableField("operation_type")
    private String operationType;

    /** 供应商：deepseek / dashscope / ollama ... */
    @TableField("provider")
    private String provider;

    /** 模型名：deepseek-v4-flash / qwen-vl-max ... */
    @TableField("model")
    private String model;

    /** 输入 token 数 */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /** 输出 token 数 */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /** 总 token 数（成本估算基础） */
    @TableField("total_tokens")
    private Integer totalTokens;

    /** 本次调用耗时（毫秒） */
    @TableField("duration_ms")
    private Long durationMs;

    /** 1 成功 / 0 失败 */
    @TableField("success")
    private Boolean success;

    /** 失败原因（截断到 500 字符） */
    @TableField("error_msg")
    private String errorMsg;    /** 归属用户 id（阶段 9 用户隔离） */
    @TableField("user_id")
    private Long userId;


    @TableField("created_at")
    private LocalDateTime createdAt;
}
