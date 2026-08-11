package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.tools.ToolCallRecorder;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 阶段 6 控制器：Agent 编排演示。
 *
 * 五个端点对应五种编排形态：
 *  - /react        ReactAgent（单 Agent 自带工具循环）
 *  - /sequential    SequentialAgent（顺序：写作→评审）
 *  - /routing       LlmRoutingAgent（单次路由分发）
 *  - /workflow      graph-core 手写意图路由工作流（原理入门）
 *  - /supervisor    graph-core 手写 Supervisor 多智能体（监督者循环路由）
 *
 * 统一入参：{ "query": "..." }；统一出参含 mode / result / trace（执行轨迹）等，方便前端可视化。
 */
@RestController
@RequestMapping("/api/agent6")
public class Agent6Controller {

    private final ReactAgent reactAgent;
    private final SequentialAgent sequentialAgent;
    private final LlmRoutingAgent routingAgent;
    private final CompiledGraph intentRoutingGraph;
    private final CompiledGraph supervisorGraph;
    private final ToolCallRecorder recorder;

    public Agent6Controller(ReactAgent reactAgent,
                            SequentialAgent sequentialAgent,
                            LlmRoutingAgent routingAgent,
                            @Qualifier("intentRoutingGraph") CompiledGraph intentRoutingGraph,
                            @Qualifier("supervisorGraph") CompiledGraph supervisorGraph,
                            ToolCallRecorder recorder) {
        this.reactAgent = reactAgent;
        this.sequentialAgent = sequentialAgent;
        this.routingAgent = routingAgent;
        this.intentRoutingGraph = intentRoutingGraph;
        this.supervisorGraph = supervisorGraph;
        this.recorder = recorder;
    }

    /** ReactAgent：单 Agent 自带工具循环。用 ToolCallRecorder 收集模型发起的工具调用明细。 */
    @PostMapping("/react")
    public Map<String, Object> react(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        String conversationId = "agent6-react-" + UUID.randomUUID();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "ReactAgent（单 Agent 工具循环）");
        try {
            recorder.begin(conversationId);
            // call 内部会跑 tool-execution loop：模型生成 toolCalls → 框架执行 @Tool 方法 → 回填 → 模型作答
            AssistantMessage msg = reactAgent.call(query);
            res.put("toolCalls", recorder.collect());
            res.put("result", msg.getText());
        } catch (Exception e) {
            res.put("error", "执行失败：" + e.getMessage());
            res.put("result", "");
            res.put("toolCalls", recorder.collect());
        } finally {
            recorder.clear();
        }
        return res;
    }

    /** SequentialAgent：子 Agent 顺序串执行（写作 → 评审） */
    @PostMapping("/sequential")
    public Map<String, Object> sequential(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "SequentialAgent（顺序：写作→评审）");
        try {
            Optional<OverAllState> state = sequentialAgent.invoke(query);
            res.put("result", extractLastMessage(state));
            res.put("trace", extractMessages(state));
        } catch (Exception e) {
            res.put("error", "执行失败：" + e.getMessage());
            res.put("result", "");
        }
        return res;
    }

    /** LlmRoutingAgent：LLM 判断问题类型后单次路由到最合适子 Agent */
    @PostMapping("/routing")
    public Map<String, Object> routing(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "LlmRoutingAgent（单次路由分发）");
        try {
            Optional<OverAllState> state = routingAgent.invoke(query);
            res.put("result", extractLastMessage(state));
            res.put("trace", extractMessages(state));
        } catch (Exception e) {
            res.put("error", "执行失败：" + e.getMessage());
            res.put("result", "");
        }
        return res;
    }

    /** graph-core 手写：意图路由工作流 */
    @PostMapping("/workflow")
    public Map<String, Object> workflow(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "graph-core 手写：意图路由工作流（原理入门）");
        try {
            Optional<OverAllState> state = intentRoutingGraph.invoke(Map.of("input", query));
            res.put("route", state.flatMap(s -> s.value("route")).orElse("?"));
            res.put("result", state.flatMap(s -> s.value("result")).orElse("?"));
        } catch (Exception e) {
            res.put("error", "执行失败：" + e.getMessage());
            res.put("result", "");
        }
        return res;
    }

    /** graph-core 手写：Supervisor 多智能体（监督者循环路由） */
    @PostMapping("/supervisor")
    public Map<String, Object> workflowSupervisor(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "graph-core 手写：Supervisor 多智能体（监督者循环路由）");
        try {
            Optional<OverAllState> state = supervisorGraph.invoke(Map.of("input", query));
            res.put("result", state.flatMap(s -> s.value("result")).orElse("?"));
        } catch (Exception e) {
            res.put("error", "执行失败：" + e.getMessage());
            res.put("result", "");
        }
        return res;
    }

    /** 从 OverAllState 提取 messages 列表里每条消息的文本（用于轨迹展示） */
    private List<String> extractMessages(Optional<OverAllState> stateOpt) {
        List<String> out = new ArrayList<>();
        if (stateOpt.isEmpty()) {
            return out;
        }
        Object messages = stateOpt.get().value("messages").orElse(null);
        if (messages instanceof List<?> list) {
            for (Object m : list) {
                if (m instanceof AssistantMessage am) {
                    out.add(am.getText());
                } else {
                    out.add(m.toString());
                }
            }
        }
        return out;
    }

    /** 取 messages 最后一条作为最终输出 */
    private String extractLastMessage(Optional<OverAllState> stateOpt) {
        List<String> msgs = extractMessages(stateOpt);
        return msgs.isEmpty() ? "(无输出)" : msgs.get(msgs.size() - 1);
    }
}
