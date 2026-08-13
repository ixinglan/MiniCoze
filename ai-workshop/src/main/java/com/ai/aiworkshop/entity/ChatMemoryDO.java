package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * chat_memory 表映射（消息明细）。
 *
 * 该表是复合主键 (conversation_id, message_index)：
 * 这里把 conversation_id 声明为 MyBatis-Plus 的主键（手动赋值，IdType.INPUT），
 * message_index 作为普通列。业务上按 conversation_id 整窗读写，不依赖 selectById。
 */
@Data
@TableName("chat_memory")
public class ChatMemoryDO {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private String conversationId;

    @TableField("message_index")
    private Integer messageIndex;

    @TableField("message_type")
    private String messageType;

    @TableField("content")
    private String content;

    /** 消息元数据，存 Jackson 序列化后的 JSON 字符串；还原时暂不回填（纯文本对话无影响） */
    @TableField("metadata")
    private String metadata;    /** 归属用户 id（阶段 9 用户隔离） */
    @TableField("user_id")
    private Long userId;


    @TableField("created_at")
    private LocalDateTime createdAt;
}
