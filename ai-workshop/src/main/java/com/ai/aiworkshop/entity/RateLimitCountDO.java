package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * rate_limit_count 表映射（阶段 9 限流计数）。
 */
@Data
@TableName("rate_limit_count")
public class RateLimitCountDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** USER / IP */
    @TableField("scope_type")
    private String scopeType;

    /** 用户 id 或 IP 字符串 */
    @TableField("scope_value")
    private String scopeValue;

    /** CHAT / UPLOAD */
    @TableField("action")
    private String action;

    @TableField("day")
    private LocalDate day;

    @TableField("count")
    private Integer count;
}
