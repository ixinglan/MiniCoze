package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * chat_log 表映射（完整对话日志，append-only，给人回看用）。
 * 与 ChatMemoryDO（喂模型的滑动窗口 chat_memory）职责分离：
 * 本表不被窗口裁剪，永久保留每一次问答。
 */
@Data
@TableName("chat_log")
public class ChatLogDO {

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    /** user / assistant / system */
    @TableField("role")
    private String role;

    @TableField("content")
    private String content;    /** 归属用户 id（阶段 9 用户隔离） */
    @TableField("user_id")
    private Long userId;


    @TableField("created_at")
    private LocalDateTime createdAt;
}
