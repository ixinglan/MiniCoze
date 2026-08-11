package com.ai.aiworkshop.tools;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 工具 4：知识库检索（业务闭环的核心 —— 把阶段 3 的 RAG 能力包装成模型可调用的工具）。
 *
 * 思路：模型不再“被动”地由 QuestionAnswerAdvisor 自动注入资料，而是“主动”决定
 * “这个问题我需要查资料”，调用本工具对向量库做 similaritySearch，拿到相关片段后自行组织回答。
 * 这比 Advisor 模式更灵活（模型可以多次检索、或结合其它工具）。
 *
 * 复用同一个 VectorStore Bean（memory / Milvus 由 RagConfig 条件注入），零额外接线。
 */
@Service
public class RagQueryTool {

    private final VectorStore vectorStore;
    private final ToolCallRecorder recorder;

    public RagQueryTool(VectorStore vectorStore, ToolCallRecorder recorder) {
        this.vectorStore = vectorStore;
        this.recorder = recorder;
    }

    @Tool(description = "从本地知识库中检索与问题相关的文档片段。当用户的问题需要查阅你的私有资料/"
            + "知识库文档时调用，例如询问资料里提到的概念、步骤、条款等。返回最相关的若干片段文本。")
    public String queryKnowledgeBase(
            @ToolParam(description = "检索问题，用简短的关键词或问句") String query) {
        recorder.record("queryKnowledgeBase", Map.of("query", query));

        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.5)
                .build());

        if (docs == null || docs.isEmpty()) {
            return "知识库中未找到与「" + query + "」相关的内容。";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            Object source = d.getMetadata() != null ? d.getMetadata().get("source") : null;
            sb.append("【片段 ").append(i + 1).append("】");
            if (source != null) sb.append("(来源: ").append(source).append(") ");
            sb.append(d.getText()).append("\n");
        }
        return sb.toString();
    }
}
