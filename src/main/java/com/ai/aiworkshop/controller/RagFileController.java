package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.entity.RagFileDO;
import com.ai.aiworkshop.service.RagFileService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    public RagFileController(RagFileService ragFileService) {
        this.ragFileService = ragFileService;
    }

    /** 单文件上传：field name = "file" */
    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return toMap(ragFileService.upload(file));
    }

    /** 批量上传：field name = "files"（多文件） */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Map<String, Object>> uploadBatch(@RequestParam("files") MultipartFile[] files) throws IOException {
        List<Map<String, Object>> res = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            if (!f.isEmpty()) res.add(toMap(ragFileService.upload(f)));
        }
        return res;
    }

    /** 手动向量化：把文件切片 + 向量化后写入向量库 */
    @PostMapping("/{id}/index")
    public Map<String, Object> index(@PathVariable String id) throws IOException {
        ragFileService.index(id);
        return Map.of("ok", true, "id", id, "status", "indexed");
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
