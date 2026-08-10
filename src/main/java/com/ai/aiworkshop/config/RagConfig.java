package com.ai.aiworkshop.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
 * 1. 向量库由 {@code rag.vectorstore.type} 控制：
 *    - memory（默认）：SimpleVectorStore 内存实现，零额外依赖，适合演示/测试；
 *    - milvus：MilvusVectorStore（docker 起的 Milvus 单机版），生产级、可持久化、重启不丢索引。
 *    业务侧（QuestionAnswerAdvisor / RagService）只依赖 VectorStore 抽象，切换实现零改动。
 * 2. 向量化统一用 Ollama 的 bge-m3（EmbeddingModel，本地、无需云密钥）。维度 1024，
 *    因此 Milvus 的 embeddingDimension 也对齐 1024（错配会导致建集合失败）。
 * 3. 应用启动时（CommandLineRunner）扫描 classpath:rag-docs/* 的 .md/.txt 自动建索引
 *    （保留“开箱即有内容可问”的演示种子；用户上传的文件走 RagFileController 手动向量化）。
 * 4. 与对话记忆完全解耦：VectorStore 存文档向量，ChatMemory 存对话历史。
 */
@Configuration
public class RagConfig {

    /** 启动时扫 rag-docs 下的 .md/.txt 文档 */
    @Value("classpath:rag-docs/*")
    private Resource[] documents;

    /**
     * 向量库 Bean：按配置在内存 / Milvus 间切换。
     * 用 {@code @Primary} 确保覆盖 Spring AI 自带的 SimpleVectorStore 自动配置。
     * 默认走 memory，因此沙箱/无 Docker 环境也能直接启动跑通整条 RAG 管线。
     */
    @Bean
    @Primary
    public VectorStore vectorStore(EmbeddingModel embeddingModel,
                                   @Value("${rag.vectorstore.type:memory}") String type,
                                   @Value("${rag.vectorstore.milvus.host:localhost}") String host,
                                   @Value("${rag.vectorstore.milvus.port:19530}") int port,
                                   @Value("${rag.vectorstore.milvus.collection:vector_store}") String collection,
                                   @Value("${rag.vectorstore.milvus.dimension:1024}") int dimension) {
        if ("milvus".equalsIgnoreCase(type)) {
            // 仅当明确选择 milvus 才连接，避免无 Docker 时启动失败
            MilvusServiceClient client = new MilvusServiceClient(
                    ConnectParam.newBuilder()
                            .withUri("http://" + host + ":" + port)
                            .build());
            return MilvusVectorStore.builder(client, embeddingModel)
                    .collectionName(collection)
                    .databaseName("default")
                    .indexType(IndexType.IVF_FLAT)
                    .metricType(MetricType.COSINE)
                    .embeddingDimension(dimension)
                    .initializeSchema(true)   // 首次自动建集合（Spring AI 1.x 需显式开启）
                    .build();
        }
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
