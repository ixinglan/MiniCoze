package com.ai.aiworkshop.repository;

import com.ai.aiworkshop.entity.ChatMemoryDO;
import com.ai.aiworkshop.mapper.ChatMemoryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 阶段 1 改造：用 MySQL 实现 ChatMemoryRepository（MyBatis-Plus 版）。
 *
 * 四个方法语义与内置实现一致：
 *  - findByConversationId：按 message_index 升序读出，重建为 Message
 *  - saveAll：整窗替换 —— 先 DELETE 该会话全部行，再按 index 批量 INSERT
 *    （与 MessageWindowChatMemory 的“滑动窗口”逻辑匹配：每次只存窗口内的消息）
 *  - deleteByConversationId / findConversationIds：简单 CRUD
 *
 * 注意：Spring AI 的消息类没有 (String, Map) 带 metadata 的构造器，
 * 还原时用 content-only 重建（new UserMessage(content) 等），metadata 仅存储、还原时暂不回填，
 * 对纯文本多轮对话无影响。
 */
public class MysqlChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryMapper chatMemoryMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MysqlChatMemoryRepository(ChatMemoryMapper chatMemoryMapper) {
        this.chatMemoryMapper = chatMemoryMapper;
    }

    @Override
    public List<String> findConversationIds() {
        return chatMemoryMapper.selectList(
                        Wrappers.<ChatMemoryDO>query().select("distinct conversation_id"))
                .stream()
                .map(ChatMemoryDO::getConversationId)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<ChatMemoryDO> list = chatMemoryMapper.selectList(
                Wrappers.<ChatMemoryDO>query()
                        .eq("conversation_id", conversationId)
                        .orderByAsc("message_index"));
        return list.stream().map(this::toMessage).collect(Collectors.toList());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 整窗替换：先删后插
        chatMemoryMapper.delete(
                Wrappers.<ChatMemoryDO>update().eq("conversation_id", conversationId));
        for (int i = 0; i < messages.size(); i++) {
            chatMemoryMapper.insert(toDO(conversationId, i, messages.get(i)));
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        chatMemoryMapper.delete(
                Wrappers.<ChatMemoryDO>update().eq("conversation_id", conversationId));
    }

    // ---------- 行 <-> Message 转换 ----------

    private Message toMessage(ChatMemoryDO d) {
        String type = d.getMessageType();
        String content = d.getContent();
        MessageType mt = MessageType.fromValue(type);
        return switch (mt) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            default -> new AssistantMessage(content); // TOOL 等极端情况兜底（本工程不出现）
        };
    }

    private ChatMemoryDO toDO(String conversationId, int index, Message m) {
        ChatMemoryDO d = new ChatMemoryDO();
        d.setConversationId(conversationId);
        d.setMessageIndex(index);
        d.setMessageType(m.getMessageType().getValue());
        d.setContent(m.getText());
        String metadataJson;
        try {
            metadataJson = (m.getMetadata() == null || m.getMetadata().isEmpty())
                    ? "{}" : objectMapper.writeValueAsString(m.getMetadata());
        } catch (Exception e) {
            metadataJson = "{}";
        }
        d.setMetadata(metadataJson);
        return d;
    }
}
