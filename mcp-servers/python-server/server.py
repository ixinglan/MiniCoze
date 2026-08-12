#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Python MCP Server（标准 stdio 传输）
====================================
作用：作为独立的 MCP 工具服务端，通过标准输入/输出（stdio）与 Spring Boot 主应用通信。
Spring Boot 通过 spring-ai-starter-mcp-client 启动此进程并自动发现工具。

暴露 4 个工具：
  1. get_current_time     → 获取当前日期和时间
  2. get_weather          → 查询指定城市的天气（模拟数据）
  3. calculate            → 安全计算数学表达式
  4. search_knowledge     → 模拟知识库检索

启动方式（由 Spring Boot 主应用自动启动，无需手动运行）：
  python server.py

依赖安装（首次）：
  pip install -r requirements.txt

关键概念：
  - @mcp.tool() 装饰器：把 Python 函数注册为 MCP 工具，Spring AI 会自动发现
  - 函数签名中的参数类型注解（str/int）和 docstring 会被框架解析为工具的 schema
  - transport="stdio"：用标准输入/输出通信，不占用网络端口
"""

import json
import operator
from datetime import datetime

from fastmcp import FastMCP

# ===== 第一步：创建 MCP 服务实例 =====
# FastMCP 是 mcp 包提供的高层 API，封装了底层协议细节。
# name 是服务名称（会显示在 Spring AI 的 MCP 连接配置里）。
mcp = FastMCP("python-tools-server")


# ===== 第二步：用 @mcp.tool() 注册工具 =====
# 每个被 @mcp.tool() 装饰的普通 Python 函数，自动成为 MCP 工具。
# 函数名 = 工具名，docstring = 工具描述，参数类型注解 = 入参 schema。
# 关键：参数类型只能用基础类型（str/int/float/bool），不能是复杂对象。


@mcp.tool()
def get_current_time() -> str:
    """获取当前日期和时间，返回 YYYY-MM-DD HH:MM:SS 格式的字符串。

    适用场景：用户问"现在几点"、"今天是几号"等时间相关问题。
    """
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


@mcp.tool()
def get_weather(city: str) -> str:
    """查询指定城市的实时天气情况（模拟数据）。每人每次调用返回随机天气。

    参数：
        city: 城市名称，如"北京"、"上海"、"深圳"。

    适用场景：用户问"北京天气怎么样"等天气相关问题。
    注意：这是模拟数据，实际生产应接入真实天气 API。
    """
    # 用城市名的 hash 做伪随机种子，同一城市每次结果不同但可重现
    import random
    random.seed(hash(city) + hash(datetime.now().strftime("%Y%m%d%H")))  # 每小时变一次

    temps = list(range(-10, 41))
    conditions = ["晴", "多云", "阴", "小雨", "中雨", "大雨", "雷阵雨", "小雪", "雾", "霾"]
    winds = ["微风", "3-4级", "4-5级", "5-6级"]

    temp = random.choice(temps)
    condition = random.choice(conditions)
    wind = random.choice(winds)
    humidity = random.randint(20, 95)

    return (
        f"{city}天气：{condition}，温度 {temp}°C，"
        f"湿度 {humidity}%，风力 {wind}"
    )


@mcp.tool()
def calculate(expression: str) -> str:
    """安全计算四则运算数学表达式。

    参数：
        expression: 数学表达式字符串，如 "23+19*4"、"100/(5-3)"。

    支持运算符：+ - * / ** ( )
    注意：出于安全考虑，使用 AST 安全求值，不接受函数调用或变量。
    适用场景：用户需要进行数学计算时。
    """
    # ===== 安全求值：用 AST 解析而非 eval()，防止代码注入 =====
    import ast

    # 允许的运算符映射（只开放四则运算+幂运算）
    ALLOWED_OPS = {
        ast.Add: operator.add,
        ast.Sub: operator.sub,
        ast.Mult: operator.mul,
        ast.Div: operator.truediv,
        ast.Pow: operator.pow,
        ast.USub: operator.neg,    # 负号，如 -5
    }

    def _safe_eval(node):
        """递归安全求值：只处理数字 + 允许的二元运算符"""
        if isinstance(node, ast.Expression):
            return _safe_eval(node.body)
        elif isinstance(node, ast.Constant):
            # Python 3.8+ 数字常量是 ast.Constant
            if isinstance(node.value, (int, float)):
                return node.value
        elif isinstance(node, ast.Num):
            # Python 3.7 兼容
            return node.n
        elif isinstance(node, ast.BinOp):
            # 二元运算：左值 op 右值
            op_type = type(node.op)
            if op_type in ALLOWED_OPS:
                left = _safe_eval(node.left)
                right = _safe_eval(node.right)
                if op_type == ast.Div and right == 0:
                    raise ZeroDivisionError("除数不能为零")
                return ALLOWED_OPS[op_type](left, right)
        elif isinstance(node, ast.UnaryOp):
            # 一元运算：目前只支持负号
            op_type = type(node.op)
            if op_type in ALLOWED_OPS:
                return ALLOWED_OPS[op_type](_safe_eval(node.operand))

        raise ValueError(f"不支持的表达式: 包含不允许的操作")

    try:
        tree = ast.parse(expression.strip(), mode='eval')
        result = _safe_eval(tree)
        # 格式化输出：整数不带小数点，浮点数保留适当精度
        if isinstance(result, float) and result == int(result):
            result = int(result)
        elif isinstance(result, float):
            result = round(result, 10)
        return f"{expression} = {result}"
    except ZeroDivisionError as e:
        return f"计算错误: {e}"
    except (ValueError, SyntaxError) as e:
        return f"表达式无效: {expression}（只支持 + - * / ** () 和数字）"


@mcp.tool()
def search_knowledge(query: str) -> str:
    """检索本地知识库（模拟数据），返回与查询相关的知识条目。

    参数：
        query: 搜索关键词，如"Spring AI MCP 协议"。

    适用场景：用户需要查询知识库中的信息时。
    注意：当前为模拟实现，实际应接入向量数据库或搜索引擎。
    """
    # ===== 模拟知识库 =====
    knowledge_base = {
        "spring": "Spring Framework 是 Java 生态最主流的应用框架，核心是 IoC 容器和 AOP。Spring Boot 在此基础上提供自动配置和起步依赖，大幅简化开发。",
        "mcp": "MCP（Model Context Protocol）是 Anthropic 提出的开放协议，定义了 AI 模型与外部工具/数据源之间的标准通信方式。Spring AI 1.1.2 内置了 MCP Client 和 Server 支持。",
        "spring ai": "Spring AI 是 Spring 生态的 AI 集成框架，提供 ChatClient、Embedding、VectorStore、Tool Calling、Agent 编排等能力，支持 OpenAI、DeepSeek、Ollama 等多种模型。",
        "nacos": "Nacos 是阿里巴巴开源的服务发现和配置管理平台。Spring Cloud Alibaba Nacos Discovery 可以让微服务自动注册和发现。结合 MCP 可实现 MCP Server 的服务发现。",
        "deepseek": "DeepSeek V4 是 DeepSeek 的最新大语言模型，性价比高。通过 OpenAI 兼容协议接入 Spring AI，适合对话、代码生成、Agent 工具调用等场景。",
        "rag": "RAG（检索增强生成）是在 LLM 生成回答前先检索外部知识库，把检索结果作为上下文注入 prompt，从而减少幻觉、让回答有据可查。核心组件：文档解析→切片→向量化→向量库检索→增强生成。",
        "agent": "AI Agent 是能自主使用工具、规划步骤、执行任务的智能体。Spring AI Alibaba 提供 ReactAgent（思考-行动循环）、SequentialAgent（顺序执行）、LlmRoutingAgent（路由分发）等模式。",
    }

    # 简单关键词匹配（大小写不敏感）
    query_lower = query.lower()
    results = []

    for key, content in knowledge_base.items():
        if key in query_lower or any(word in query_lower for word in key.split()):
            results.append(f"【{key}】{content}")

    if not results:
        return f"未找到与「{query}」相关的知识。当前知识库覆盖：Spring、MCP、Nacos、DeepSeek、RAG、Agent 等主题。"

    return "\n\n".join(results)


# ===== 第三步：启动服务（stdio 传输） =====
# 这是 Python 脚本的标准入口保护。
# 当被 Spring Boot 通过 stdio 启动时，mcp.run() 会读取 stdin 的 JSON-RPC 消息、
# 执行对应工具、把结果写回 stdout。
if __name__ == "__main__":
    mcp.run(transport="stdio")
