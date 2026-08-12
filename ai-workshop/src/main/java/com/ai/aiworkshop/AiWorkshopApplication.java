package com.ai.aiworkshop;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@SpringBootApplication
public class AiWorkshopApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiWorkshopApplication.class, args);
    }

    @Bean
    public CommandLineRunner checkMcpBeans(ApplicationContext context) {
        return args -> {
            System.out.println("\n=== 检查 MCP ToolCallbackProvider Bean ===");
            Map<String, ToolCallbackProvider> providers =
                    context.getBeansOfType(ToolCallbackProvider.class);

            for (Map.Entry<String, ToolCallbackProvider> entry : providers.entrySet()) {
                String beanName = entry.getKey();
                ToolCallbackProvider provider = entry.getValue();
                int toolCount = provider.getToolCallbacks().length;
                System.out.println("  Bean: " + beanName + " (工具数: " + toolCount + ")");

                // 列出所有工具名称
                for (ToolCallback callback : provider.getToolCallbacks()) {
                    System.out.println("    - " + callback.getToolDefinition().name());
                }
            }
            System.out.println("========================================\n");
        };
    }
}
