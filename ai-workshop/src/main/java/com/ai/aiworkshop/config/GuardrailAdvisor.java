package com.ai.aiworkshop.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 阶段 8 Guardrails（护栏）：输入过滤 + 输出脱敏。
 *
 * <h3>为什么用 Advisor 实现</h3>
 * Spring AI 1.1.x 没有现成的 guardrails 模块（Maven Central 无 spring-ai-guardrails），
 * 但护栏本质就是"模型调用前的闸 + 模型返回后的闸"，而 Advisor 恰好提供这两个切点：
 * <ul>
 *   <li>adviseCall / adviseStream 开头 = 输入闸：检查敏感词 / 越狱特征 / 超长，命中则
 *       <b>短路返回提示语</b>（不调 {@code chain.nextCall}，模型压根不参与，省 token）</li>
 *   <li>chain.nextCall 返回后 = 输出闸：对模型回复做 PII 脱敏（手机号/邮箱/身份证 → ***）</li>
 * </ul>
 * 短路 vs 抛异常：选短路——不抛异常，Controller 不用改任何代码，前端体验一致（正常收到一句提示语）。
 *
 * <h3>挂载方式</h3>
 * 在 ChatClientConfig 里把本 Bean 加入 chatClient 的 defaultAdvisors，所有 /api/chat 请求自动带护栏。
 * getOrder() 返回 -100（最低优先级先执行），确保输入检查在记忆注入等其它 Advisor 之前完成。
 */
@Slf4j
@Component
public class GuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    private final GuardrailProperties props;

    /** 越狱正则（懒编译，避免每次请求都编译） */
    private volatile List<Pattern> jailbreakCache;
    /** 脱敏正则（懒编译） */
    private volatile List<Pattern> piiCache;

    public GuardrailAdvisor(GuardrailProperties props) {
        this.props = props;
    }

    // ==================== 非流式入口 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 输入闸：命中直接短路，模型不参与
        String reason = checkInput(request);
        if (reason != null) {
            return blockedResponse(request, reason);
        }
        // 正常调用链（记忆注入 → 模型 → ...），返回后过输出闸
        return sanitize(chain.nextCall(request));
    }

    // ==================== 流式入口（SSE）====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String reason = checkInput(request);
        if (reason != null) {
            // 短路：直接返回一条"提示语"流（不订阅模型，前端正常收到一条消息）
            return Flux.just(blockedResponse(request, reason));
        }
        // 输入已放行：原样透传流式块。
        // 注意：流式输出脱敏不在 Advisor 做 —— DeepSeek 流式块的 textContent 为 null
        // （真实文本由 content() 内部聚合器处理），逐块/合并重建都会破坏 content() 的提取。
        // 流式 PII 脱敏统一在 ChatService.stream 聚合完整文本后调用 maskPii() 完成。
        return chain.nextStream(request);
    }

    // ==================== 输入闸 ====================

    /**
     * 对用户最新输入做三道检查：超长 / 敏感词 / 越狱特征。
     *
     * @return 命中时的拦截原因（null = 放行）
     */
    private String checkInput(ChatClientRequest request) {
        if (!props.isEnabled()) {
            return null;
        }
        String userText = extractUserText(request);
        if (userText == null || userText.isBlank()) {
            return null;
        }
        // 1) 长度上限（防超长输入浪费 token / 恶意灌入）
        if (userText.length() > props.getMaxInputChars()) {
            return "输入超长（" + userText.length() + " > " + props.getMaxInputChars() + " 字符）";
        }
        // 2) 敏感词（忽略大小写包含匹配）
        String lower = userText.toLowerCase();
        for (String word : props.getSensitiveWords()) {
            if (!word.isBlank() && lower.contains(word.toLowerCase())) {
                return "命中敏感词「" + word + "」";
            }
        }
        // 3) 越狱 / 提示注入特征（正则）
        for (Pattern p : jailbreakPatterns()) {
            if (p.matcher(userText).find()) {
                return "疑似越狱/提示注入攻击指令";
            }
        }
        return null;
    }

    /** 从请求的指令消息里提取"最后一条用户消息"文本（多轮对话取最新输入） */
    private String extractUserText(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == MessageType.USER) {
                return m.getText();
            }
        }
        return null;
    }

    /** 构造短路响应：一条"拦截提示语"的假 ChatResponse（模型不参与） */
    private ChatClientResponse blockedResponse(ChatClientRequest request, String reason) {
        log.warn("[Guardrail] 拦截输入，原因：{}", reason);
        String text = props.getBlockedReply() + "\n（原因：" + reason + "）";
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    // ==================== 输出闸（PII 脱敏）====================

    /**
     * 对模型回复做脱敏：命中任一 PII 正则（手机号/邮箱/身份证等）→ 替换为 ***。
     * 用 {@code ChatResponse.builder().from(原响应)} 复制其余元数据，只替换文本。
     * 非流式路径专用（流式路径在聚合后调用 maskPii）。
     */
    private ChatClientResponse sanitize(ChatClientResponse response) {
        if (!props.isPiiEnabled() || response == null || response.chatResponse() == null) {
            return response;
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
            return response;
        }
        String original = chatResponse.getResult().getOutput().getText();
        String masked = maskPii(original);
        if (masked.equals(original)) {
            return response;   // 没有命中，原样返回
        }
        log.info("[Guardrail] 输出脱敏：命中 PII 正则，已替换 {} 处", countReplacements(original, masked));
        ChatResponse newResponse = ChatResponse.builder()
                .from(chatResponse)
                .generations(List.of(new Generation(new AssistantMessage(masked))))
                .build();
        return ChatClientResponse.builder()
                .chatResponse(newResponse)
                .context(response.context())
                .build();
    }

    /** 对任意文本依次套用所有 PII 正则（流式/非流式共用）：命中替换为 ***，未命中原样返回。
     *  公开给 ChatService 用于流式全文聚合后的脱敏。 */
    public String maskPii(String text) {
        if (!props.isPiiEnabled() || text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        for (Pattern p : piiPatterns()) {
            masked = p.matcher(masked).replaceAll("***");
        }
        return masked;
    }

    /** 粗略统计替换了多少处（用差值估算，仅供日志） */
    private int countReplacements(String original, String masked) {
        int diff = original.length() - masked.length();
        return diff > 0 ? (diff + 2) / 3 : 0;   // 每处替换掉 3 个字符（***）
    }

    // ==================== 正则缓存 ====================

    private List<Pattern> jailbreakPatterns() {
        List<Pattern> local = jailbreakCache;
        if (local == null) {
            synchronized (this) {
                local = jailbreakCache;
                if (local == null) {
                    local = props.getJailbreakPatterns().stream().map(Pattern::compile).toList();
                    jailbreakCache = local;
                }
            }
        }
        return local;
    }

    private List<Pattern> piiPatterns() {
        List<Pattern> local = piiCache;
        if (local == null) {
            synchronized (this) {
                local = piiCache;
                if (local == null) {
                    local = props.getPiiPatterns().stream().map(Pattern::compile).toList();
                    piiCache = local;
                }
            }
        }
        return local;
    }

    // ==================== 排序与命名 ====================

    /** -100：在其它 Advisor（记忆注入等）之前先执行输入检查 */
    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public String getName() {
        return "guardrail";
    }
}
