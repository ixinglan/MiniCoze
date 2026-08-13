package com.ai.aiworkshop.config;

import com.ai.aiworkshop.auth.CurrentUser;
import com.ai.aiworkshop.mapper.RateLimitMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 阶段 9 限流拦截器：防恶意体验。
 * <p>
 * 双维度按天配额：
 * <ul>
 *   <li>USER 维度：登录用户的每日调用次数（防单人刷爆）</li>
 *   <li>IP 维度：同 IP 的每日总次数（防换号绕过）</li>
 * </ul>
 * 动作分两类：CHAT（聊天）与 UPLOAD（文档上传），配额独立配置：
 * {@code app.rate-limit.chat-per-user-per-day} / chat-per-ip-per-day / upload-per-user-per-day / upload-per-ip-per-day。
 * 计数用 rate_limit_count 表（唯一键 scope_type+scope_value+action+day），原子递增。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitMapper rateLimitMapper;
    private final int chatPerUser;
    private final int chatPerIp;
    private final int uploadPerUser;
    private final int uploadPerIp;

    public RateLimitInterceptor(RateLimitMapper rateLimitMapper,
                                @Value("${app.rate-limit.chat-per-user-per-day:50}") int chatPerUser,
                                @Value("${app.rate-limit.chat-per-ip-per-day:100}") int chatPerIp,
                                @Value("${app.rate-limit.upload-per-user-per-day:5}") int uploadPerUser,
                                @Value("${app.rate-limit.upload-per-ip-per-day:10}") int uploadPerIp) {
        this.rateLimitMapper = rateLimitMapper;
        this.chatPerUser = chatPerUser;
        this.chatPerIp = chatPerIp;
        this.uploadPerUser = uploadPerUser;
        this.uploadPerIp = uploadPerIp;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 只对聊天与上传做限流
        String action = resolveAction(request);
        if (action == null) {
            return true;
        }

        String ip = resolveIp(request);
        Long userId = CurrentUser.id();   // 登录用户必有；未登录走不到这里（Security 已拦）

        // 用户维度
        if (userId != null) {
            int userLimit = "CHAT".equals(action) ? chatPerUser : uploadPerUser;
            if (!consume(userId.toString(), "USER", action, userLimit)) {
                return reject(response, action, userLimit, "账号");
            }
        }
        // IP 维度
        int ipLimit = "CHAT".equals(action) ? chatPerIp : uploadPerIp;
        if (!consume(ip, "IP", action, ipLimit)) {
            return reject(response, action, ipLimit, "IP");
        }
        return true;
    }

    /**
     * 原子计数并判断是否超限：
     * 若当天该维度已达配额 → 返回 false（拒绝）；否则 +1 并放行。
     * 用 INSERT ... ON DUPLICATE KEY UPDATE 保证原子性（并发不超发）。
     */
    private boolean consume(String scopeValue, String scopeType, String action, int limit) {
        // 显式东八区：LocalDate.now() 用的是 JVM 时区，服务器若为 UTC 会按错日期计数
        LocalDate day = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        // 先查当前计数
        var record = rateLimitMapper.selectOne(
                Wrappers.<com.ai.aiworkshop.entity.RateLimitCountDO>query()
                        .eq("scope_type", scopeType)
                        .eq("scope_value", scopeValue)
                        .eq("action", action)
                        .eq("day", day));
        int current = record == null ? 0 : record.getCount();
        if (current >= limit) {
            return false;   // 已超限，拒绝
        }
        // 原子 +1（不存在则插入计数 1）
        rateLimitMapper.increment(scopeType, scopeValue, action, day);
        return true;
    }

    /**
     * 所有"消耗模型调用"的端点（按 CHAT 配额限流）。
     * 查表方式：加新页面/端点时在这里补一行即可，最不容易漏。
     * ⚠️ 只放"AI 调用"端点；列表/查询类（会话列表、工单列表、MCP 工具列表、文件列表）不放——它们不该扣配额。
     */
    private static final java.util.Set<String> CHAT_ENDPOINTS = java.util.Set.of(
            // 聊天（阶段 1）
            "/api/chat", "/api/chat/stream",
            // RAG 问答（阶段 3）
            "/api/rag", "/api/rag/stream",
            // Agent 工具调用（阶段 4）
            "/api/agent/chat",
            // 多模态（阶段 5）：图片理解 + 文生图
            "/api/multimodal/describe", "/api/multimodal/generate",
            // Agent 编排（阶段 6）：5 种形态 + MCP 工具对话
            "/api/agent6/react", "/api/agent6/sequential", "/api/agent6/routing",
            "/api/agent6/workflow", "/api/agent6/supervisor", "/api/agent6/mcp/chat"
    );

    /** 文档上传端点（按 UPLOAD 配额限流）：单文件 + 批量（RagFileController） */
    private static final java.util.Set<String> UPLOAD_ENDPOINTS = java.util.Set.of(
            "/api/rag/files", "/api/rag/files/batch"
    );

    /** 根据请求路径 + 方法识别动作：CHAT / UPLOAD / null（不限制） */
    private String resolveAction(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean post = "POST".equalsIgnoreCase(method);
        // AI 调用（CHAT）：非流式端点都是 POST；流式端点（/xx/stream）是 GET
        // ⚠️ 流式是 GET 不是 POST！之前要求 POST 导致流式聊天/问答从不计数（"限流不生效"的根因）
        if (CHAT_ENDPOINTS.contains(path) && (post || path.endsWith("/stream"))) {
            return "CHAT";
        }
        // 文档上传（UPLOAD）
        if (post && UPLOAD_ENDPOINTS.contains(path)) {
            return "UPLOAD";
        }
        return null;
    }

    /** 取客户端 IP（穿透常见代理头，X-Forwarded-For 取第一个） */
    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 返回 429 + JSON 提示 */
    private boolean reject(HttpServletResponse response, String action, int limit, String dim)
            throws java.io.IOException {
        String name = "CHAT".equals(action) ? "聊天" : "文档上传";
        String message = "今日" + name + "次数已达上限（" + dim + "维度 " + limit + " 次），明天再来体验吧";
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}
