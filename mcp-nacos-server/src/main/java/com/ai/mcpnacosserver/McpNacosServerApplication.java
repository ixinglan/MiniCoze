package com.ai.mcpnacosserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nacos MCP Server —— 独立运行的 MCP 服务端。
 * <p>
 * 职责：通过 Spring AI MCP Server WebFlux 暴露工具端点（SSE），
 * 并注册到 Nacos 供主应用（ai-workshop）自动发现和调用。
 * 与主应用完全独立——独立端口、独立进程、独立启动。
 * </p>
 */
@SpringBootApplication
public class McpNacosServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpNacosServerApplication.class, args);
    }
}
