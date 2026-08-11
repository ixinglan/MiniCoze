package com.ai.aiworkshop.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 第二个模型：本地 Ollama 的 embedding 模型（bge-m3）。
 *
 * 它和 DeepSeek 的 ChatModel 是两套完全不同的能力：
 * - ChatModel 负责"理解/生成文本"（对话、视觉理解）
 * - EmbeddingModel 负责"把文本转成向量"（RAG 入库、语义检索）
 *
 * 这就是 Spring AI 抽象的价值：应用里可以混用多家供应商，
 * 各自只暴露统一的 ChatModel / EmbeddingModel 接口，业务代码不耦合具体厂商。
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(@Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /** 把一段文本转成向量数组（RAG 检索增强的前置步骤） */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /** 当前 embedding 模型的向量维度（建向量库时要对齐） */
    public int dimensions() {
        return embed("__dim_probe__").length;
    }
}
