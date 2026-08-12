package com.ai.mcpnacosserver.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpProviderConfig {
    @Bean
    public ToolCallbackProvider weatherTools(McpToolsConfig mcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(mcpTools).build();
    }
}
