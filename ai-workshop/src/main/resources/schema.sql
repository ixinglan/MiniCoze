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

-- 工单表（阶段 4 补全落库）：模型通过“创建工单”工具登记的待办。
-- 设计要点（为后续阶段预留缝，避免返工）：
--  - source：工单来源（agent=当前 Agent 工具 / mcp=阶段 7 MCP 工具 / subagent=阶段 6 子智能体），来源无关，统一落同一张表；
--  - status：生命周期（open 待办 / done 已完成），供后续阶段更新（如 Agent 标记工单完成）；
--  - conversation_id：关联到触发它的 agent 会话，便于溯源“哪个对话产生了这张工单”。
--  - tags：关键词标签，存 JSON 字符串（列表序列化），读取时再解析。
CREATE TABLE IF NOT EXISTS task_ticket (
    id              VARCHAR(64)  PRIMARY KEY,
    title           VARCHAR(255) DEFAULT NULL,
    category        VARCHAR(32)  DEFAULT NULL,
    priority        VARCHAR(16)  DEFAULT NULL,
    due_date        VARCHAR(20)  DEFAULT NULL,                 -- YYYY-MM-DD 或空
    tags            TEXT,                                       -- JSON 数组字符串
    description     TEXT,
    need_follow_up  TINYINT(1)   DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'open',
    source          VARCHAR(32)  NOT NULL DEFAULT 'agent',
    conversation_id VARCHAR(64)  DEFAULT NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tt_status (status),
    INDEX idx_tt_source (source),
    INDEX idx_tt_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== 阶段 8：AI 调用观测日志表（可观测性）=====
-- 由自定义 ObservationHandler（AiCallLogObservationHandler）在每次 LLM 调用结束时写入，
-- 记录每次模型调用的元数据（模型 / 供应商 / token 用量 / 耗时 / 是否成功），供 obs.html 监控页聚合展示。
-- append-only，不删除；如后续量大可按天归档。
CREATE TABLE IF NOT EXISTS ai_call_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    operation_type    VARCHAR(32)  DEFAULT NULL,   -- 操作类型：chat / image / embedding
    provider          VARCHAR(32)  DEFAULT NULL,   -- 供应商：deepseek / dashscope / ollama ...
    model             VARCHAR(100) DEFAULT NULL,   -- 模型名：deepseek-v4-flash / qwen-vl-max ...
    prompt_tokens     INT          DEFAULT 0,      -- 输入 token 数
    completion_tokens INT          DEFAULT 0,      -- 输出 token 数
    total_tokens      INT          DEFAULT 0,      -- 总 token 数（成本估算基础）
    duration_ms       BIGINT       DEFAULT 0,      -- 本次调用耗时（毫秒）
    success           TINYINT(1)   NOT NULL DEFAULT 1,  -- 1 成功 / 0 失败
    error_msg         VARCHAR(500) DEFAULT NULL,   -- 失败原因（截断到 500 字符）
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_acl_created (created_at),
    INDEX idx_acl_model (model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
