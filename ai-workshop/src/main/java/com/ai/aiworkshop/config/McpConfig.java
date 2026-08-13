package com.ai.aiworkshop.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 阶段 7 MCP 工具汇聚配置。
 * <p>
 * Spring AI MCP Client 自动配置会在启动时：
 * - stdio 模式：启动 Python 进程，通过 JSON-RPC 握手发现工具，为每个工具创建 ToolCallback Bean
 * - Nacos SSE 模式：从 Nacos 发现 MCP Server SSE 端点，连接后自动发现工具
 * <p>
 * 本配置统一收集所有 MCP 来源的 ToolCallback，封装成 List，供 Agent6Config 合并到 Agent 工具列表。
 * 这样 Agent 就能无缝使用远程 MCP 工具，就像使用本地 @Tool 方法一样。
 * </p>
 */
@Configuration
public class McpConfig {

    /**
     * required=false：MCP Client 是可选的（如容器部署禁用 MCP、或连接失败时），
     * 该 Bean 可能不存在 —— 注入 null，下方判空返回空列表，保证应用照常启动。
     */
    @Qualifier("mcpAsyncToolCallbacks")
    @Autowired(required = false)
    ToolCallbackProvider mcpToolCallbackProvider;

    /**
     * 汇聚所有 MCP 来源的 ToolCallback（Python stdio + Nacos SSE）。
     * <p>
     * 注意：如果 MCP 未启用或连接失败，Spring 会注入一个空列表，不会导致启动失败。
     * 这意味着 MCP 是可选的增强能力，不影响核心功能。
     * </p>
     */
    @Bean
    public List<ToolCallback> mcpToolCallbacks() {
        if (mcpToolCallbackProvider != null && mcpToolCallbackProvider.getToolCallbacks() != null) {
            return Arrays.asList(mcpToolCallbackProvider.getToolCallbacks());
        }
        // 包装一层：防止直接返回 Spring 管理的集合引用被外部修改
        return new ArrayList<>();
    }
}
