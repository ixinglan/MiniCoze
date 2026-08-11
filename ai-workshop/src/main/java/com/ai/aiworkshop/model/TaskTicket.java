package com.ai.aiworkshop.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * 阶段 2 的结构化输出目标对象：把用户的自然语言需求，解析成规整的工单/任务。
 *
 * 重点：
 * 1. 用普通 class + Lombok @Data（Jackson 用字段/ getter-setter 反序列化，比 record 更省心）。
 * 2. 每个字段都加 @JsonPropertyDescription —— Spring AI 的 BeanOutputConverter 会读取它，
 *    生成进 prompt 的 JSON Schema 的字段说明，模型抽取准确率明显更高。
 *    （这是结构化输出里最容易被忽略、但性价比最高的一招。）
 */
@Data
public class TaskTicket {

    @JsonPropertyDescription("工单标题，用一句话简短概括需求，不超过 20 个字")
    private String title;

    @JsonPropertyDescription("工单分类，只能从 [开发, 运维, 设计, 文档, 测试, 其他] 中选一个")
    private String category;

    @JsonPropertyDescription("优先级，只能从 [P0(最高), P1, P2, P3(最低)] 中选一个")
    private String priority;

    @JsonPropertyDescription("截止时间，能识别出来就给 YYYY-MM-DD 格式，识别不出给空字符串")
    private String dueDate;

    @JsonPropertyDescription("关键词标签列表，2 到 5 个，用于检索和归类")
    private List<String> tags;

    @JsonPropertyDescription("需求详细描述，用 1 到 3 句话把事情说清楚")
    private String description;

    @JsonPropertyDescription("是否还需要后续跟进：需要则 true，一次性则 false")
    private boolean needFollowUp;
}
