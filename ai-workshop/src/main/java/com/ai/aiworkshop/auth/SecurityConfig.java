package com.ai.aiworkshop.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Spring Security 配置（阶段 9，无状态 JWT 模式）。
 * <ul>
 *   <li>登录 / 注册接口、静态页面放行（页面加载不需要 token，JS 内部调 /api 才需要）；</li>
 *   <li>其余 /api/** 全部要求登录，未认证返回 401；</li>
 *   <li>无 Session（STATELESS），认证状态全靠 JWT。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** BCrypt 密码加密器（登录校验 / 注册加密 / admin 初始化共用） */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // 无状态 JWT，不需要 CSRF 防护
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // 放行：认证相关（登录/注册）、静态页面与资源（*.html/*.js/*.css/图片）、健康检查
                        // ⚠️ 必须用通配符而不是硬编码页面清单：否则页面引用的 auth.js 等资源会被 401 拦截，
                        //    页面本身的未登录跳转逻辑因脚本加载失败而完全失效（阶段 9 踩过这个坑）。
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/", "/*.html", "/*.js", "/*.css", "/*.ico",
                                "/*.png", "/*.jpg", "/*.jpeg", "/*.svg", "/*.gif",
                                "/actuator/health").permitAll()
                        // 其余全部要求登录（未认证返回 401）
                        .anyRequest().authenticated())
                // JWT 过滤器放在用户名密码过滤器之前
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
