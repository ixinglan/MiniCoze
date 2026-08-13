package com.ai.aiworkshop.service;

import com.ai.aiworkshop.auth.CurrentUser;
import com.ai.aiworkshop.auth.JwtService;
import com.ai.aiworkshop.entity.UserDO;
import com.ai.aiworkshop.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证服务（阶段 9）：登录 / 当前用户 / 注册。
 * <p>
 * 注册开关 {@code app.registration.enabled}（默认 false）：
 * 演示系统页面不开放注册，但接口逻辑完整 —— 开关校验放在本 Service 层，
 * 即使有人绕过前端直接调 /api/auth/register，关闭时也会被这里拒绝（403）。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final boolean registrationEnabled;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                       @Value("${app.registration.enabled:false}") boolean registrationEnabled) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.registrationEnabled = registrationEnabled;
    }

    /** 注册是否开放（前端可用来隐藏入口；真正的开关判断在 register() 里） */
    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    /**
     * 登录：校验用户名 + 密码（BCrypt matches），成功签发 JWT。
     *
     * @throws IllegalArgumentException 用户名不存在 / 密码错误 / 用户被禁用
     */
    public Map<String, Object> login(String username, String password) {
        UserDO user = userMapper.selectOne(
                Wrappers.<UserDO>query().eq("username", username));
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }
        return userPayload(user);
    }

    /** 当前登录用户信息（不含密码） */
    public Map<String, Object> me() {
        Long uid = CurrentUser.id();
        if (uid == null) {
            throw new IllegalArgumentException("未登录");
        }
        UserDO user = userMapper.selectById(uid);
        return userPayload(user);
    }

    /**
     * 注册（受开关控制）：开关关闭时直接拒绝，防止绕过前端直调接口。
     * 成功后直接签发 token（注册即登录，演示体验顺滑）。
     *
     * @throws IllegalStateException    注册未开放
     * @throws IllegalArgumentException 用户名已存在 / 密码太短
     */
    public Map<String, Object> register(String username, String password, String displayName) {
        if (!registrationEnabled) {
            throw new IllegalStateException("注册未开放");
        }
        if (username == null || username.isBlank() || username.length() < 3) {
            throw new IllegalArgumentException("用户名至少 3 个字符");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (userMapper.selectCount(Wrappers.<UserDO>query().eq("username", username)) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
        UserDO user = new UserDO();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));   // BCrypt，绝不存明文
        user.setDisplayName(displayName == null || displayName.isBlank() ? username : displayName.trim());
        user.setRole("USER");
        user.setEnabled(true);
        userMapper.insert(user);
        return userPayload(user);
    }

    /** 组装登录响应：token + 用户基础信息（绝不含 password） */
    private Map<String, Object> userPayload(UserDO user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", jwtService.generateToken(user.getId(), user.getUsername(), user.getRole()));
        m.put("userId", user.getId());
        m.put("username", user.getUsername());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole());
        return m;
    }
}
