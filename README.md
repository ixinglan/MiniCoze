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
- `GET  /api/chat/conversations?type=chat`（会话列表，按 type 过滤；聊天页用 `chat`，RAG 页用 `rag`）
- `POST /api/chat/conversations?type=chat`（新建会话，返回 `{ "id": "..." }`；`type` 区分来源 `chat` / `rag`）
   - `GET  /api/chat/history?conversationId=xxx`（拉取某会话历史）
   - `DELETE /api/chat/conversation?conversationId=xxx`（彻底删除会话：消息 + 元数据）
   - `GET  /api/chat/embed?text=你好世界`（验证 Ollama embedding 是否就绪）
- `POST /api/parse/ticket`（JSON `{ "text": "..." }`，返回结构化工单 TaskTicket JSON；演示页 http://localhost:9999/ticket.html）
- `GET  /api/rag/stream?message=...&conversationId=xxx`（RAG 流式问答，基于知识库检索回答；每轮双写 chat_log，复用 /api/chat/conversations?type=rag、/api/chat/history 做会话列表与历史回看，与聊天页互不串门）
- `POST /api/rag`（JSON `{ "message": "...", "conversationId": "xxx" }`，非流式 RAG 问答；演示页 http://localhost:9999/rag.html）

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
- 扩展：双表分离（`chat_memory` 喂模型 + `chat_log` 完整日志，append-only 不丢历史）

## 阶段 2 结构化输出（PromptTemplate + BeanOutputConverter）
- 目标：把"模型的自由文本"收敛成"规整的 Java 对象"，让 LLM 从"能说"变成"能干活"——这是 Agent 工作台能接 API / 落库 / 编排的前提。
- 场景落地：自然语言需求 → `TaskTicket`（title / category / priority / dueDate / tags / description / needFollowUp 七字段）。
- 两个 Spring AI 原语：`BeanOutputConverter<TaskTicket>`（标准三步——`new` 单例 → `getFormat()` 拿 JSON Schema 塞 prompt → `convert(文本)` 得对象）；`PromptTemplate`（`{占位符}` 模板 + `.variables(Map)` 渲染）。
- 关键设计：`parsingClient` 无状态（不挂记忆 Advisor，解析不污染 `chat_memory` / `chat_log`）；目标类用 `@JsonPropertyDescription` 提升抽取准确率；Converter 做成 `final` 单例复用。
- 端点 `POST /api/parse/ticket` + 演示页 `ticket.html`。

## 阶段 3 RAG 检索增强（SimpleVectorStore + QuestionAnswerAdvisor + TokenTextSplitter）
- 目标：让模型"基于你的私有资料回答"，而不是凭空编造——这是知识库产品的核心能力。
- 流程：启动扫描 `classpath:rag-docs/*` 的 markdown/text → 构造 Document（带 source 元数据）→ `TokenTextSplitter` 切片 → 写入内存向量库（SimpleVectorStore，底层用 Ollama bge-m3 向量化）。
- 检索增强：`ragClient` 挂载 `QuestionAnswerAdvisor(vectorStore)`，每次提问前先从向量库取 topK 最相关片段注入 prompt；并叠加 `MessageChatMemoryAdvisor` 保留多轮记忆（conversationId 隔离）。
- 端点 `GET /api/rag/stream` + `POST /api/rag` + 演示页 `rag.html`。知识库文档放在 `src/main/resources/rag-docs/`，新增 .md/.txt 即可被自动索引。
- **会话历史持久化（与 /api/chat 一致）**：`rag.html` 复用聊天页的会话侧边栏；`RagController` 在每轮问答时 `touch` 会话排序/标题 + 双写 `chat_log`。因此 RAG 对话同样支持多会话切换、历史回看、手动删除，刷新不丢。
- **聊天 / RAG 会话隔离（type 字段）**：`conversation` 表新增 `type` 列（`chat` / `rag`，默认 `chat`），列表按 `type` 过滤，聊天页与 RAG 页互不串门。后端 `ConversationService.createConversation(type)` / `listConversations(type)` 透传；`ChatController` 的 `conversations` / `newConversation` 接收 `@RequestParam type`；`rag.html` 固定传 `?type=rag`。为兼容已存在的库，新增 `SchemaMigration`（CommandLineRunner）在启动时幂等 `ALTER TABLE` 补列并回填历史会话为 `chat`。

## 已完成
- [x] 阶段 0：ChatClient 接入 DeepSeek + SSE 流式对话 + 极简网页
- [x] 阶段 0 扩展：双模型骨架（DeepSeek 对话 + Ollama embedding）
- [x] 阶段 1：多轮对话记忆（`ChatMemory` 滑动窗口 + `MessageChatMemoryAdvisor` + 按 conversationId 隔离）
- [x] 阶段 1 持久化：记忆落 MySQL（`MysqlChatMemoryRepository` + `conversation`/`chat_memory` 表）+ 前端会话列表面板
- [x] 阶段 1 扩展：双表分离（`chat_memory` 喂模型 + `chat_log` 完整日志，append-only 不丢历史）
- [x] 阶段 2：结构化输出（`PromptTemplate` + `BeanOutputConverter`，自然语言 → TaskTicket 工单对象）
- [x] 阶段 3：RAG 检索增强（SimpleVectorStore + QuestionAnswerAdvisor + TokenTextSplitter，启动加载 rag-docs 建索引）
- [x] 阶段 3 扩展：RAG 会话历史持久化（RagController 双写 chat_log + rag.html 会话侧边栏，与聊天页一致）
- [x] 阶段 3 扩展：聊天 / RAG 会话隔离（`conversation` 表加 `type` 字段，列表按 type 过滤，SchemaMigration 幂等补列兼容旧库）

## 学习计划（每阶段 = 给产品加一块能力）
- [x] 阶段 1｜多轮对话与记忆：`ChatMemory` + Advisor（会话隔离、历史注入）
- [x] 阶段 2｜结构化输出与提示词：`PromptTemplate` + `BeanOutputConverter`
- [x] 阶段 3｜RAG 检索增强：`document-reader-*` → `TokenTextSplitter` → Embedding → 向量库 → `QuestionAnswerAdvisor` / RagWay
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
