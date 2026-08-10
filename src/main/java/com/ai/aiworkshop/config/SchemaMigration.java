package com.ai.aiworkshop.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 幂等数据库迁移：为已有 conversation 表补加 type 列（chat / rag）。
 *
 * 设计说明：
 * - schema.sql 里 conversation 的 CREATE TABLE 已包含 type 列，用于【全新库】首次启动；
 * - 但用户本地是【已存在的库】，CREATE TABLE IF NOT EXISTS 会被跳过，type 列不会自动出现，
 *   而 ConversationDO 已声明 type 字段，SELECT 会因“未知列”报错。
 * - 因此这里在应用启动（CommandLineRunner，晚于 schema.sql 执行）时检查列是否存在，
 *   仅当不存在才 ALTER 加列；已存在则跳过，保证每次启动都安全、不报错。
 */
@Component
public class SchemaMigration implements CommandLineRunner {

    private final DataSource dataSource;

    public SchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            boolean hasType;
            try (Statement check = conn.createStatement();
                 ResultSet rs = check.executeQuery(
                         "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                         + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation' AND COLUMN_NAME = 'type'")) {
                hasType = rs.next() && rs.getInt(1) > 0;
            }
            if (!hasType) {
                try (Statement alter = conn.createStatement()) {
                    alter.execute("ALTER TABLE conversation ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'chat'");
                }
                // 给历史会话统一标记为 chat，避免和 RAG 会话混在一起
                try (Statement backfill = conn.createStatement()) {
                    backfill.execute("UPDATE conversation SET type = 'chat' WHERE type IS NULL OR type = ''");
                }
            }

            // 幂等建表：rag_file（RAG 文件管理）。全新库由 schema.sql 建好；已存在库靠这里兜底。
            boolean hasRagFile;
            try (Statement check2 = conn.createStatement();
                 ResultSet rs2 = check2.executeQuery(
                         "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                         + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rag_file'")) {
                hasRagFile = rs2.next() && rs2.getInt(1) > 0;
            }
            if (!hasRagFile) {
                try (Statement create = conn.createStatement()) {
                    create.execute("CREATE TABLE IF NOT EXISTS rag_file ("
                            + "id VARCHAR(64) PRIMARY KEY, "
                            + "filename VARCHAR(255) NOT NULL, "
                            + "content_type VARCHAR(128) DEFAULT NULL, "
                            + "size BIGINT DEFAULT 0, "
                            + "storage_path VARCHAR(512) NOT NULL, "
                            + "status VARCHAR(16) NOT NULL DEFAULT 'uploaded', "
                            + "doc_ids TEXT, "
                            + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                            + "indexed_at TIMESTAMP DEFAULT NULL, "
                            + "INDEX idx_rf_status (status)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                }
            }
        }
    }
}
