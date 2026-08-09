package com.ai.aiworkshop.service;

import com.ai.aiworkshop.entity.ChatMemoryDO;
import com.ai.aiworkshop.entity.ConversationDO;
import com.ai.aiworkshop.mapper.ChatMemoryMapper;
import com.ai.aiworkshop.mapper.ConversationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话管理服务（MyBatis-Plus 版）：维护 conversation 表（左侧栏用）与 chat_memory 表的级联删除。
 * 记忆本身由 MysqlChatMemoryRepository 负责；完整历史读取改走 chat_log（见 ChatLogService），
 * 这样窗口裁剪只影响喂模型的 chat_memory，不影响用户回看完整历史。
 */
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ChatMemoryMapper chatMemoryMapper;
    private final ChatLogService chatLogService;

    public ConversationService(ConversationMapper conversationMapper, ChatMemoryMapper chatMemoryMapper,
                               ChatLogService chatLogService) {
        this.conversationMapper = conversationMapper;
        this.chatMemoryMapper = chatMemoryMapper;
        this.chatLogService = chatLogService;
    }

    /** 新建一个会话，返回会话 ID（供前端“新对话”按钮使用） */
    public String createConversation() {
        String id = UUID.randomUUID().toString();
        ConversationDO d = new ConversationDO();
        d.setId(id);
        d.setTitle("新对话");
        conversationMapper.insert(d);   // created_at / updated_at 由 DB 默认值 CURRENT_TIMESTAMP 填充
        return id;
    }

    /** 会话列表（左侧栏），按最近更新倒序 */
    public List<Map<String, Object>> listConversations() {
        List<ConversationDO> list = conversationMapper.selectList(
                Wrappers.<ConversationDO>query().orderByDesc("updated_at"));
        return list.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("title", d.getTitle());
            m.put("updated_at", d.getUpdatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 某个会话的完整历史（前端点开会话时拉取）。
     * 关键：改从 chat_log 读，而不是被窗口裁剪的 chat_memory，
     * 因此即使对话超过 20 条（10 轮），用户也能回看全部历史、不会缺头。
     */
    public List<Map<String, Object>> getHistory(String conversationId) {
        return chatLogService.getFullHistory(conversationId);
    }

    /** 彻底删除一个会话：清理会话元数据 + 窗口记忆 + 完整日志（手动点删除时调用） */
    public void deleteConversation(String conversationId) {
        conversationMapper.deleteById(conversationId);
        chatMemoryMapper.delete(
                Wrappers.<ChatMemoryDO>update().eq("conversation_id", conversationId));
        chatLogService.deleteByConversationId(conversationId);
    }

    /**
     * 每次发消息时调用：
     *  1) 刷新 updated_at，让该会话排到列表最上面；
     *  2) 若标题还是默认“新对话”，用首句前 30 字设为标题（只设一次）。
     */
    public void touch(String conversationId, String userText) {
        // updated_at 列有 ON UPDATE CURRENT_TIMESTAMP，这里显式更新也更可控
        conversationMapper.update(null,
                Wrappers.<ConversationDO>update().eq("id", conversationId).setSql("updated_at = NOW()"));
        String title = userText.length() > 30 ? userText.substring(0, 30) : userText;
        conversationMapper.update(null,
                Wrappers.<ConversationDO>update()
                        .eq("id", conversationId).eq("title", "新对话")
                        .set("title", title));
    }
}
