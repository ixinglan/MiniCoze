package com.ai.aiworkshop.controller;

import com.ai.aiworkshop.model.TaskTicket;
import com.ai.aiworkshop.service.StructuredOutputService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 阶段 2 的结构化解析接口。
 * 职责单一：接收自然语言文本，返回结构化工单对象（TaskTicket 由 Spring 自动序列化 JSON）。
 */
@RestController
@RequestMapping("/api/parse")
public class StructuredOutputController {

    private final StructuredOutputService structuredOutputService;

    public StructuredOutputController(StructuredOutputService structuredOutputService) {
        this.structuredOutputService = structuredOutputService;
    }

    /**
     * 自然语言 -> 工单
     * 请求：POST /api/parse/ticket  body: { "text": "周五前帮我写个登录接口压测脚本，优先级高" }
     * 返回：TaskTicket 对象（title / category / priority / dueDate / tags / description / needFollowUp）
     */
    @PostMapping("/ticket")
    public TaskTicket parseTicket(@RequestBody Map<String, String> body) {
        return structuredOutputService.parseTicket(body.get("text"));
    }
}
