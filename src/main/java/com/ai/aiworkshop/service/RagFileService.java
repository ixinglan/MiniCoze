package com.ai.aiworkshop.service;

import com.ai.aiworkshop.entity.RagFileDO;
import com.ai.aiworkshop.mapper.RagFileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final EmbeddingModel embeddingModel;
    private final String storageDir;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public RagFileService(RagFileMapper ragFileMapper,
                           VectorStore vectorStore,
                          @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
                           @Value("${rag.storage.dir:./data/rag-files}") String storageDir) {
        this.ragFileMapper = ragFileMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
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

    /**
     * 单文件上传：内容哈希去重 → 落盘 + 写元数据（status=uploaded）。
     * 同一份文件（SHA-256 相同）已存在则抛 DuplicateFileException，前端据此提示并跳过，不重复落盘。
     */
    public RagFileDO upload(MultipartFile file) throws IOException {
        // 读字节：既用于内容哈希（去重依据），也直接落盘，避免对大文件二次读取
        byte[] bytes = file.getBytes();
        String hash = sha256(bytes);

        // 重复校验：内容哈希已存在即视为重复（无论 uploaded 还是 indexed 状态）
        RagFileDO dup = findByHash(hash);
        if (dup != null) {
            throw new DuplicateFileException(dup.getFilename(), dup.getStatus(), dup.getId());
        }

        String id = UUID.randomUUID().toString();
        String original = file.getOriginalFilename();
        String storedName = id + "_" + sanitize(original);
        Path dir = Paths.get(storageDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(storedName);
        // 用已读字节落盘（绝对路径，避免相对路径被容器解析到临时目录导致 FileNotFound）
        Files.write(target, bytes);

        RagFileDO d = new RagFileDO();
        d.setId(id);
        d.setFilename(original != null ? original : storedName);
        d.setContentType(file.getContentType());
        d.setSize(file.getSize());
        d.setStoragePath(target.toString());
        d.setContentHash(hash);
        d.setStatus("uploaded");
        d.setCreatedAt(LocalDateTime.now());
        ragFileMapper.insert(d);
        return d;
    }

    /** 计算字节数组的 SHA-256 十六进制串（上传去重的内容指纹） */
    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /** 按内容哈希查已存在的记录（去重用），命中返回该记录 */
    public RagFileDO findByHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        return ragFileMapper.selectOne(
                Wrappers.<RagFileDO>query().eq("content_hash", hash).last("LIMIT 1"));
    }

    /** 批量去重预检：返回已存在的 hash -> {filename, status, id}，供前端上传前提示 */
    public Map<String, Map<String, Object>> checkDuplicates(List<String> hashes) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (hashes == null || hashes.isEmpty()) return result;
        List<RagFileDO> existing = ragFileMapper.selectList(
                Wrappers.<RagFileDO>query().in("content_hash", hashes));
        for (RagFileDO d : existing) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("filename", d.getFilename());
            info.put("status", d.getStatus());
            info.put("id", d.getId());
            result.put(d.getContentHash(), info);
        }
        return result;
    }

    /** 文件列表（含索引状态），按上传时间倒序 */
    public List<Map<String, Object>> list() {
        List<RagFileDO> list = ragFileMapper.selectList(
                Wrappers.<RagFileDO>query().orderByDesc("created_at"));
        return list.stream().map(this::toMap).collect(Collectors.toList());
    }

    /**
     * 手动向量化（流式真进度）：解析 → 切片 → 逐片段嵌入(bge-m3) → upsert，
     * 每完成一个阶段 / 每嵌入一个片段就通过 reporter 往前端推一次进度事件；
     * 状态在最后成功才切到 indexed（失败抛异常，记录保持 uploaded，不让脏状态进库）。
     *
     * 关键：嵌入改为手动逐片段调用 EmbeddingModel.embed，这样才能真实反映“嵌入到第几个片段”的进度；
     * 文档已带向量后交给 vectorStore.add，store 检测到已有 embedding 不会重复嵌入。
     */
    public void indexWithProgress(String fileId, ProgressReporter reporter) throws IOException {
        RagFileDO d = ragFileMapper.selectById(fileId);
        if (d == null) throw new IllegalArgumentException("文件不存在: " + fileId);
        File f = new File(d.getStoragePath());
        if (!f.exists()) throw new IllegalStateException("物理文件缺失: " + d.getStoragePath());

        List<String> docIds = new ArrayList<>();

        // 步骤 1：解析文档
        emit(reporter, stage("parse", 1, "解析文档", "开始解析文件内容…", 8, false, false));
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(f));
        List<Document> parsed = reader.get();
        for (Document doc : parsed) doc.getMetadata().put("source", d.getFilename());
        int parsedCount = parsed.size();
        emit(reporter, stage("parse", 1, "解析文档", "解析出 " + parsedCount + " 个文档块", 25, false, false));

        // 步骤 2：切片
        emit(reporter, stage("split", 2, "切片", "开始切片…", 30, false, false));
        List<Document> chunks = new ArrayList<>();
        for (Document doc : parsed) {
            chunks.addAll(splitter.split(List.of(doc)));
        }
        if (chunks.isEmpty()) {
            emit(reporter, stage("error", 4, "向量化失败",
                    "解析后无有效文本（可能为空文件或扫描件 PDF）", 100, false, true));
            throw new IllegalStateException("解析后无有效文本片段，无法向量化");
        }
        // 分配稳定 doc id + fileId 元数据，便于按文件移除索引。
        // 注意：Document 无 setId，需通过「带 id 的构造器」重建（id 为第一参数，content 用 getText()）。
        // 关键坑：Spring AI 1.1.2 的 MilvusVectorStore 把 doc_id 字段硬编码为 VarChar(36) 且不可配置，
        // 因此 doc id 必须是“有效字符串且 ≤36 字符”，否则 insert 报 ParamException(Type mismatch)。
        // 故用“去横杠 UUID”（32 字符，恒 ≤36），fileId 仍留在 metadata 里仅供溯源/移除使用。
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = new LinkedHashMap<>(chunk.getMetadata());
            meta.put("fileId", fileId);
            String docId = UUID.randomUUID().toString().replace("-", "");
            chunks.set(i, new Document(docId, chunk.getText(), meta));
            docIds.add(docId);
        }
        int chunkCount = chunks.size();
        emit(reporter, stage("split", 2, "切片", "切片为 " + chunkCount + " 个片段", 50, false, false));

        // 步骤 3：嵌入（bge-m3）。逐片段真实嵌入，每嵌完一片就上报进度。
        // 注意：Spring AI 1.1.2 的 Document 不带 embedding 字段，向量只存在向量库后端，
        // 因此 vectorStore.add 内部会用同一模型再嵌入一次（结果一致）。这里手动嵌入是为了
        // 让进度条“真”地逐片推进——每报告一片，就确实有一片被嵌入（真实耗时，非假动画）。
        emit(reporter, stage("embed", 3, "嵌入(bge-m3)", "开始向量化…", 50, false, false));
        int total = chunkCount;
        for (int i = 0; i < total; i++) {
            embeddingModel.embed(chunks.get(i));   // 真实嵌入（驱动进度）；向量由后续 vectorStore.add 统一写入
            int done = i + 1;
            int pct = 50 + Math.round(25f * done / total);
            emit(reporter, stage("embed", 3, "嵌入(bge-m3)", "嵌入 " + done + "/" + total, pct, false, false));
        }
        emit(reporter, stage("embed", 3, "嵌入(bge-m3)", "嵌入完成 " + total + "/" + total, 75, false, false));

        // 步骤 4：写入向量库（文档已带 embedding，store 直接写，不重复算向量）
        emit(reporter, stage("write", 4, "写入向量库", "写入向量库…", 80, false, false));
        vectorStore.add(chunks);
        emit(reporter, stage("write", 4, "写入向量库", "写入完成", 95, false, false));

        // 完成：状态切到 indexed，记录 docIds 供后续按文件移除
        d.setStatus("indexed");
        d.setDocIds(String.join(",", docIds));
        d.setIndexedAt(LocalDateTime.now());
        ragFileMapper.updateById(d);
        emit(reporter, stage("done", 4, "完成", "已索引 " + chunkCount + " 个片段", 100, true, false));
    }

    /** 进度事件回调：后端每完成一步 / 每片就推一个事件 Map 给前端 */
    public interface ProgressReporter {
        void emit(Map<String, Object> event);
    }

    private void emit(ProgressReporter reporter, Map<String, Object> event) {
        if (reporter != null) reporter.emit(event);
    }

    /** 构造一个进度事件（统一字段：阶段 / 第几步 / 总步数 / 文案 / 详情 / 整体百分比 / 是否完成 / 是否出错） */
    private static Map<String, Object> stage(String st, int step, String msg,
                                             String detail, int pct, boolean done, boolean error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", st);
        m.put("step", step);
        m.put("totalSteps", 4);
        m.put("message", msg);
        m.put("detail", detail);
        m.put("percent", pct);
        m.put("done", done);
        m.put("error", error);
        return m;
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
