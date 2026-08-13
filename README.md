# AI Workshop —— 用产品学 Spring AI Alibaba

> 路线：**综合知识库 + 自主任务 Agent 工作台**（本地迷你版 Coze）
> 通过"每学一个特性，就给产品加一块能力"的方式，吃透 Spring AI Alibaba。

## 技术栈（版本需严格对应）
- JDK 17
- Spring Boot 3.5.x
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.2（已支持 Agent Skills、Supervisor / Routing 多智能体）
- 模型：DeepSeek V4（仅对话，OpenAI 兼容协议，**公开 API 不支持图片输入**）+ 通义千问视觉 **qwen-vl-max**（图片理解，走 DashScope）+ 通义万相 **Wanx**（文生图，走 DashScope）+ Ollama 本地 bge-m3（embedding / RAG 向量化）
- 向量库：默认内存 `SimpleVectorStore`（零依赖）；可切 **Milvus**（docker 起，生产级持久化，维度对齐 bge-m3 的 1024）
- 文档解析：Apache Tika（`spring-ai-tika-document-reader`），一把覆盖 PDF / Word / Excel / PPT / TXT / MD
- MCP（阶段 7）：Model Context Protocol，跨进程跨语言工具协议；多模块工程（`ai-workshop` 主应用 + `mcp-nacos-server` MCP Server），stdio（Python FastMCP）+ SSE（Java Server）双路集成
- 可观测（阶段 8）：Spring AI 原生 **Micrometer Observation**（自定义 Handler 落库 `ai_call_log` + `obs.html` 监控页）+ **OpenTelemetry**（`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`）导出到自托管 **Langfuse v3**（Postgres + ClickHouse + Redis + MinIO + web + worker）
- Guardrails（阶段 8）：基于 **Advisor 手写**（Spring AI 1.1.x 无现成模块）——输入闸（敏感词/越狱/超长拦截，短路返回）+ 输出闸（PII 脱敏），规则全配置化
- 部署（阶段 8）：单阶段 Dockerfile + `docker/app-deploy.yml` compose 一键起（容器连宿主机已有 MySQL/Milvus/Ollama/Langfuse）
- 用户体系（阶段 9）：**Spring Security + JWT**（jjwt 0.12，无状态认证）+ BCrypt 密码 + `users` 表角色（ADMIN/USER）；6 类业务数据按 `user_id` 全链路隔离；`rate_limit_count` 表限流（用户+IP 双维度按天配额）；注册开关（默认关、Service 层强校验）；`login.html` + `auth.js`（monkey-patch fetch 自动带 token / 401 跳登录 / 未登录自动跳转）

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
4. 运行主应用（多模块工程，指定 `ai-workshop` 模块）：
   ```bash
   mvn spring-boot:run -pl ai-workshop
   ```
5. （可选，阶段 7 MCP 才用到）启动 MCP Server：
   - **stdio 路线**：主应用自动拉起 Python 子进程，需先 `pip install fastmcp`（详见 `mcp-servers/python-server/requirements.txt`）
   - **SSE 路线**：另开终端启动 Java MCP Server（`mcp-nacos-server`，端口 9988）：
     ```bash
     mvn spring-boot:run -pl mcp-nacos-server
     ```
   - （可选）Nacos 服务端：`docker/nacos-standalone.yml` 一键起，控制台 http://localhost:8080，API http://localhost:8848
6. 打开浏览器：http://localhost:9999/ 即可对话。
   - 接口：
     - `GET  /api/chat/stream?message=你好&conversationId=xxx`（SSE 流式，多轮记忆靠 conversationId 隔离）
     - `POST /api/chat`（JSON `{ "message": "...", "conversationId": "xxx" }`）
   - 会话管理接口：
     - `GET  /api/chat/conversations?type=chat`（会话列表，按 type 过滤；聊天页用 `chat`，RAG 页用 `rag`）
     - `POST /api/chat/conversations?type=chat`（新建会话，返回 `{ "id": "..." }`；`type` 区分来源 `chat` / `rag`）
     - `GET  /api/chat/history?conversationId=xxx`（拉取某会话历史）
     - `DELETE /api/chat/conversation?conversationId=xxx`（彻底删除会话：消息 + 元数据）
     - `GET  /api/chat/embed?text=你好世界`（验证 Ollama embedding 是否就绪）
   - 结构化接口：
     - `POST /api/parse/ticket`（JSON `{ "text": "..." }`，返回结构化工单 TaskTicket JSON；演示页 http://localhost:9999/ticket.html）
   - Rag检索接口：
     - `GET  /api/rag/stream?message=...&conversationId=xxx`（RAG 流式问答，基于知识库检索回答；每轮双写 chat_log，复用 /api/chat/conversations?type=rag、/api/chat/history 做会话列表与历史回看，与聊天页互不串门）
     - `POST /api/rag`（JSON `{ "message": "...", "conversationId": "xxx" }`，非流式 RAG 问答；演示页 http://localhost:9999/rag.html）
   - **RAG 文件管理**（`rag.html` **右侧**「📁 文件管理」独立栏，避免挤占左侧会话/中间对话空间；支持选前预览、上传真实百分比进度、向量化**流式真进度**，手动控制是否进知识库）：
     - `POST /api/rag/files`（单文件上传，field `file`；落盘 + 写 `rag_file` 记录，status=uploaded）
     - `POST /api/rag/files/batch`（批量上传，field `files`）
     - `POST /api/rag/files/check`（上传前去重预检：传内容哈希列表，返回已存在的 hash → {filename,status,id}）
     - **上传去重**：按文件内容 SHA-256 判断，内容相同即视为重复（无论改名与否），已存在则提示并跳过、不重复落盘；前端上传前先算哈希调 `/check` 预检，已上传过的直接拦截提示。
     - `GET  /api/rag/files`（文件列表，含索引状态）
     - `POST /api/rag/files/{id}/index`（手动向量化，返回 `application/x-ndjson` **流式真进度**：解析 → 切片 → 逐片段 bge-m3 嵌入(percent 随真实嵌入递增) → 写入向量库 → done，status=indexed；失败推 error 事件）
     - `DELETE /api/rag/files/{id}/index`（移除索引：从向量库删该文件向量，保留文件与记录）
     - `DELETE /api/rag/files/{id}`（彻底删除：移除索引 + 删物理文件 + 删记录）
   - **Agent 工具调用**（阶段 4，`agent.html`）：
     - `POST /api/agent/chat`（JSON `{ "message": "...", "conversationId": "xxx" }`，返回 `{ answer, toolCalls, conversationId }`；model 自主调工具，前端可视化"调了哪些工具/传了啥"）
     - `GET  /api/agent/tasks`（工单列表，来自 `task_ticket` 表）
     - 会话复用聊天页基建：`GET /api/chat/conversations?type=agent`、`/api/chat/history?conversationId=xxx`、`DELETE /api/chat/conversation?conversationId=xxx`
   - **多模态**（阶段 5，`multimodal.html`）：
     - `POST /api/multimodal/describe`（multipart `image` + `question`，图片理解，走 qwen-vl-max，返回 `{ answer }`）
     - `POST /api/multimodal/generate`（JSON `{ "prompt": "..." }`，文生图走通义万相 Wanx，返回 `{ image: "data:image/png;base64,...", url }`）
   - **Agent 编排**（阶段 6，`agent6.html`）：
     - `POST /api/agent6/react`（JSON `{ "query": "..." }`，ReactAgent 单 Agent 工具循环，返回 `{ mode, result, toolCalls }`）
     - `POST /api/agent6/sequential`（SequentialAgent 顺序：写作→评审，返回 `{ mode, result, trace }`）
     - `POST /api/agent6/routing`（LlmRoutingAgent 单次路由分发，返回 `{ mode, result, trace }`）
     - `POST /api/agent6/workflow`（graph-core 手写意图路由工作流，返回 `{ mode, route, result }`）
     - `POST /api/agent6/supervisor`（graph-core 手写 Supervisor 多智能体循环路由，返回 `{ mode, result }`）
   - **MCP 集成**（阶段 7，`agent6.html` → MCP tab）：
     - `GET /api/agent6/mcp/tools`（列出所有已连接 MCP Server 的工具清单，含来源标识；stdio 4 个 Python 工具 + SSE 4 个 Java 工具 = 8 个）

## 多模型骨架说明
- **DeepSeek V4** 提供 `ChatModel`：负责对话、流式输出（纯文本；其公开 API 不支持图片输入）。
- **通义千问视觉 qwen-vl-max** 提供 `ChatModel`（DashScope 自动配置的 `dashScopeChatModel`）：负责图片理解，须开 `multi-model: true` 走多模态端点。
- **通义万相 Wanx** 提供 `ImageModel`（DashScope 自动配置的 `dashScopeImageModel`）：负责文生图，异步生成、后端代理下载为 base64 返回。
- **Ollama bge-m3** 提供 `EmbeddingModel`：负责文本向量化，供阶段 3 的 RAG 使用。
- Ollama 的 `chat` 也已启用（注入 `ollamaChatModel`），专供**离线模式**切换为本地 LLM 生成；在线模式仍走 DeepSeek，二者通过 `@Qualifier` 区分，不冲突。
- DeepSeek 不提供 embedding，也不支持图片输入 / 文生图；图片理解走 qwen-vl-max、文生图走 Wanx，二者均经 DashScope（`AI_DASHSCOPE_API_KEY`），与 DeepSeek 互不冲突。以上 4 类模型靠 `@Qualifier` 区分，各自职责清晰。

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

## 阶段 3 增强：RAG 文件管理 + 向量数据库 + 离线模式
- **文件管理（手动控制检索增强开关）**：新增 `rag_file` 表（id / filename / content_type / size / storage_path / content_hash / status / doc_ids / 时间戳）。文件本体落盘到 `data/rag-files/`，元数据存库。上传只落盘（status=uploaded），**点「向量化」才切片 + bge-m3 向量化 + 写入向量库**（status=indexed），「移除索引」可回退，「删除」连文件带向量一起清。支持单文件 / 批量上传，多格式由 Tika 统一解析。**上传带内容哈希去重（SHA-256）**：同一份文件（即使改名）再次上传会被拦截提示，不重复落盘。
- **多格式解析**：`TikaDocumentReader` 一把覆盖 PDF / Word / Excel / PPT / TXT / MD（按文件扩展名自动选解析器），无需为每种格式写专门代码。
- **向量库可切换（内存 ↔ Milvus）**：`RagConfig` 的 `VectorStore` Bean 由 `rag.vectorstore.type` 控制——`memory`（默认，零依赖）或 `milvus`。业务侧（`QuestionAnswerAdvisor` / `RagService`）只依赖 `VectorStore` 抽象，切换实现零改动。Milvus 维度固定 1024 对齐 bge-m3；`docker/milvus-standalone.yml` 提供 etcd + minio + milvus 单机版一键启动。
  - **坑**：Spring AI 1.1.2 的 `MilvusVectorStore` 把 `doc_id` 字段**硬编码为 `VarChar(36)` 且不可配置**。上传文件的文档 ID 必须用 ≤36 字符的有效字符串，否则 insert 报 `io.milvus.exception.ParamException: Type mismatch for field 'doc_id'`。本项目的 `RagFileService` 已用「去横杠 UUID（32 字符）」作为 doc id，`fileId` 仍留在 metadata 中用于按文件移除索引。
- **离线模式（RAG 问答可断网）**：检索增强的「嵌入（Ollama bge-m3）+ 向量库（本地）」本就本地；唯一外网依赖是「生成」用的 DeepSeek。把 `rag.offline.enabled=true` 即可让生成切到本地 Ollama LLM（如 qwen2.5 / deepseek-r1），实现**全链路离线**。取舍：本地模型更慢、质量略低，需提前 `ollama pull` 对应模型。
- 启动仍自动索引 `classpath:rag-docs/*`（开箱即有内容可问）；用户上传文件走手动向量化，两者共存于同一向量库。
  - **防重复（种子索引守卫）**：`memory` 模式每次启动本就空，照常索引；`milvus` 模式因数据持久化，若集合已非空则**跳过**种子索引（`RagConfig.loadRagDocuments` 用 `vectorStore.similaritySearch(topK=1)` 探空判断），避免每次启动都重复 insert 同一批文档导致越积越多。需强制重建时设 `rag.seed.force-reindex=true`。

## 阶段 4 工具调用（@Tool + FunctionToolCallback）
- 目标：让 LLM 从"能答"进化到"能动手"——把确定性能力（时间、计算、查资料、建工单）暴露成工具，模型自主决定何时调用、传什么参数，Spring AI 在内部执行并回灌结果，最终用自然语言作答（内置 tool-execution loop）。
- 5 个工具（`tools/` 包，`@Tool` 标注）：`DateTimeTool`（当前时间）/ `CalculatorTool`（四则运算）/ `WeatherTool`（天气，模拟）/ `RagQueryTool`（知识库检索，复用阶段 3 向量库）/ `CreateTaskTool`（创建工单，复用阶段 2 的 TaskTicket）。
- 工具可视化：`ToolCallRecorder`(ThreadLocal) 在每个 `@Tool` 执行时主动 `record(name, params)`，Controller 通过 `begin()/collect()/clear()` 100% 可靠捕获调用明细做前端可视化（避开 1.1.2 `ChatResponseMetadata` 无 `getToolCalls()` 的坑；`finally clear()` 防并发泄漏）。
- 落库（计划外扩展）：`agentClient` 挂 `MessageChatMemoryAdvisor`（复用 JDBC 记忆）；`AgentController.chat` 复用 `ConversationService`(type=agent) + `ChatLogService`；新建 `task_ticket` 表（status/source/conversation_id/tags JSON），`CreateTaskTool` 建单即写库。
- 端点 `POST /api/agent/chat` + `GET /api/agent/tasks` + 演示页 `agent.html`（左侧会话列表复用 /api/chat/conversations?type=agent）。

## 阶段 5 多模态（图片理解 + 文生图）
- 目标：让产品首次具备非文本模态能力——"看懂"图片（图片理解）+ "画出来"内容（文生图）。
- 关键决策修正：**DeepSeek V4 公开 API（api.deepseek.com）仍为 text-only**，把图片当纯文本忽略（一度导致"看不到图"）。因此图片理解改走**通义千问视觉 qwen-vl-max**（DashScope），文生图走**通义万相 Wanx**（DashScope）；新增 `spring-ai-alibaba-starter-dashscope` 依赖，自动配置 `dashScopeChatModel`（视觉）+ `dashScopeImageModel`（文生图）两个 Bean。
- 图片理解：上传图 → `Media`(mimeType + ByteArrayResource) → `user(u -> u.text(question).media(media))` → qwen-vl-max 作答。**`qwen-vl-max` 必须开 `multi-model: true`**（yml `spring.ai.dashscope.chat.options`），否则请求打到文本端点、图片被丢。
- 文生图：`imageModel.call(new ImagePrompt(prompt))` → 拿到临时图片 URL → **后端代理下载成 base64 data URL** 返回（避免 DashScope 临时 URL 过期前端 403）；Wanx 异步生成，yml 放大 `retry.max-attempts=20` + 指数退避。
- 端点 `POST /api/multimodal/describe` + `POST /api/multimodal/generate` + 演示页 `multimodal.html`（双模块：图片理解上传预览 + 文生图 prompt 生成）。
- 详见 `docs/阶段5-知识点总结.md`。

## 阶段 6 Agent 编排（graph-core + agent-framework）
- 目标：从"一个能调工具的 Agent"升级到"多个 Agent 协作 + 图编排"——把复杂任务拆成可调度、可视化的工作流。
- 两层能力（SAA 1.1.2.2）：低层 `graph-core`（Java 版 LangGraph：StateGraph + Node + Edge + OverAllState + KeyStrategy）用于手写任意图；高层 `agent-framework` 提供开箱即用的 `ReactAgent` / `SequentialAgent` / `LlmRoutingAgent`。
- 新增依赖 `spring-ai-alibaba-graph-core` + `spring-ai-alibaba-agent-framework`（主 BOM 管理免 version）；编排主力模型用 **DeepSeek V4**（用户选择；若 Supervisor 嵌套 tool-calling 不稳可切 qwen）。
- 五种编排形态（均演示于 `agent6.html`，tab 切换）：
  1. **ReactAgent**（单 Agent 工具循环）：`ReactAgent.builder().methodTools(阶段4的5个@Tool).build()`，模型在 Agent 与 Tool 之间循环直到完成；`ToolCallRecorder` 可视化工具调用。
  2. **SequentialAgent**（顺序）：`subAgents(写作→评审)`，后一个自动读前一个输出。
  3. **LlmRoutingAgent**（路由）：LLM 判断问题类型后单次分发到研究 / 编程 / 写作助手。
  4. **graph-core 手写意图路由工作流**（原理入门）：入口 → classify 节点(LLM 分类) → 条件边 → 对应 worker → END，演示"图编排"最小形态。
  5. **graph-core 手写 Supervisor 多智能体**（监督者循环路由）：supervisor 节点循环决定调 researcher / coder 直到 FINISH；**注意 SAA 1.1.2.2 的 agent-framework 尚未提供封装的 SupervisorAgent，故用 graph-core 手写**，恰好印证"高层模式底层就是一张图"。
- 关键 API：`KeyStrategyFactory` 用 lambda `() -> Map.<String, KeyStrategy>of(...)` 声明各 key 的合并策略（Replace / Append）；`node_async(NodeAction)` / `edge_async(EdgeAction)` 为静态导入；`GraphStateException` 为 checked 异常需在 @Bean 方法签名声明。
- 端点 `POST /api/agent6/{react,sequential,routing,workflow,supervisor}` + 演示页 `agent6.html`。
- 详见 `docs/阶段6-知识点总结.md`。

## 阶段 7 MCP 集成（Model Context Protocol）
- 目标：把"工具"从**进程内 `@Tool` 方法**升级成**跨进程、跨语言的标准协议**。Agent 不再只能调用同进程的 Java 方法，还能调用一个独立的 Python 进程、一个独立的 Java 微服务——只要它们实现了 MCP 协议。这是从"单体 Agent"走向"工具即服务"的关键一步。
- **多模块工程**：根 POM `packaging=pom`，下挂两个模块——`ai-workshop`（主应用，端口 9999，MCP Client）+ `mcp-nacos-server`（独立 MCP Server，端口 9988，SSE 传输）。根目录另有 `mcp-servers/python-server/`（Python FastMCP Server，stdio 传输，非 Maven 模块）。
- **双路 MCP 集成**（两路并行，互不依赖）：
  1. **stdio 路线（Python FastMCP）**：主应用启动一个 Python 子进程，通过标准输入/输出收发 JSON-RPC，发现 4 个工具（`generate_uuid` / `generate_password` / `http_request` / `text_stats`）。配置在 `spring.ai.mcp.client.stdio.connections.python-tools-server`。
  2. **SSE 路线（Java Server）**：独立 Java 微服务 `mcp-nacos-server` 通过 SSE 端点 `/sse` 暴露 4 个工具（`generate_uuid` / `calculate` / `query_weather` / `generate_qrcode`）。主应用用标准 Spring AI SSE Client 直连 `http://localhost:9988/sse`（配置 `spring.ai.mcp.client.sse.connections.nacos-mcp-server`）。
- **工具汇聚**：`McpConfig` 收集所有 MCP `ToolCallback`（注入 `List<ToolCallback>`）→ `Agent6Config` 注入到 `mcpChatClient` → Agent 无感调用远程工具，与阶段 4 的本地 `@Tool` 用法一致。
- **依赖选型大坑（反编译核实）**：SAA 1.1.x 的 Nacos MCP 体系有三个 artifact，职责完全不同——`mcp-registry`（1.1.2.1，**仅服务端注册**，自动配置需 `McpSyncServer` bean，主应用没有 → 全部跳过）/ `nacos-mcp-client`（1.0.0.2 旧客户端，**直连 Nacos 发现**，不在 1.1.x BOM 里需显式写版本）/ `mcp-router`（1.1.2.1，**中间路由聚合层**，Client 不直连 Nacos 而连 router，router 再聚合多 MCP Server）。本项目最终采用方案 B：**主应用用标准 Spring AI SSE 直连**，放弃 Nacos 动态发现（1.1.x 要用 Nacos 发现得再装 mcp-router 中间层，阶段学习成本过高）。服务端 `mcp-nacos-server` 仍保留 `mcp-registry` 注册到 Nacos（注册和发现是两回事，注册不影响运行）。
- **配置分层坑**：Spring AI 1.1.2 的 MCP Client 配置按 transport **分层**——`spring.ai.mcp.client.stdio.connections.<name>` / `.sse.connections.<name>` / `.streamable-http.connections.<name>` 各占一个节点。`type` 是 client 级属性（`async`/`sync`），不是 per-connection；SSE 连接只有 `url` + `sse-endpoint` 两个字段。
- 端点 `GET /api/agent6/mcp/tools` + 演示页 `agent6.html` MCP tab。
- 详见 `docs/阶段7-知识点总结.md`。

## 阶段 8 工程化（可观测 + Guardrails + Docker 部署）
- **自建可观测**：自定义 `AiCallLogObservationHandler`（实现 `ObservationHandler<ChatModelObservationContext>`，`@Component` 自动被 ObservationRegistry 收集）→ 每次 LLM 调用落库 `ai_call_log`（模型/供应商/token/耗时/成败）。**必须加 `spring-boot-starter-actuator`**（ObservationRegistry 自动配置前提）。监控页 `obs.html` + `/api/obs/stats` + `/api/obs/logs`。Agent 工具循环的每次模型调用独立成条。
- **Langfuse（OTel 导出，全链路验证）**：`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`（Boot 3.5.11 管理版本）；`management.otlp.tracing.endpoint` 必须写完整 URL 带 `/v1/traces` + Basic Auth(pk:sk base64) + `x-langfuse-ingestion-version: 4`；`spring.ai.chat.observations.log-prompt/completion=true` 内容进 span。**Langfuse v3 自托管 = 6 服务**（Postgres + ClickHouse + Redis + MinIO + web + worker 独立镜像），compose 在 `docker/langfuse/langfuse-standalone.yml`，UI `http://localhost:3000`（admin@example.com / admin123456）。
- **Guardrails（Advisor 手写）**：`GuardrailAdvisor` 输入闸（敏感词/越狱正则/超长 → 短路返回提示语，模型不参与）+ 输出闸（PII 脱敏）；流式输出脱敏在 `ChatService.stream` 聚合全文后 `maskPii()`（DeepSeek 流式块 `textContent=null` 的坑，见总结文档 5.5）。规则全配置化（`guardrail.*`）。
- **工程化修复**：MySQL 时间东八区（HikariCP `connection-init-sql: SET time_zone='+08:00'`，必须数值偏移）；删除 `SchemaMigration`（schema.sql 唯一建表来源）；6 页面导航统一。
- **Docker 部署（单阶段）**：`ai-workshop/Dockerfile`（maven:3.9-eclipse-temurin-17 单镜像构建+运行）+ `docker/app-deploy.yml` compose 一键起（`host.docker.internal` 连宿主机 MySQL/Milvus/Ollama/Langfuse，API Key 走 `.env`，容器版精简 MCP）。
- 详见 `docs/阶段8-知识点总结.md`。

## 阶段 9 用户体系与演示加固（登录 + 隔离 + 限流）
- **认证**：Spring Security + JWT（无状态，HS384 由密钥长度自动选）——`JwtService`（生成/解析）/ `JwtAuthFilter`（Bearer → SecurityContext，Claims 放 details）/ `CurrentUser.id()`（Service 层隔离收口）。SecurityConfig 放行登录注册 + 静态资源（**必须通配符 `/*.html` `/*.js` 等，硬编码页面清单会漏掉脚本导致未登录不跳转**）+ 健康检查，其余 `/api/**` 全部登录，未认证 401。
- **用户隔离**：6 张业务表（conversation/chat_log/chat_memory/rag_file/task_ticket/ai_call_log）加 `user_id`，老数据归 admin（SchemaMigration 幂等迁移）；Service 层写入带 userId、查询按 userId 过滤、操作前 `checkOwnership` 越权校验；观测页按用户隔离（obs 只看自己的调用记录）；种子知识库全局共享。
- **限流**：`rate_limit_count` 表 + `RateLimitInterceptor`——用户+IP 双维度按天配额（聊天 50/100 次/天、上传 5/10 次/天，yml 可调），`INSERT ... ON DUPLICATE KEY UPDATE` 原子计数，超限 429。
- **Admin + 注册开关**：启动时无 admin 自动创建（环境变量 `APP_ADMIN_USERNAME/PASSWORD`）；`app.registration.enabled` 默认 false，**Service 层强校验**防直调接口（403「注册未开放」）。
- **前端**：`login.html`（无注册入口）+ `auth.js`（monkey-patch fetch 自动带 token + 401 跳登录 + 文件加载即执行未登录跳转，无闪烁）；7 个页面（含 ticket.html）统一接入。
- 详见 `docs/阶段9-知识点总结.md`。

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
- [x] 阶段 3 增强：RAG 文件管理（`rag_file` 表 + 单/批量上传落盘 + Tika 多格式解析 + 手动向量化/移除/删除端点 + rag.html 文件面板）
- [x] 阶段 3 增强：向量库可切换（Milvus 替换内存 SimpleVectorStore，维度 1024 对齐 bge-m3；docker/milvus-standalone.yml 一键起）
- [x] 阶段 3 增强：离线模式（生成模型可切本地 Ollama，嵌入+向量库+生成全本地，RAG 问答可断网）
- [x] 阶段 4：工具调用（`@Tool` + `FunctionToolCallback`，5 个工具：时间/计算器/天气/知识库检索/创建工单；`ToolCallRecorder`(ThreadLocal) 可视化工具调用；`agentClient` 注册工具）
- [x] 阶段 4 扩展：agent 对话落库（复用 `ConversationService` type=agent + `ChatLogService` + 记忆 Advisor）+ 工单落库（新建 `task_ticket` 表，`CreateTaskTool` 建单即写库；来源无关为阶段 6/7 留缝）
- [x] 阶段 5：多模态（`spring-ai-alibaba-starter-dashscope` 接入；qwen-vl-max 图片理解 + 通义万相 Wanx 文生图；`multimodal.html` 双模块演示页）
- [x] 阶段 6：Agent 编排（`spring-ai-alibaba-graph-core` + `spring-ai-alibaba-agent-framework`；ReactAgent 工具循环 + SequentialAgent 顺序 + LlmRoutingAgent 路由 + graph-core 手写意图路由工作流 + graph-core 手写 Supervisor 多智能体；`agent6.html` tab 演示五种形态；主力模型 DeepSeek V4）
- [x] 阶段 7：MCP 集成（多模块工程；stdio Python FastMCP + SSE Java Server 双路并行；标准 Spring AI SSE Client 直连；`McpConfig` 汇聚 ToolCallback → `mcpChatClient`；`agent6.html` MCP tab；8 个 MCP 工具）
- [x] 阶段 8：工程化三件套
- [x] 阶段 9：用户体系与演示加固（Spring Security + JWT 登录；6 类数据 user_id 全链路隔离；用户+IP 双维度限流；注册开关 + Admin 初始化；login.html + auth.js 前端认证）
  - [x] 可观测·自建：`AiCallLogObservationHandler`（Observation 落库 `ai_call_log`）+ `obs.html` 监控页 + `/api/obs/*`
  - [x] 可观测·Langfuse：OTel 导出全链路验证（traces 入库），Langfuse v3 六服务自托管（docker/langfuse/）
  - [x] Guardrails：`GuardrailAdvisor` 输入拦截（敏感词/越狱/超长，短路）+ 输出 PII 脱敏（含流式聚合脱敏）
  - [x] 工程化修复：MySQL 东八区时区、删除 SchemaMigration、6 页面导航统一
  - [x] Docker 部署：单阶段 Dockerfile + `docker/app-deploy.yml` compose 一键起

## 学习计划（每阶段 = 给产品加一块能力）
- [x] 阶段 1｜多轮对话与记忆：`ChatMemory` + Advisor（会话隔离、历史注入）
- [x] 阶段 2｜结构化输出与提示词：`PromptTemplate` + `BeanOutputConverter`
- [x] 阶段 3｜RAG 检索增强：`document-reader-*` → `TokenTextSplitter` → Embedding → 向量库 → `QuestionAnswerAdvisor` / RagWay
- [x] 阶段 4｜工具调用：`@Tool` + `FunctionToolCallback`（让模型调你的 Spring Bean）
- [x] 阶段 5｜多模态：通义千问图片理解 + 通义万相文生图
- [x] 阶段 6｜Agent 编排：`spring-ai-alibaba-graph-core` → `ReactAgent` → 多智能体（Sequential / Routing / Supervisor）
- [x] 阶段 7｜MCP 集成：标准 MCP（stdio Python Server + SSE Java Server），接入跨进程跨语言工具
- [x] 阶段 8｜工程化：可观测（自建 Observation 落库 + Langfuse OTel）、Guardrails（Advisor 手写护栏）、Docker 部署（单阶段）
- [x] 阶段 9｜用户体系与演示加固：Spring Security + JWT 登录、user_id 全链路隔离、用户+IP 限流、注册开关与 Admin 初始化、前端统一认证

## 参考
- 官网 / 文档：https://java2ai.com
- 版本说明：https://java2ai.com/docs/versions
- GitHub：https://github.com/alibaba/spring-ai-alibaba
- 官方 Playground 示例（含前端 UI，可抄）：仓库 `examples/` 目录
