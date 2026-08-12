package com.ai.aiworkshop.config;

import com.ai.aiworkshop.tools.CalculatorTool;
import com.ai.aiworkshop.tools.CreateTaskTool;
import com.ai.aiworkshop.tools.DateTimeTool;
import com.ai.aiworkshop.tools.RagQueryTool;
import com.ai.aiworkshop.tools.WeatherTool;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 阶段 6 专用：Agent 编排基础设施。
 *
 * SAA 在 1.1.2.2 下提供两层编排能力：
 *  - 低层 graph-core（Java 版 LangGraph）：StateGraph + Node + Edge + OverAllState，用于手写任意工作流/多智能体图
 *  - 高层 agent-framework：ReactAgent / SequentialAgent / LlmRoutingAgent 等开箱即用的 Agent 模式
 *
 * 本配置把所有"可复用 Actor"都声明成 Spring Bean，供 Controller 直接调用：
 *  - 编排用 ChatClient（基于 DeepSeek V4，不挂记忆，每次编排独立）
 *  - 5 个角色 ReactAgent（综合工具型 / 研究 / 编程 / 写作 / 评审）
 *  - SequentialAgent（顺序：写作→评审）、LlmRoutingAgent（单次路由）
 *  - 两个手写 StateGraph：意图路由工作流（原理入门）、Supervisor 多智能体（监督者循环路由）
 *
 * 注：用户选择用 DeepSeek V4 作为编排主力模型（阶段 4 已验证它能跑 tool loop）。
 * 若 Supervisor 等嵌套 tool-calling 在 DeepSeek 上不稳定，把 orchestrationClient 改用 dashScopeChatModel 即可。
 */
@Configuration
public class Agent6Config {

    // ===== 阶段 4 的 5 个 @Tool 实例（复用，作为 ReactAgent 的工具来源）=====
    private final DateTimeTool dateTimeTool;
    private final CalculatorTool calculatorTool;
    private final WeatherTool weatherTool;
    private final RagQueryTool ragQueryTool;
    private final CreateTaskTool createTaskTool;

    // 编排主力模型：DeepSeek V4
    private final ChatModel deepSeek;

    // MCP 远程工具（阶段 7）：来自 Python stdio MCP Server + Nacos MCP Server。
    // @Lazy + 非强制注入：MCP 未配置/未连接时为空列表，不影响核心 Agent 功能。
    @Lazy
    private final List<ToolCallback> mcpTools;

    public Agent6Config(@Qualifier("deepSeekChatModel") ChatModel deepSeek,
                        DateTimeTool dateTimeTool, CalculatorTool calculatorTool,
                        WeatherTool weatherTool, RagQueryTool ragQueryTool, CreateTaskTool createTaskTool,
                        @Lazy List<ToolCallback> mcpToolCallbacks) {
        this.deepSeek = deepSeek;
        this.dateTimeTool = dateTimeTool;
        this.calculatorTool = calculatorTool;
        this.weatherTool = weatherTool;
        this.ragQueryTool = ragQueryTool;
        this.createTaskTool = createTaskTool;
        this.mcpTools = mcpToolCallbacks != null ? mcpToolCallbacks : List.of();
    }

    /**
     * 编排专用 ChatClient：基于 DeepSeek V4，不挂记忆 Advisor（每次编排都是独立任务，不该串味）。
     * 各 Agent 会在自己的 instruction 里定义角色，所以这个 client 不带 system。
     */
    @Bean
    public ChatClient orchestrationClient() {
        return ChatClient.builder(deepSeek).build();
    }

    /**
     * 阶段 7 MCP 工具专用 ChatClient：注入所有 MCP ToolCallback（Python stdio + Nacos SSE），
     * 让 Agent 能无缝调用远程 MCP 工具，就像调用本地 @Tool 方法一样。
     * <p>
     * 注意：这里用 {@code defaultTools(ToolCallback...)} 而非 {@code defaultTools(Object...)}，
     * 因为 MCP 工具以 ToolCallback 接口实例注入，不走 @Tool 注解扫描。
     * </p>
     */
    @Bean
    public ChatClient mcpChatClient(@Qualifier("deepSeekChatModel") ChatModel deepSeek) {
        var builder = ChatClient.builder(deepSeek);
        if (!mcpTools.isEmpty()) {
            builder.defaultToolCallbacks(mcpTools);
        }
        return builder.build();
    }

    /**
     * 综合工具型 ReactAgent：复用阶段 4 的 5 个 @Tool，演示"单 Agent 自带工具循环"。
     * methodTools(Object...) 与 ChatClient.defaultTools(...) 等价：扫描传入对象里的 @Tool 方法并注册成工具。
     */
    @Bean
    public ReactAgent reactAgent(ChatClient orchestrationClient) {
        return ReactAgent.builder()
                .name("工具型 Agent")
                .instruction("你是一个能调用工具的 AI 助手。你拥有以下工具：获取当前时间、四则运算计算器、"
                        + "查询天气(模拟)、检索本地知识库、创建工单。请根据用户需求自主判断是否需要调用工具、"
                        + "调用哪一个、传什么参数，并基于工具的返回结果用简体中文回答。不要编造工具不存在的信息。")
                .chatClient(orchestrationClient)
                .methodTools(dateTimeTool, calculatorTool, weatherTool, ragQueryTool, createTaskTool)
                .build();
    }

    /** 研究助手（多智能体子 Agent，无工具，纯 LLM 角色） */
    @Bean
    public ReactAgent researcherAgent(ChatClient orchestrationClient) {
        return ReactAgent.builder()
                .name("研究助手")
                .instruction("你是一个研究助手，擅长搜集、整理和归纳信息，用简体中文给出结构清晰的研究结果。")
                .chatClient(orchestrationClient)
                .build();
    }

    /** 编程助手（多智能体子 Agent） */
    @Bean
    public ReactAgent coderAgent(ChatClient orchestrationClient) {
        return ReactAgent.builder()
                .name("编程助手")
                .instruction("你是一个编程助手，擅长写代码、解释代码、调试问题，用简体中文回答，代码用代码块呈现。")
                .chatClient(orchestrationClient)
                .build();
    }

    /** 写作助手（多智能体子 Agent） */
    @Bean
    public ReactAgent writerAgent(ChatClient orchestrationClient) {
        return ReactAgent.builder()
                .name("写作助手")
                .instruction("你是一个写作助手，擅长撰写文章、文案、总结，语言流畅、结构清晰。")
                .chatClient(orchestrationClient)
                .build();
    }

    /** 评审助手（多智能体子 Agent） */
    @Bean
    public ReactAgent reviewerAgent(ChatClient orchestrationClient) {
        return ReactAgent.builder()
                .name("评审助手")
                .instruction("你是一个评审助手，负责审阅他人产出的内容，指出优点、问题并给出改进建议。")
                .chatClient(orchestrationClient)
                .build();
    }

    /**
     * 顺序智能体：先写作、后评审。子 Agent 按顺序串执行，后一个能读到前一个的输出
     * （框架通过 outputKey + 占位符自动把前序结果喂给后序 Agent）。
     */
    @Bean
    public SequentialAgent sequentialAgent(ReactAgent writerAgent, ReactAgent reviewerAgent) {
        return SequentialAgent.builder()
                .name("顺序工作流：写作→评审")
                .description("先由写作助手产出内容，再由评审助手审阅并给出建议")
                .subAgents(List.of(writerAgent, reviewerAgent))
                .build();
    }

    /**
     * 路由智能体：根据用户问题类型，用 LLM 判断后单次分发给最合适的研究/编程/写作助手。
     * 与 Supervisor 的区别：路由只发生一次，子 Agent 执行完即结束（不循环回路由器）。
     */
    @Bean
    public LlmRoutingAgent routingAgent(ReactAgent researcherAgent, ReactAgent coderAgent, ReactAgent writerAgent) {
        return LlmRoutingAgent.builder()
                .model(deepSeek)
                .name("路由分发")
                .description("根据用户问题类型，路由到最合适的研究/编程/写作助手")
                .instruction("你是任务路由器。根据用户的问题，判断它更适合由【研究助手】【编程助手】还是【写作助手】处理，"
                        + "只输出其中一个助手的名字（研究助手 / 编程助手 / 写作助手）作为路由结果，不要解释。")
                .subAgents(List.of(researcherAgent, coderAgent, writerAgent))
                .build();
    }

    /**
     * graph-core 手写：意图路由工作流（原理入门）。
     * 这是理解"图编排"的最小示例：入口 → 分类节点（LLM 决定 RESEARCH/CODING/WRITING）
     * → 条件边分发到对应 worker 节点 → 结束。每个 worker 用编排 ChatClient 直接调用 LLM。
     *
     * 关键点：OverAllState 是贯穿全图的共享状态，需为每个 key 注册 KeyStrategy 决定"多节点写同一 key 时如何合并"：
     *  - input/route/result 用 ReplaceStrategy（覆盖）
     */
    @Bean
    public CompiledGraph intentRoutingGraph(ChatClient orchestrationClient) throws GraphStateException {
        KeyStrategyFactory ksf = () -> Map.<String, KeyStrategy>of(
                "input", new ReplaceStrategy(),
                "route", new ReplaceStrategy(),
                "result", new ReplaceStrategy()
        );
        StateGraph graph = new StateGraph(ksf);

        graph.addNode("classify", node_async((OverAllState state) -> {
            String input = state.value("input", "");
            String route = orchestrationClient.prompt()
                    .system("你是意图分类器。只输出 RESEARCH / CODING / WRITING 之一，不要解释。")
                    .user(input)
                    .call().content().trim().toUpperCase();
            return Map.of("route", route);
        }));

        graph.addNode("research", node_async((OverAllState state) -> {
            String out = orchestrationClient.prompt()
                    .system("你是研究助手，给出结构清晰的研究结果。")
                    .user(state.value("input", ""))
                    .call().content();
            return Map.of("result", "[研究] " + out);
        }));

        graph.addNode("coding", node_async((OverAllState state) -> {
            String out = orchestrationClient.prompt()
                    .system("你是编程助手，代码用代码块呈现。")
                    .user(state.value("input", ""))
                    .call().content();
            return Map.of("result", "[编程] " + out);
        }));

        graph.addNode("writing", node_async((OverAllState state) -> {
            String out = orchestrationClient.prompt()
                    .system("你是写作助手，语言流畅、结构清晰。")
                    .user(state.value("input", ""))
                    .call().content();
            return Map.of("result", "[写作] " + out);
        }));

        graph.addEdge(START, "classify");
        // 条件边：classify 节点把 route 写入 state，这里根据 route 决定下一个节点
        graph.addConditionalEdges("classify",
                edge_async((OverAllState state) -> state.value("route", "WRITING")),
                Map.of("RESEARCH", "research", "CODING", "coding", "WRITING", "writing"));
        graph.addEdge("research", END);
        graph.addEdge("coding", END);
        graph.addEdge("writing", END);

        return graph.compile();
    }

    /**
     * graph-core 手写：Supervisor 多智能体（监督者循环路由）。
     * 这是"多智能体协作"的本质演示：一个 supervisor 节点用 LLM 决定下一步调用哪个 worker，
     * worker 执行完回到 supervisor，直到 supervisor 判定 FINISH 才结束。worker 直接复用前面声明的
     * 子 Agent（researcherAgent / coderAgent），体现"Agent 作为可调度单元"。
     *
     * 注意：SAA 1.1.2.2 的 agent-framework 尚未提供封装好的 SupervisorAgent 类，所以这里用 graph-core 手写，
     * 正好也演示了"高层模式底层其实就是一张图"。
     * 为防 LLM 不收敛，用 step 计数（最多 3 步）强制终止。
     */
    @Bean
    public CompiledGraph supervisorGraph(ChatClient orchestrationClient,
                                         ReactAgent researcherAgent, ReactAgent coderAgent) throws GraphStateException {
        KeyStrategyFactory ksf = () -> Map.<String, KeyStrategy>of(
                "input", new ReplaceStrategy(),
                "next", new ReplaceStrategy(),
                "step", new ReplaceStrategy(),
                "result", new AppendStrategy()   // 多次 worker 输出追加到同一 key，形成完整过程记录
        );
        StateGraph graph = new StateGraph(ksf);

        graph.addNode("supervisor", node_async((OverAllState state) -> {
            int step = state.value("step", 0);
            if (step >= 3) {
                return Map.of("next", "FINISH", "step", step + 1);
            }
            String input = state.value("input", "");
            String decision = orchestrationClient.prompt()
                    .system("你是监督者。根据任务，决定下一步调用【researcher 研究员】还是【coder 程序员】，"
                            + "或任务已完成则输出【FINISH】。只输出 researcher / coder / FINISH 之一，不要解释。")
                    .user("当前任务：" + input)
                    .call().content().trim().toUpperCase();
            String next = decision.contains("FINISH") ? "FINISH"
                    : decision.contains("RESEARCH") ? "researcher"
                    : decision.contains("CODER") ? "coder" : "FINISH";
            return Map.of("next", next, "step", step + 1);
        }));

        graph.addNode("researcher", node_async((OverAllState state) -> {
            Optional<OverAllState> r = researcherAgent.invoke(state.value("input", ""));
            return Map.of("result", "\n[研究员] " + extractOutput(r));
        }));

        graph.addNode("coder", node_async((OverAllState state) -> {
            Optional<OverAllState> r = coderAgent.invoke(state.value("input", ""));
            return Map.of("result", "\n[程序员] " + extractOutput(r));
        }));

        graph.addEdge(START, "supervisor");
        graph.addConditionalEdges("supervisor",
                edge_async((OverAllState state) -> state.value("next", "FINISH")),
                Map.of("researcher", "researcher", "coder", "coder", "FINISH", END));
        // worker 执行完回到 supervisor，形成"监督者循环"
        graph.addEdge("researcher", "supervisor");
        graph.addEdge("coder", "supervisor");

        return graph.compile();
    }

    /** 从 ReactAgent.invoke 返回的 OverAllState 中提取最终文本输出（取 messages 列表最后一条） */
    private String extractOutput(Optional<OverAllState> stateOpt) {
        if (stateOpt.isEmpty()) {
            return "(无输出)";
        }
        OverAllState state = stateOpt.get();
        Object messages = state.value("messages").orElse(null);
        if (messages instanceof List<?> list && !list.isEmpty()) {
            Object last = list.get(list.size() - 1);
            if (last instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
                return am.getText();
            }
            return last.toString();
        }
        return state.value("result", "(无输出)");
    }
}
