package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * task_ticket 表映射（阶段 4 工单落库）。
 *
 * 与阶段 2 的 {@code TaskTicket}（结构化输出目标对象）解耦：TaskTicket 只负责“模型抽取出的字段”，
 * 本 DO 负责“落库存储”，额外带上 DB 层面的 status / source / conversation_id / 时间戳等生命周期字段。
 */
@Data
@TableName("task_ticket")
public class TaskTicketDO {

    /** 工单 ID，业务层用 UUID 赋值，IdType.INPUT 表示不自动生成 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("title")
    private String title;

    @TableField("category")
    private String category;

    @TableField("priority")
    private String priority;

    /** 截止时间 YYYY-MM-DD 或空 */
    @TableField("due_date")
    private String dueDate;

    /** 关键词标签，库中存 JSON 数组字符串，读取时再解析为列表 */
    @TableField("tags")
    private String tags;

    @TableField("description")
    private String description;

    @TableField("need_follow_up")
    private Boolean needFollowUp;

    /** 生命周期：open（待办）/ done（已完成），供后续阶段更新 */
    @TableField("status")
    private String status;

    /** 来源：agent（当前 Agent 工具）/ mcp（阶段 7 MCP 工具）/ subagent（阶段 6 子智能体），来源无关统一落表 */
    @TableField("source")
    private String source;

    /** 关联到触发它的 agent 会话 ID，便于溯源 */
    @TableField("conversation_id")
    private String conversationId;    /** 归属用户 id（阶段 9 用户隔离） */
    @TableField("user_id")
    private Long userId;


    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
