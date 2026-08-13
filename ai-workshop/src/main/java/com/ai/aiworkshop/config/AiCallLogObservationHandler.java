package com.ai.aiworkshop.config;

import com.ai.aiworkshop.entity.AiCallLogDO;
import com.ai.aiworkshop.mapper.AiCallLogMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.observation.AiOperationMetadata;
import org.springframework.stereotype.Component;

/**
 * 阶段 8 可观测性核心：把每次 LLM 调用自动落库（ai_call_log）。
 *
 * <h3>机制（Spring AI 1.1.2 内置 Observation 体系）</h3>
 * Spring AI 的每个 ChatModel 调用都会产生一个 {@link ChatModelObservationContext}，
 * 经过 Micrometer 的 ObservationRegistry 分发给所有注册的 ObservationHandler。
 * 我们只需实现 {@link ObservationHandler} 并在 Spring 容器里注册为 bean，
 * ObservationRegistry 会自动收集它，无需任何手动接线。
 *
 * <h3>为什么在 onStop 落库而不是 onError</h3>
 * Observation 的生命周期：onStart → （成功或失败）→ onStop 必然触发；
 * 错误信息通过 {@code context.getError()} 获取，成功/失败统一在 onStop 处理最干净。
 *
 * <h3>稳定性设计</h3>
 * 观测链路绝不允许影响主业务：落库任何异常都只打 warn 日志，不向上抛。
 */
@Slf4j
@Component
public class AiCallLogObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private final AiCallLogMapper aiCallLogMapper;

    public AiCallLogObservationHandler(AiCallLogMapper aiCallLogMapper) {
        this.aiCallLogMapper = aiCallLogMapper;
    }

    /** 只关心 ChatModel 的观测（聊天模型调用）；图片/向量等其他类型的观测直接忽略 */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    /** onStart：记录调用开始时间，存进 Observation.Context 自带的 KV 存储 */
    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put("startTime", System.currentTimeMillis());
    }

    /** onStop：组装一条 ai_call_log 落库 */
    @Override
    public void onStop(ChatModelObservationContext context) {
        try {
            AiCallLogDO record = new AiCallLogDO();

            // 1. 供应商 + 操作类型（如 provider=deepseek, operationType=chat）
            AiOperationMetadata meta = context.getOperationMetadata();
            if (meta != null) {
                record.setProvider(meta.provider());
                record.setOperationType(meta.operationType());
            }

            // 2. 耗时 = onStop 时间 - onStart 记录的 startTime
            Long startTime = context.get("startTime");
            record.setDurationMs(startTime == null ? 0L : System.currentTimeMillis() - startTime);

            // 3. 响应侧：模型名 + token 用量（输入/输出/总量）
            ChatResponse response = context.getResponse();
            if (response != null) {
                ChatResponseMetadata metadata = response.getMetadata();
                if (metadata != null) {
                    record.setModel(metadata.getModel());
                    Usage usage = metadata.getUsage();
                    if (usage != null) {
                        record.setPromptTokens(nvl(usage.getPromptTokens()));
                        record.setCompletionTokens(nvl(usage.getCompletionTokens()));
                        record.setTotalTokens(nvl(usage.getTotalTokens()));
                    }
                }
            }

            // 4. 成功 / 失败：错误从 context.getError() 取（onStop 统一收口）
            Throwable error = context.getError();
            if (error != null) {
                record.setSuccess(false);
                record.setErrorMsg(truncate(error.getMessage(), 500));
            } else {
                record.setSuccess(true);
            }

            // 5. 阶段 9：观测归属当前登录用户（obs.html 按用户隔离）。
            //    异步线程里 SecurityContext 可能取不到 → 留 null（老数据归 admin 由迁移兜底）
            try {
                record.setUserId(com.ai.aiworkshop.auth.CurrentUser.id());
            } catch (Exception ignored) {
                // 观测链路绝不允许影响主业务
            }

            aiCallLogMapper.insert(record);
        } catch (Exception e) {
            // 观测链路失败绝不影响主业务：只记日志
            log.warn("记录 AI 调用观测日志失败: {}", e.getMessage());
        }
    }

    /** Integer 可能为 null（部分模型不返回 usage），统一兜底为 0 */
    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    /** 错误信息截断，防止超长文本撑爆 VARCHAR 列 */
    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }
}
