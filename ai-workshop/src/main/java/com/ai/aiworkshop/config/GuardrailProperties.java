package com.ai.aiworkshop.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 阶段 8 Guardrails（护栏）配置。
 * <p>
 * 所有规则都做成可配置（application.yml 的 guardrail 段），改规则不用改代码：
 * <ul>
 *   <li>enabled         总开关（false = 完全关闭护栏）</li>
 *   <li>maxInputChars   输入长度上限（超过直接拦截，防 token 滥用）</li>
 *   <li>sensitiveWords  敏感词表（命中即拦截，不调模型）</li>
 *   <li>jailbreakPatterns 越狱/注入特征正则（命中即拦截）</li>
 *   <li>piiEnabled      输出脱敏开关（模型回复里的手机号/邮箱/身份证 → ***）</li>
 *   <li>piiPatterns     脱敏正则表</li>
 *   <li>blockedReply    拦截提示语（短路返回给用户，前端正常显示）</li>
 * </ul>
 * 使用 {@code @Component + @ConfigurationProperties} 组合：Spring Boot 会自动注册该 Bean
 * 并绑定 yml 里的 guardrail.* 配置（无需额外 @EnableConfigurationProperties）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "guardrail")
public class GuardrailProperties {

    /** 总开关：false 时所有护栏规则全部跳过（业务零影响） */
    private boolean enabled = true;

    /** 输入长度上限（字符数），超出即拦截 */
    private int maxInputChars = 2000;

    /** 敏感词表：用户输入包含任意一个词（忽略大小写）即拦截 */
    private List<String> sensitiveWords = List.of();

    /** 越狱/提示注入特征正则：匹配即拦截（防"忽略之前指令"这类攻击） */
    private List<String> jailbreakPatterns = List.of();

    /** 输出脱敏开关：模型回复中的个人信息替换为 *** */
    private boolean piiEnabled = true;

    /** 输出脱敏正则表（手机号 / 邮箱 / 身份证等） */
    private List<String> piiPatterns = List.of();

    /** 拦截时短路返回的提示语（不调模型，省 token 且用户体验一致） */
    private String blockedReply = "⚠️ 该内容已被安全护栏拦截，未发送给模型。";
}
