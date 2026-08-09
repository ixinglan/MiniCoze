# AI Workshop —— 用产品学 Spring AI Alibaba

> 路线：**综合知识库 + 自主任务 Agent 工作台**（本地迷你版 Coze）
> 通过"每学一个特性，就给产品加一块能力"的方式，吃透 Spring AI Alibaba。

## 技术栈（版本需严格对应）
- JDK 17
- Spring Boot 3.5.x
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.2（已支持 Agent Skills、Supervisor / Routing 多智能体）
- 模型：DeepSeek V4（对话 + 视觉理解，OpenAI 兼容协议）+ Ollama 本地 bge-m3（embedding / RAG 向量化）

## 快速开始
1. 获取 DeepSeek API Key：https://platform.deepseek.com/api_keys
2. 设置环境变量（**不要写进代码**）：
   ```bash
   export AI_DEEPSEEK_API_KEY=你的key
   ```
3. （可选，阶段 3 RAG 才用到）本地装 Ollama 并拉取 embedding 模型：
   ```bash
   # 安装见 https://ollama.com ，然后：
   ollama pull bge-m3
   ```
4. 运行：
   ```bash
   mvn spring-boot:run
   ```
5. 打开浏览器：http://localhost:9999/ 即可对话。
   接口：
   - `GET  /api/chat/stream?message=你好&conversationId=xxx`（SSE 流式，多轮记忆靠 conversationId 隔离）
   - `POST /api/chat`（JSON `{ "message": "...", "conversationId": "xxx" }`）
   - `GET  /api/chat/conversations`（会话列表，左侧会话栏用）
   - `POST /api/chat/conversations`（新建会话，返回 `{ "id": "..." }`）
   - `GET  /api/chat/history?conversationId=xxx`（拉取某会话历史）
   - `DELETE /api/chat/conversation?conversationId=xxx`（彻底删除会话：消息 + 元数据）
   - `GET  /api/chat/embed?text=你好世界`（验证 Ollama embedding 是否就绪）

## 双模型骨架说明
- **DeepSeek V4** 提供 `ChatModel`：负责对话、流式输出、图片理解（V4 原生多模态输入）。
- **Ollama bge-m3** 提供 `EmbeddingModel`：负责文本向量化，供阶段 3 的 RAG 使用。
- 为避免 Ollama 默认也注册 `ChatModel` 与 DeepSeek 冲突，已在 `application.yml` 中关闭 `spring.ai.ollama.chat.enabled`。
- DeepSeek 不提供 embedding，也不支持"文生图"输出；文生图在阶段 5 再接（如通义万相 / 本地 SD）。

## 阶段 1 记忆持久化（MySQL）
- 记忆从 JVM 内存（`InMemoryChatMemoryRepository`）迁移到 MySQL，库名 `mini_coze`，表：
  - `chat_memory`：消息明细（conversation_id + message_index 联合主键，整窗替换）
  - `conversation`：会话元数据（标题、更新时间，供左侧会话列表）
- 自定义 `MysqlChatMemoryRepository implements ChatMemoryRepository`；`schema.sql` 由 `spring.sql.init.mode=always` 启动自动建表。
- 前端改为"以会话 id 为中心"：历史全部从 `/api/chat/history` 拉取；左侧会话列表可新建 / 切换 / 删除，只有点删除才真删。

## 已完成
- [x] 阶段 0：ChatClient 接入 DeepSeek + SSE 流式对话 + 极简网页
- [x] 阶段 0 扩展：双模型骨架（DeepSeek 对话 + Ollama embedding）
- [x] 阶段 1：多轮对话记忆（`ChatMemory` 滑动窗口 + `MessageChatMemoryAdvisor` + 按 conversationId 隔离）
- [x] 阶段 1 持久化：记忆落 MySQL（`MysqlChatMemoryRepository` + `conversation`/`chat_memory` 表）+ 前端会话列表面板

## 学习计划（每阶段 = 给产品加一块能力）
- [x] 阶段 1｜多轮对话与记忆：`ChatMemory` + Advisor（会话隔离、历史注入）
- [ ] 阶段 2｜结构化输出与提示词：`PromptTemplate` + `BeanOutputConverter`
- [ ] 阶段 3｜RAG 检索增强：`document-reader-*` → `TokenTextSplitter` → Embedding → 向量库 → `QuestionAnswerAdvisor` / RagWay
- [ ] 阶段 4｜工具调用：`@Tool` + `FunctionToolCallback`（让模型调你的 Spring Bean）
- [ ] 阶段 5｜多模态：通义千问图片理解 + 通义万相文生图
- [ ] 阶段 6｜Agent 编排：`spring-ai-alibaba-graph-core` → `ReactAgent` → 多智能体（Sequential / Routing / Supervisor）
- [ ] 阶段 7｜MCP 集成：`nacos-mcp-client` / 标准 MCP，接入外部工具
- [ ] 阶段 8｜工程化：可观测（ARMS / Langfuse）、Guardrails、Docker 部署

## 参考
- 官网 / 文档：https://java2ai.com
- 版本说明：https://java2ai.com/docs/versions
- GitHub：https://github.com/alibaba/spring-ai-alibaba
- 官方 Playground 示例（含前端 UI，可抄）：仓库 `examples/` 目录
