-- 阶段 1：把对话记忆从 JVM 内存迁移到 MySQL 持久化存储
-- 由 spring.sql.init.mode=always 在应用启动时自动执行（IF NOT EXISTS 保证可重复执行）

-- 消息明细表：ChatMemoryRepository 实际读写这张表
-- conversation_id + message_index 作为联合主键，保证一个会话的消息有序、可整窗替换
CREATE TABLE IF NOT EXISTS chat_memory (
    conversation_id VARCHAR(64)  NOT NULL,
    message_index  INT           NOT NULL,
    message_type   VARCHAR(16)   NOT NULL,   -- USER / ASSISTANT / SYSTEM / TOOL
    content        TEXT,
    metadata       TEXT,                      -- Jackson 序列化后的 JSON（还原时暂未用，留作扩展）
    created_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, message_index),
    INDEX idx_cm_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话元数据表：给前端左侧会话列表用（标题、时间排序、类型）
-- type 字段用于区分会话来源：chat（常规聊天）/ rag（知识库问答），列表按 type 过滤隔离
CREATE TABLE IF NOT EXISTS conversation (
    id         VARCHAR(64)  PRIMARY KEY,
    title      VARCHAR(255) DEFAULT '新对话',
    type       VARCHAR(16)  NOT NULL DEFAULT 'chat',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_conv_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 完整对话日志表：append-only，永不随窗口裁剪删除（给人回看完整历史用）
-- 与 chat_memory（喂模型的滑动窗口）职责分离：
--   chat_memory：只留最近 N 条，超窗即物理删除（省 token、控成本）
--   chat_log  ：永久保留每一次问答，前端历史列表从这张表读，确保“超 20 条也不会缺头”
CREATE TABLE IF NOT EXISTS chat_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,   -- user / assistant / system
    content         TEXT,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_cl_conv (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- RAG 文件管理表：上传记录 + 向量化状态（文件本体落盘，本表只存元数据）
-- status: uploaded（仅上传，未向量化）/ indexed（已向量化，可参与检索增强）
CREATE TABLE IF NOT EXISTS rag_file (
    id           VARCHAR(64)  PRIMARY KEY,                 -- UUID，同时作为向量库文档段的 fileId 前缀
    filename     VARCHAR(255) NOT NULL,                    -- 原始文件名（展示用）
    content_type VARCHAR(128) DEFAULT NULL,                -- MIME 类型
    size         BIGINT       DEFAULT 0,                   -- 文件大小（字节）
    storage_path VARCHAR(512) NOT NULL,                    -- 落盘物理路径（含 UUID 前缀）
    content_hash CHAR(64)     DEFAULT NULL,                -- 文件内容 SHA-256（上传去重指纹）
    status       VARCHAR(16)  NOT NULL DEFAULT 'uploaded', -- uploaded / indexed
    doc_ids      TEXT,                                     -- 该文件切片在向量库的文档 ID（逗号分隔），用于按文件移除索引
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    indexed_at   TIMESTAMP    DEFAULT NULL,
    INDEX idx_rf_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
