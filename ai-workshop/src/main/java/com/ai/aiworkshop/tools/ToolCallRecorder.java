package com.ai.aiworkshop.tools;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用记录器（阶段 4 教学可视化用）。
 *
 * 为什么需要它：Spring AI 默认 {@code call()} 会在内部跑完整个 tool-execution loop，
 * 最终返回的 {@link org.springframework.ai.chat.model.ChatResponse} 往往只剩“最终答案”文本，
 * 中间模型发起的工具调用未必保留在响应里 —— 直接解析响应拿 toolCalls 很脆弱。
 *
 * 因此这里用一个 ThreadLocal 列表，在每个 {@code @Tool} 方法真正执行时主动 record 一次
 * （工具名 + 入参），Controller 在调用前后 begin()/collect() 即可 100% 可靠地拿到
 * “模型这次到底调了哪些工具、传了什么参数”，用于前端可视化。
 *
 * 用 ThreadLocal 而非全局 List：Web 每个请求在独立线程，天然隔离，避免并发请求互相污染。
 *
 * 额外职责（阶段 4 补全 - 工单落库）：携带本次请求的 conversationId。
 * 工具方法（如 CreateTaskTool）执行时拿不到请求级的 conversationId 参数，
 * 通过本记录器的 ThreadLocal 传递，避免为工具方法硬加参数、改动 @Tool 签名。
 */
@Component
public class ToolCallRecorder {

    private final ThreadLocal<List<Map<String, Object>>> holder = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<String> conversationIdHolder = new ThreadLocal<>();

    /** 每次请求开始时重置当前线程的调用记录 + 记录所属会话 ID */
    public void begin(String conversationId) {
        holder.set(new ArrayList<>());
        conversationIdHolder.set(conversationId);
    }

    /** 读取本次请求所属的会话 ID（工具方法落库时关联用） */
    public String getConversationId() {
        return conversationIdHolder.get();
    }

    /** 工具方法执行时记录一次调用（name + 入参） */
    public void record(String name, Map<String, Object> params) {
        List<Map<String, Object>> list = holder.get();
        if (list == null) {
            list = new ArrayList<>();
            holder.set(list);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("params", params == null ? Map.of() : params);
        list.add(entry);
    }

    /** 取出本线程的全部调用记录（深拷贝，返回前端用） */
    public List<Map<String, Object>> collect() {
        List<Map<String, Object>> list = holder.get();
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** 清空（请求结束调用，释放 ThreadLocal，防止内存泄漏） */
    public void clear() {
        holder.remove();
        conversationIdHolder.remove();
    }
}
