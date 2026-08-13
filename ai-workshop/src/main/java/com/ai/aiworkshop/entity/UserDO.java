package com.ai.aiworkshop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * users 表映射（阶段 9 用户体系）。
 * password 存 BCrypt 哈希；role: ADMIN / USER。
 */
@Data
@TableName("users")
public class UserDO {

    /** 自增主键（业务表 user_id 引用它） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录名（唯一） */
    @TableField("username")
    private String username;

    /** BCrypt 哈希（60 字符），绝不存明文 */
    @TableField("password")
    private String password;

    /** 展示名（导航栏显示） */
    @TableField("display_name")
    private String displayName;

    /** ADMIN / USER */
    @TableField("role")
    private String role;

    /** 1 启用 / 0 禁用 */
    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
