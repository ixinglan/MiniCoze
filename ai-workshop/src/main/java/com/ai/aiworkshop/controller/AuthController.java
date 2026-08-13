package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（阶段 9）：
 * <ul>
 *   <li>POST /api/auth/login    —— 登录，返回 JWT + 用户信息</li>
 *   <li>GET  /api/auth/me       —— 当前登录用户信息（未登录会被 Security 拦 401）</li>
 *   <li>POST /api/auth/register —— 注册（受 app.registration.enabled 开关控制，默认关闭）</li>
 *   <li>GET  /api/auth/register/status —— 注册开关状态（前端据此决定是否显示注册入口）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：{username, password} → {token, userId, username, displayName, role} */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(authService.login(body.get("username"), body.get("password")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
        }
    }

    /** 当前用户信息（无需传参，从 token 解析） */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        try {
            return ResponseEntity.ok(authService.me());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
        }
    }

    /** 注册开关状态 */
    @GetMapping("/register/status")
    public Map<String, Object> registerStatus() {
        return Map.of("enabled", authService.isRegistrationEnabled());
    }

    /** 注册：开关关闭时 403（Service 层强校验，防绕过前端直调） */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(authService.register(
                    body.get("username"), body.get("password"), body.get("displayName")));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
