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
                        // 放行：认证相关（登录/注册）、静态页面与资源、健康检查
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/", "/login.html", "/index.html", "/rag.html", "/agent.html",
                                "/multimodal.html", "/agent6.html", "/obs.html",
                                "/favicon.ico", "/actuator/health").permitAll()
                        // 其余全部要求登录
                        .anyRequest().authenticated())
                // JWT 过滤器放在用户名密码过滤器之前
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
