package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.entity.RagFileDO;
import com.ai.aiworkshop.service.DuplicateFileException;
import com.ai.aiworkshop.service.RagFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 文件管理接口。
 * - 上传（单/批量）：只落盘 + 写元数据，不自动向量化（手动控制是否进知识库）
 * - 向量化 / 移除索引 / 删除：手动控制检索增强的“开关”
 * - 列表：返回文件名/类型/大小/索引状态，供前端文件面板展示
 */
@RestController
@RequestMapping("/api/rag/files")
public class RagFileController {

    private final RagFileService ragFileService;
    private final ObjectMapper objectMapper;

    public RagFileController(RagFileService ragFileService, ObjectMapper objectMapper) {
        this.ragFileService = ragFileService;
        this.objectMapper = objectMapper;
    }

    /** 单文件上传：field name = "file"。内容重复（已上传过）返回 ok=false + duplicate=true，不落盘 */
    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        try {
            return toMap(ragFileService.upload(file));
        } catch (DuplicateFileException ex) {
            return Map.of("ok", false, "duplicate", true,
                    "filename", ex.getFilename(), "status", ex.getStatus(),
                    "id", ex.getExistingId(), "message", "文件已上传过：" + ex.getFilename());
        }
    }

    /** 批量上传：field name = "files"（多文件）。逐文件去重，重复的标记 duplicate=true 并跳过 */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Map<String, Object>> uploadBatch(@RequestParam("files") MultipartFile[] files) throws IOException {
        List<Map<String, Object>> res = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            try {
                res.add(toMap(ragFileService.upload(f)));
            } catch (DuplicateFileException ex) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", false);
                m.put("duplicate", true);
                m.put("filename", ex.getFilename());
                m.put("status", ex.getStatus());
                m.put("id", ex.getExistingId());
                m.put("message", "已上传过，已跳过：" + ex.getFilename());
                res.add(m);
            }
        }
        return res;
    }

    /** 上传前重复预检：传入内容哈希列表，返回已存在的 hash -> {filename,status,id} */
    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        List<String> hashes = (List<String>) body.get("hashes");
        return Map.of("duplicates", ragFileService.checkDuplicates(hashes));
    }

    /**
     * 手动向量化（流式真进度）：后端边解析/切片/逐片嵌入/写入，边往前端推 NDJSON 事件。
     * 用 StreamingResponseBody 把响应体以流的形式写出，每收到一个进度事件就 flush 一行 JSON，
     * 前端用 fetch + ReadableStream 逐行解析即可实时渲染真实进度条；失败也会推 error 事件。
     */
    @PostMapping(value = "/{id}/index", produces = "application/x-ndjson")
    public StreamingResponseBody index(@PathVariable String id) {
        return outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            try {
                ragFileService.indexWithProgress(id, event -> {
                    try {
                        writer.write(objectMapper.writeValueAsString(event));
                        writer.write('\n');
                        writer.flush();   // 每来一个事件立即推给前端，实现“真进度”
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                // 整段失败：补发一条 error 事件，让前端把进度条变红并提示原因
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("stage", "error");
                err.put("step", 4);
                err.put("totalSteps", 4);
                err.put("message", "向量化失败");
                err.put("detail", e.getMessage());
                err.put("percent", 100);
                err.put("done", false);
                err.put("error", true);
                writer.write(objectMapper.writeValueAsString(err));
                writer.write('\n');
                writer.flush();
            }
        };
    }

    /** 移除索引：从向量库删掉该文件的向量，但保留文件与记录 */
    @DeleteMapping("/{id}/index")
    public Map<String, Object> removeIndex(@PathVariable String id) {
        ragFileService.removeIndex(id);
        return Map.of("ok", true, "id", id, "status", "uploaded");
    }

    /** 彻底删除：移除索引 + 删物理文件 + 删记录 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        ragFileService.delete(id);
        return Map.of("ok", true, "id", id);
    }

    /** 文件列表（含索引状态） */
    @GetMapping
    public List<Map<String, Object>> list() {
        return ragFileService.list();
    }

    private Map<String, Object> toMap(RagFileDO d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("filename", d.getFilename());
        m.put("contentType", d.getContentType());
        m.put("size", d.getSize());
        m.put("status", d.getStatus());
        m.put("createdAt", d.getCreatedAt());
        m.put("indexedAt", d.getIndexedAt());
        return m;
    }
}
