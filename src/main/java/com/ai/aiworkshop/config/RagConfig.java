package com.ai.aiworkshop.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阶段 3 RAG：向量库 + 启动时文档建索引。
 *
 * 设计要点：
 * 1. 向量库用 SimpleVectorStore（Spring AI 内存实现，零额外依赖，适合演示/测试）。
 *    它底层用 Ollama 的 bge-m3（EmbeddingModel，由 spring-ai-starter-model-ollama 自动注入）做向量化。
 * 2. 应用启动时（CommandLineRunner）扫描 classpath:rag-docs/* 下的 markdown / txt，
 *    读取内容 → 构造 Document（带 source 元数据）→ TokenTextSplitter 切片 → vectorStore.add 建索引。
 *    因此每次启动会自动重建索引，重启不丢"知识"（只是重新 embedding 一次）。
 * 3. 这块和"对话记忆"完全解耦：VectorStore 存的是文档向量，ChatMemory 存的是对话历史。
 */
@Configuration
public class RagConfig {

    /** 启动时扫 rag-docs 下的 .md/.txt 文档 */
    @Value("classpath:rag-docs/*")
    private Resource[] documents;

    /**
     * 内存向量库。构造必须传 EmbeddingModel（Spring 会自动注入 Ollama 的 bge-m3）。
     * SimpleVectorStore.builder(EmbeddingModel) 是 Spring AI 1.1.2 的标准写法。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 启动即建索引：把 rag-docs 里的文档读进来、切片、写入向量库。
     * 用 CommandLineRunner 保证在 Web 容器就绪后、首次请求前完成。
     */
    @Bean
    public org.springframework.boot.CommandLineRunner loadRagDocuments(VectorStore vectorStore) {
        return args -> {
            TokenTextSplitter splitter = new TokenTextSplitter(); // 默认按 token 切，带重叠
            List<Document> allDocs = new ArrayList<>();

            for (Resource res : documents) {
                if (!res.exists()) {
                    continue;
                }
                String fileName = res.getFilename();
                if (fileName == null || !(fileName.endsWith(".md") || fileName.endsWith(".txt"))) {
                    continue;
                }
                try {
                    String content = res.getContentAsString(StandardCharsets.UTF_8);
                    // 每个文件先构造成一个 Document，带 source 元数据（方便检索结果溯源）
                    Document doc = new Document(content, Map.of("source", fileName));
                    // 切片：把一个长文档切成多个小片段，每段单独向量化，检索更精准
                    List<Document> chunks = splitter.split(List.of(doc));
                    // 把文件名写进每个 chunk 的元数据，便于前端展示"答案来自哪个文件"
                    chunks.forEach(c -> c.getMetadata().put("source", fileName));
                    allDocs.addAll(chunks);
                } catch (IOException e) {
                    throw new RuntimeException("加载 RAG 文档失败：" + fileName, e);
                }
            }

            if (!allDocs.isEmpty()) {
                vectorStore.add(allDocs);
                System.out.println("[RAG] 已加载并建索引 " + allDocs.size() + " 个文档片段（来自 "
                        + documents.length + " 个源文件）");
            } else {
                System.out.println("[RAG] 未找到 rag-docs 下的文档，跳过建索引");
            }
        };
    }
}
