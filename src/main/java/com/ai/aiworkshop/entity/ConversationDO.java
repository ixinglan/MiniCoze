package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * conversation 表映射（会话元数据，给前端左侧栏用）。
 */
@Data
@TableName("conversation")
public class ConversationDO {

    /** 会话 ID，由业务层用 UUID 赋值，IdType.INPUT 表示不自动生成 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("title")
    private String title;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
