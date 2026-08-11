package com.ai.aiworkshop.service;

import com.ai.aiworkshop.entity.ChatLogDO;
import com.ai.aiworkshop.mapper.ChatLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 完整对话日志（给人回看，不被窗口裁剪删除）。
 * 与 MysqlChatMemoryRepository（喂模型的滑动窗口 chat_memory）职责分离：
 *   - chat_memory：只留最近 N 条，超窗即删（省 token、控成本）
 *   - chat_log   ：append-only 永久保留，前端历史列表从这张表读，超 20 条也不会缺头
 */
@Service
public class ChatLogService {

    private final ChatLogMapper chatLogMapper;

    public ChatLogService(ChatLogMapper chatLogMapper) {
        this.chatLogMapper = chatLogMapper;
    }

    /** 追加一条消息（user / assistant / system），按 created_at 自然有序 */
    public void append(String conversationId, String role, String content) {
        ChatLogDO d = new ChatLogDO();
        d.setConversationId(conversationId);
        d.setRole(role);
        d.setContent(content);
        chatLogMapper.insert(d);
    }

    /** 读取某个会话的完整历史（按时间正序），供前端回看 */
    public List<Map<String, Object>> getFullHistory(String conversationId) {
        List<ChatLogDO> list = chatLogMapper.selectList(
                Wrappers.<ChatLogDO>query()
                        .eq("conversation_id", conversationId)
                        .orderByAsc("created_at"));
        return list.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", d.getRole());
            m.put("content", d.getContent());
            return m;
        }).collect(Collectors.toList());
    }

    /** 删除会话时级联清理日志 */
    public void deleteByConversationId(String conversationId) {
        chatLogMapper.delete(
                Wrappers.<ChatLogDO>update().eq("conversation_id", conversationId));
    }
}
