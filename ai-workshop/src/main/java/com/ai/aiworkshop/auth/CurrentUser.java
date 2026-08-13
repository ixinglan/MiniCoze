package com.ai.aiworkshop.auth;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具（阶段 9 用户隔离的核心）。
 * <p>
 * Service/Controller 里通过 {@code CurrentUser.id()} 拿当前用户 id，
 * 所有会话/文件/工单/观测数据的查询写入都按它过滤 —— 这就是"用户级隔离"的实现点。
 * 安全：数据必须用 CurrentUser.id() 过滤，绝不能用前端传的 userId。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前登录用户 id（未登录返回 null） */
    public static Long id() {
        Claims claims = claims();
        return claims == null ? null : claims.get("uid", Long.class);
    }

    /** 当前登录用户名 */
    public static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    /** 当前登录用户角色：ADMIN / USER */
    public static String role() {
        Claims claims = claims();
        return claims == null ? null : claims.get("role", String.class);
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        return "ADMIN".equals(role());
    }

    /** 从 SecurityContext 的 Authentication.details 里取 JWT Claims（JwtAuthFilter 放的） */
    private static Claims claims() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof Claims claims) {
            return claims;
        }
        return null;
    }
}
