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

-- 会话元数据表：给前端左侧会话列表用（标题、时间排序）
CREATE TABLE IF NOT EXISTS conversation (
    id         VARCHAR(64)  PRIMARY KEY,
    title      VARCHAR(255) DEFAULT '新对话',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
