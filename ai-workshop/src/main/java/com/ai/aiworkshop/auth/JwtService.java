package com.ai.aiworkshop.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具（阶段 9 认证）。
 * <p>
 * 无状态认证：登录成功后发 token，前端每次请求带 {@code Authorization: Bearer <token>}，
 * 服务端解析即可拿到 userId / username / role，无需 Session。
 * 用 HS256 对称签名，密钥来自配置 {@code app.jwt.secret}（环境变量覆盖，别用默认值上生产）。
 */
@Component
public class JwtService {

    /** token 有效期（毫秒）：24 小时 */
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000L;

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret:ai-workshop-demo-secret-key-please-change-0123456789}") String secret) {
        // HS256 要求密钥 >= 256 bit（32 字节），用 UTF-8 字节构造 HMAC 密钥
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 token：subject = username，额外 claim 带 uid 和 role */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名/有效期，失败抛异常（由过滤器捕获转为未认证） */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
