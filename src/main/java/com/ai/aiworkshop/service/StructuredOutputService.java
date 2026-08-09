package com.ai.aiworkshop.service;

import com.ai.aiworkshop.model.TaskTicket;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 阶段 2 核心：结构化输出。
 *
 * 把“模型的自由文本”收敛成“规整的 Java 对象”（这里用 TaskTicket）。
 * 用到的两个 Spring AI 原语：
 *   1) BeanOutputConverter<T> —— 负责“文本 -> Java 对象”的反序列化，内部基于 Jackson + JSON Schema。
 *   2) PromptTemplate         —— 负责“提示词模板化”，{占位符} 用 .variables(Map) 渲染。
 *
 * 标准三步：
 *   a. new BeanOutputConverter<>(TaskTicket.class) 且复用单例；
 *   b. converter.getFormat() 拿到 JSON Schema 格式说明，塞进 prompt 末尾，逼模型按格式输出；
 *   c. parsingClient 拿到回复文本后，converter.convert(text) 直接得到 TaskTicket。
 */
@Service
public class StructuredOutputService {

    /** BeanOutputConverter 本身是线程安全的，做成 final 单例复用即可（避免每次 new 重算 schema） */
    private final BeanOutputConverter<TaskTicket> converter = new BeanOutputConverter<>(TaskTicket.class);
    private final ChatClient parsingClient;

    public StructuredOutputService(ChatClient parsingClient) {
        this.parsingClient = parsingClient;
    }

    /**
     * 把一句自然语言需求解析成结构化工单。
     *
     * @param text 用户的原始需求文本
     * @return 规整的 TaskTicket 对象（Spring 序列化后就是 JSON）
     */
    public TaskTicket parseTicket(String text) {
        // b. 拿到格式说明（JSON Schema 文本），告诉模型“你必须按这个结构输出”
        String format = converter.getFormat();

        // 提示词模板：{text} 是用户需求，{format} 是上面拿到的格式说明
        String template = """
                你是一个工单解析助手。请把用户的自然语言需求解析为结构化工单对象。
                只输出符合要求的 JSON，不要包含 ```json``` 代码块标记，也不要任何解释性文字。

                用户需求：
                {text}

                请严格按以下 JSON 格式输出：
                {format}
                """;

        // a+c 衔接：PromptTemplate 渲染变量 -> Prompt；调用解析客户端 -> 取文本 -> convert 成对象
        Prompt prompt = PromptTemplate.builder()
                .template(template)
                .variables(Map.<String, Object>of("text", text, "format", format))
                .build()
                .create();
        String response = parsingClient.prompt(prompt)
                .call()
                .content();

        return converter.convert(response);
    }
}
