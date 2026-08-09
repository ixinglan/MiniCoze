-- ============================================================
-- 迁移脚本：把 chat_memory 已有数据同步到 chat_log（完整日志）
-- ============================================================
-- 适用场景：阶段 1 做完"双表分离"后，把 chat_memory 里遗留的
--           历史问答补进 chat_log，使前端历史列表能看到它们。
--
-- 字段映射：
--   chat_memory.conversation_id -> chat_log.conversation_id
--   chat_memory.message_type    -> chat_log.role  （大写转小写：USER->user ...）
--   chat_memory.content         -> chat_log.content
--   chat_memory.created_at      -> chat_log.created_at（见下方时序说明）
--
-- 幂等：已存在相同 (conversation_id, role, content) 的行不会重复插入，
--       本脚本可反复执行，不会翻倍。
--
-- 时序说明（重要）：
--   chat_memory 采用"整窗替换"（DELETE 全部 + 重新 INSERT），
--   所以同一会话内同一窗口的每行 created_at 可能相同。
--   为保证 chat_log 按 created_at 升序回看时顺序不乱，
--   这里用 created_at + message_index 秒 重排，使同会话内时间严格递增。
-- ============================================================

INSERT INTO chat_log (conversation_id, role, content, created_at)
SELECT
    cm.conversation_id,
    LOWER(cm.message_type)                                      AS role,
    cm.content,
    cm.created_at + INTERVAL cm.message_index SECOND            AS created_at
FROM chat_memory cm
WHERE NOT EXISTS (
    SELECT 1
    FROM chat_log cl
    WHERE cl.conversation_id = cm.conversation_id
      AND cl.role          = LOWER(cm.message_type)
      AND cl.content       = cm.content
);
