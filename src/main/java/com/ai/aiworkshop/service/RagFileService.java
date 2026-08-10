package com.ai.aiworkshop.service;

import com.ai.aiworkshop.entity.RagFileDO;
import com.ai.aiworkshop.mapper.RagFileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RAG 文件管理：上传落盘 + 手动向量化（检索增强的“开关”）+ 移除索引 + 删除。
 *
 * 设计要点：
 * 1. 上传只落盘 + 写元数据（status=uploaded），不自动向量化——把“是否进知识库”的控制权交给用户。
 * 2. 向量化（index）：Tika 一把解析 PDF/Word/Excel/PPT/TXT/MD → TokenTextSplitter 切片
 *    → bge-m3 向量化 → upsert 进向量库，并在每个片段元数据里写入 source(文件名) + fileId。
 *    每个片段用稳定 ID「fileId#序号」，便于后续按 fileId 精准移除这批向量。
 * 3. 移除索引（removeIndex）：按记录里的 docIds 调 vectorStore.delete，但保留文件与记录。
 * 4. 删除（delete）：移除索引 + 删物理文件 + 删记录，三者原子。
 * 向量库具体实现由注入的 VectorStore 决定（内存 SimpleVectorStore / Milvus），本服务不耦合。
 */
@Service
public class RagFileService {

    private final RagFileMapper ragFileMapper;
    private final VectorStore vectorStore;
    private final String storageDir;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public RagFileService(RagFileMapper ragFileMapper,
                           VectorStore vectorStore,
                           @Value("${rag.storage.dir:./data/rag-files}") String storageDir) {
        this.ragFileMapper = ragFileMapper;
        this.vectorStore = vectorStore;
        // 转绝对路径，避免相对路径被容器（Tomcat）解析到临时目录
        this.storageDir = Paths.get(storageDir).toAbsolutePath().toString();
        try {
            Files.createDirectories(Paths.get(this.storageDir));
        } catch (IOException ignored) { }
    }

    /** 去掉路径分隔符与 ..，防止路径穿越 + 重名覆盖 */
    private static String sanitize(String name) {
        if (name == null) return "file";
        String base = name.replaceAll("[/\\\\]", "_").replaceAll("\\.\\.", "_");
        return base.isEmpty() ? "file" : base;
    }

    /** 单文件上传：落盘 + 写元数据（status=uploaded） */
    public RagFileDO upload(MultipartFile file) throws IOException {
        String id = UUID.randomUUID().toString();
        String original = file.getOriginalFilename();
        String storedName = id + "_" + sanitize(original);
        Path dir = Paths.get(storageDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(storedName);
        // 用流拷贝而非 transferTo：避免 Tomcat 把相对路径解析到容器临时目录导致 FileNotFound
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        RagFileDO d = new RagFileDO();
        d.setId(id);
        d.setFilename(original != null ? original : storedName);
        d.setContentType(file.getContentType());
        d.setSize(file.getSize());
        d.setStoragePath(target.toString());
        d.setStatus("uploaded");
        d.setCreatedAt(LocalDateTime.now());
        ragFileMapper.insert(d);
        return d;
    }

    /** 文件列表（含索引状态），按上传时间倒序 */
    public List<Map<String, Object>> list() {
        List<RagFileDO> list = ragFileMapper.selectList(
                Wrappers.<RagFileDO>query().orderByDesc("created_at"));
        return list.stream().map(this::toMap).collect(Collectors.toList());
    }

    /**
     * 手动向量化：解析 → 切片 → 向量化 → upsert，状态切到 indexed。
     * 失败抛异常，由 Controller 转 500，记录保持 uploaded（不让脏状态进库）。
     */
    public void index(String fileId) throws IOException {
        RagFileDO d = ragFileMapper.selectById(fileId);
        if (d == null) throw new IllegalArgumentException("文件不存在: " + fileId);
        File f = new File(d.getStoragePath());
        if (!f.exists()) throw new IllegalStateException("物理文件缺失: " + d.getStoragePath());

        // Tika 根据文件扩展名/内容类型自动选解析器，PDF/Word/Excel/PPT/TXT/MD 一把覆盖
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(f));
        List<Document> parsed = reader.get();

        // 每个解析段先打上 source（原始文件名），方便检索结果溯源
        parsed.forEach(doc -> doc.getMetadata().put("source", d.getFilename()));

        // 切片成更小片段，每段单独向量化，检索更精准
        List<Document> chunks = new ArrayList<>();
        for (Document doc : parsed) {
            chunks.addAll(splitter.split(List.of(doc)));
        }

        // 分配稳定文档 ID（fileId#序号），并补 fileId 元数据，便于按文件移除索引
        // 注意：Document 无 setId，需通过「带 id 的构造器」重建（id 为第一参数，content 用 getText()）
        List<String> docIds = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = new LinkedHashMap<>(chunk.getMetadata());
            meta.put("fileId", fileId);
            String docId = fileId + "#" + i;
            chunks.set(i, new Document(docId, chunk.getText(), meta));
            docIds.add(docId);
        }

        vectorStore.add(chunks);

        d.setStatus("indexed");
        d.setDocIds(String.join(",", docIds));
        d.setIndexedAt(LocalDateTime.now());
        ragFileMapper.updateById(d);
    }

    /** 移除索引：按 docIds 删向量，但保留文件与记录（状态回退 uploaded） */
    public void removeIndex(String fileId) {
        RagFileDO d = ragFileMapper.selectById(fileId);
        if (d == null) throw new IllegalArgumentException("文件不存在: " + fileId);
        if (d.getDocIds() != null && !d.getDocIds().isEmpty()) {
            List<String> ids = List.of(d.getDocIds().split(","));
            try {
                vectorStore.delete(ids);   // 向量库为空/已删时忽略异常
            } catch (Exception ignored) { }
        }
        d.setStatus("uploaded");
        d.setDocIds(null);
        d.setIndexedAt(null);
        ragFileMapper.updateById(d);
    }

    /** 彻底删除：移除索引 + 删物理文件 + 删记录 */
    public void delete(String fileId) {
        RagFileDO d = ragFileMapper.selectById(fileId);
        if (d == null) return;
        removeIndex(fileId);
        try {
            Files.deleteIfExists(Paths.get(d.getStoragePath()));
        } catch (IOException ignored) { }
        ragFileMapper.deleteById(fileId);
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
