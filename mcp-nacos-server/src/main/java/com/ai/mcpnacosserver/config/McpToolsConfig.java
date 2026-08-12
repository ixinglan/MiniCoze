package com.ai.mcpnacosserver.config;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

/**
 * Nacos MCP Server 工具注册 —— 暴露给主应用通过 Nacos 服务发现调用的 4 个工具。
 * <p>
 * 关键原理：
 *  - spring-ai-starter-mcp-server-webflux 启动时扫描所有 @Tool 注解的 Spring Bean，
 *    自动生成 MCP 工具列表并通过 SSE 端点暴露。
 *  - Nacos Discovery 将该服务注册到 Nacos，主应用的 Nacos MCP Client 自动发现工具。
 *  - 工具设计遵循"无状态 + 幂等"原则。
 * </p>
 *
 * <p>本服务提供 4 个工具（与 Python MCP Server 互补，不重复）：
 *  - generate_uuid：生成 UUID
 *  - random_number：生成随机整数
 *  - text_stats：文本统计（字数/字符数/行数）
 *  - server_status：查询服务健康状态
 * </p>
 *
 * 注意：@Tool 如果有变化，需要改配置文件里的版本号，例：比如 tool s数量变化，就需要改版本号,nacos里就会生成新版本配置
 */
@Service  // 注册为 Spring Bean，使 @Tool 方法能被 MCP Server 扫描到
public class McpToolsConfig {

    private final Random random = new Random();
    private final String startTime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    // ===== 工具 1：生成 UUID =====
    @Tool(description = "生成 UUID v4 唯一标识符。count 指定生成数量（1-10），默认 1 个。"
            + "适用场景：给工单、文件、记录分配唯一 ID。")
    public String generateUuid(
            @ToolParam(description = "生成数量，默认 1，最多 10") int count) {
        if (count <= 0) count = 1;
        if (count > 10) count = 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append("\n");
            sb.append(UUID.randomUUID().toString());
        }
        return count == 1 ? sb.toString() : "已生成 " + count + " 个 UUID:\n" + sb;
    }

    // ===== 工具 2：随机数 =====
    @Tool(description = "在指定范围内生成一个随机整数（包含最小值和最大值）。"
            + "适用场景：抽签、模拟数据、测试。")
    public String randomNumber(
            @ToolParam(description = "最小值（含），默认 1") int min,
            @ToolParam(description = "最大值（含），默认 100，不超过 100000") int max) {
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        if (max > 100000) max = 100000;
        int result = random.nextInt(max - min + 1) + min;
        return String.format("随机数 [%d, %d]: %d", min, max, result);
    }

    // ===== 工具 3：文本统计 =====
    @Tool(description = "统计一段文本的字数、字符数、行数。中文按字符计字，英文按单词计字。"
            + "适用场景：检查文章长度、校对文本规模。")
    public String textStats(
            @ToolParam(description = "待统计的文本内容") String text) {
        if (text == null || text.isBlank()) {
            return "文本为空，无可统计内容。";
        }
        int charCount = text.length();
        // 中文+英文混合字数
        int chineseChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            }
        }
        String englishOnly = text.replaceAll("[\\p{IsHan}]", " ").trim();
        int englishWords = englishOnly.isEmpty() ? 0 : englishOnly.split("\\s+").length;
        int wordCount = chineseChars + englishWords;
        int lineCount = text.split("\\r?\\n").length;

        return String.format(
                "文本统计：总字符 %d，字数 %d（中文字 %d + 英文词 %d），行数 %d",
                charCount, wordCount, chineseChars, englishWords, lineCount);
    }

    // ===== 工具 4：服务健康状态 =====
    @Tool(description = "查询 Nacos MCP Server 的健康状态，返回启动时间和 JVM 内存使用情况。"
            + "适用场景：Agent 需要确认远程 MCP Server 是否在线。")
    public String serverStatus() {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long maxMB = rt.maxMemory() / 1024 / 1024;
        return String.format(
                "Nacos MCP Server 运行正常。启动时间: %s，JVM 内存: %dMB / %dMB",
                startTime, usedMB, maxMB);
    }
}
