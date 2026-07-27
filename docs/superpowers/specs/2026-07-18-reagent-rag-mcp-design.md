# ReAgent RAG + MCP 智能运维面试工程版设计规格

- 日期：2026-07-18
- 状态：已获用户整体确认，进入实施计划
- 基线：`a3d6b0ffc1eb335a3cb30debb516dc52a3f03adb`
- 目标项目：`/root/reagent`

## 1. 摘要

本轮同时解决两件事：

1. 补齐现有 ReAgent Runtime 的自动化工程证据，重点覆盖真实 MySQL/Redis、AgentRunner、StateStore、Redis Streams、双 Worker、lease/fencing 与崩溃恢复。
2. 在不替换现有 Runtime 的前提下，把 ReAgent 扩展为一套可一键启动、可现场演示的 RAG + MCP 智能运维事故处理 Agent。

最终场景是：Agent 先从 Runbook、架构文档和历史事故中检索带引用的证据，再通过 MCP 查询指标和日志；需要创建工单时进入持久化人工审批。即使在远端工单已创建、本地尚未记账的危险窗口崩溃，另一个 Worker 也能接管，并依靠稳定幂等键保证只产生一张工单。

RAG 与 MCP 都作为 ReAgent 工具进入现有消息日志、工具账本、ToolExecutor、租约、fencing、事件和 trace 路径。项目不引入第二套 Agent 框架或平行状态机。

## 2. 现状与问题

### 2.1 现有优势

ReAgent 已具备：

- ReAct / Tool Calling 主循环；
- MySQL 持久化消息上下文和工具调用账本；
- `PENDING → IN_PROGRESS → DONE | IN_DOUBT` 的副作用恢复语义；
- 子进程与 Docker 沙箱；
- SSE 事件重放；
- OpenTelemetry 链路；
- Worker lease、心跳、fencing token 与失败转移；
- Redis Streams 跨 Worker 事件传输。

`message` 是恢复后重新构造 LLM 上下文的唯一真相源，`tool_call` 是执行与幂等账本。新增能力必须沿用这两个事实来源。

### 2.2 当前缺口

当前基线 `./mvnw test` 共 54 个测试，在本次受限环境中 0 failure、0 error、4 个 Docker 用例因 Docker daemon 不可用而跳过。主要缺口是：

- 没有 AgentRunner 端到端测试；
- 没有真实 MySQL StateStore 集成测试；
- 没有 lease、双 Worker 与 fencing 集成测试；
- 没有 RedisStreamTransport 的真实 Redis 回归测试；
- 运行期消息、账本和工具结果写入尚未全部用 lease epoch 防止旧 Worker 陈旧提交；
- compose 只启动 MySQL、Redis 和 Jaeger，应用仍需手动运行；
- 没有业务场景、RAG、MCP、持久审批或薄演示控制台。

因此当前项目能说明 Runtime 的设计，但还不能用自动化证据完整证明核心可靠性，也不能在几分钟内展示一个业务闭环。

## 3. 目标

### 3.1 产品目标

- 保留现有 Coding Agent 行为，新增 `incident-ops` Agent Profile。
- 支持预置运维知识库的本地索引、向量检索和可核验引用。
- 使用官方 MCP Java SDK 连接 Fake Ops MCP Server。
- 提供指标查询、日志检索和创建工单三个 MCP 工具。
- 对创建工单实施跨重启的持久化审批。
- 使用 `tool_call_id` 作为下游幂等键，自动证明危险崩溃窗口不会重复建单。
- 提供 Spring Boot 静态 HTML/CSS/原生 JavaScript 控制台。
- 首次配置 LLM Key 后，一条命令启动完整演示栈。

### 3.2 工程目标

- 单元测试与 Testcontainers 集成测试职责清晰。
- CI 使用真实 MySQL、Redis 8 和真实 MCP Streamable HTTP 协议。
- LLM、MCP 业务返回和大部分 Embedding 测试均有确定性 Fake。
- 增加一个本地真实量化 MiniLM 的小型离线检索质量测试。
- 双 Worker、fencing、审批重启、拒绝路径和每个关键崩溃窗口均有自动化证据。
- 真实付费 LLM 不进入 CI，只作为人工 smoke。

### 3.3 演示目标

面试官应能在几分钟内看到：

1. Agent 为什么检索某份 Runbook；
2. 引用来自哪份文档和哪个段落；
3. Agent 查询了什么指标和日志；
4. 为什么创建工单需要人工批准；
5. 批准或拒绝后系统如何继续；
6. Worker 崩溃后为什么没有重复创建工单；
7. 对应的自动化测试和 trace 在哪里。

## 4. 非目标

首版明确不做：

- React、Vue、Node 构建链或完整运维 Dashboard；
- 真实 Prometheus、Loki、Elastic、Jira 或其他生产系统连接；
- 多租户、SSO、RBAC 和生产级审批身份认证；
- 任意文件上传、OCR、网页爬取和知识库管理后台；
- 混合检索、Cross-Encoder rerank 或复杂查询改写；
- 任务运行期间响应 MCP `tools/list_changed` 热更新；
- Kubernetes、服务网格、生产 HA 和通用工作流编排；
- MCP elicitation 作为审批状态源；
- 宣称任意第三方副作用都能做到 exactly-once；
- 在 CI 中调用真实付费 LLM；
- 完整中文向量模型质量优化。

不支持下游幂等键的副作用工具仍遵循现有保守语义：崩在 `IN_PROGRESS` 窗口时不盲目重试，而是进入 `IN_DOUBT`。

## 5. 总体架构原则

### 5.1 唯一控制面

ReAgent Runtime 仍是唯一控制面：

- LLM 只能看到当前 Task Profile 允许的工具；
- RAG 和 MCP 工具都经 ToolRegistry / ToolExecutor 调用；
- 所有 assistant/tool 消息继续写入现有消息日志；
- 所有调用继续写入工具账本；
- 审批、恢复和事件均围绕同一 `task_id`、`tool_call_id` 和 lease epoch。

RAG 模块不直接驱动 LLM，MCP Client 也不能绕过 ToolExecutor 直接产生业务副作用。

### 5.2 组件

新增或扩展的主要组件如下：

| 组件 | 职责 |
|---|---|
| `AgentProfileRegistry` | 定义 `coding` 与 `incident-ops` Profile，解析 system prompt、工具白名单、知识库和 MCP Server |
| `TaskProfileSnapshot` | 在任务创建时持久化 Profile 与工具目录快照，保证恢复后语义不漂移 |
| `EmbeddingPort` | 把文本转换为向量；生产实现使用本地量化 MiniLM，测试实现使用固定向量 |
| `KnowledgeIndex` | 索引与检索 chunk；生产实现使用 Redis 8 HNSW |
| `KnowledgeSearchTool` | 以 `READ_ONLY` 工具返回有界、带引用的检索结果 |
| `McpClientManager` | 管理 MCP 初始化、工具发现、连接、超时与关闭 |
| `McpToolAdapter` | 把 MCP Schema 和调用结果适配为 ReAgent `Tool` |
| `ApprovalGate` | 在任何需要授权的工具执行前建立持久化屏障 |
| `ApprovalService` | 处理批准、拒绝、重复决定、冲突决定与任务恢复 |
| `Fake Ops MCP Server` | 提供确定性指标、日志、工单和幂等/故障注入能力 |
| 薄控制台 | 启动任务、消费 SSE、显示引用和工具调用、批准/拒绝、显示最终工单 |

### 5.3 持久化模型变更

| 存储 | 变更 |
|---|---|
| `task` | 增加 `profile_id`、以 LONGTEXT 保存的有界 JSON `profile_snapshot`，状态增加 `WAITING_APPROVAL` |
| `message` | 保持现有真相源结构；不为 RAG/MCP 新建平行上下文表 |
| `tool_call` | 增加 `assistant_message_seq` 以标识工具批次，状态增加 `REJECTED` |
| `approval_request` | 新表；`tool_call_id` 为主键并关联 task，保存参数快照、决定状态和审计字段 |
| `demo_ticket` | Fake MCP 使用；`idempotency_key` 唯一，保存稳定 `ticket_id` 和合成工单内容 |
| Redis | 保留现有任务 Stream 键空间，新增带版本的 RAG chunk 与 HNSW 索引键空间 |

新字段和状态必须在“从当前基线 Schema 升级”和“全新空库启动”两种 Testcontainers 场景中验证，保证已有本地 volume 无需人工改表。

## 6. Agent Profile 与工具目录

### 6.1 兼容性

创建任务的 API 增加可选 `profile`：

```json
{
  "goal": "Investigate checkout errors and create a P1 ticket if evidence supports it.",
  "profile": "incident-ops"
}
```

- 缺省 `profile` 保持当前 Coding Agent 行为，避免破坏已有调用方。
- `incident-ops` 使用专用 system prompt、RAG 知识库和 MCP 工具白名单。
- Task 持久化 `profile_id` 与解析后的 `profile_snapshot`。

快照至少包含：

- profile 版本；
- system prompt 版本或内容摘要；
- 工具内部名、展示名和 JSON Schema；
- MCP Server 标识；
- `IdempotencyClass`；
- `ApprovalPolicy`；
- 知识库索引版本。

任务恢复使用原快照；MCP 工具目录变化只对新任务生效。

现有本地 Coding 工具在 `coding` Profile 中显式保持当前 `ApprovalPolicy.NONE`，避免新增审批机制改变已有演示；fail-closed 默认只作用于尚未配置的动态 MCP 工具。

### 6.2 MCP 工具命名

内部 LLM 工具名使用兼容函数名约束的稳定名称：

- `mcp_ops_query_metrics`
- `mcp_ops_search_logs`
- `mcp_ops_create_ticket`

控制台可以展示更易读的 `ops.query_metrics` 等名称。发现时如出现重名、非法 Schema 或超出白名单，启动失败并给出明确错误，不静默覆盖。

## 7. RAG 设计

### 7.1 数据集

仓库内置小型合成英文数据集，至少包含：

- checkout 数据库连接池 Runbook；
- checkout 服务架构说明；
- 一次相似的历史事故复盘；
- 若干无关文档，用来验证排序而不是关键词硬匹配。

文档是合成数据，不包含生产信息。最终 LLM 可以用中文回答，但首版检索文档和查询统一使用英文，以适配 MiniLM 模型能力。

### 7.2 切分和引用

每个 chunk 保存：

- `chunk_id`；
- `document_id`；
- `title`；
- `section`；
- `source`；
- `content`；
- `checksum`；
- `embedding_model`；
- `index_version`。

切分按标题和段落优先，并有最大长度与有限重叠。`chunk_id` 由稳定输入生成，因此重复初始化不会产生重复数据。

`search_knowledge` 返回有界 JSON 文本：

```json
{
  "query": "checkout connection pool exhaustion",
  "hits": [
    {
      "chunkId": "runbook-checkout-db-pool#mitigation",
      "title": "Checkout DB Pool Runbook",
      "section": "Mitigation",
      "source": "knowledge/runbooks/checkout-db-pool.md",
      "score": 0.89,
      "excerpt": "..."
    }
  ]
}
```

LLM 最终答案只能引用工具实际返回的 `chunkId`；无命中时必须明确证据不足，不能生成虚假引用。

### 7.3 Embedding

生产演示使用 LangChain4j 独立的 `langchain4j-embeddings-all-minilm-l6-v2-q:1.18.0-beta28` 进程内 ONNX 量化模型：

- 不采用 LangChain4j Agent/RAG 框架；
- 不增加 Ollama、TEI 或外部 Embedding API；
- 使用有界 Executor，避免默认占满 CPU；
- 应用首次构建会下载较大的 Maven 模型依赖，README 必须说明。

普通功能测试使用 `FakeEmbedding`：按测试语料映射到固定向量，不使用文本哈希猜测相似度。另设一个真实量化模型的离线质量测试，验证打包模型和合成语料确实可用。

### 7.4 Redis 8 向量索引

Redis 从 `7-alpine` 升级到明确的 Redis 8 镜像。`RedisKnowledgeIndex` 使用 HNSW、余弦相似度和 384 维向量。

键空间固定为：

- chunk：`rag:incident:chunk:{chunkId}`；
- 索引：`idx:rag:incident:{indexVersion}`；
- 初始化锁：`rag:incident:init:{indexVersion}`。

初始化过程幂等：比较数据集与模型版本，存在相同 checksum 时跳过，不一致时构建新版本并在成功后切换活动索引。首版不在线增量更新文档。

升级 Redis 的前置门禁是现有 Streams 行为全部通过真实 Redis 8 回归测试。

## 8. MCP 设计

### 8.1 SDK 与传输

使用官方 MCP Java SDK `2.0.0`：

- 导入 `mcp-bom:2.0.0`；
- 依赖 `mcp-core` 与 `mcp-json-jackson2`；
- 不使用默认带 Jackson 3 的 convenience bundle；
- Client 使用同步 facade，匹配当前 Java 21 虚拟线程/阻塞执行模型；
- 使用 Streamable HTTP；
- 不新建已弃用的旧 SSE transport。

Client 的标准顺序是：

1. `initialize()`；
2. `listTools()`；
3. 校验、命名空间化并冻结工具目录；
4. `callTool()`。

请求配置连接超时和调用超时。协议错误、Schema 错误和 MCP `isError` 被转换为结构化工具观察结果，由 Agent 决定后续；安全不变量错误则停止当前 Worker。

### 8.2 Fake Ops MCP Server

Fake Server 是 compose 中独立于 ReAgent Worker 的演示服务和容器，使用相同官方 MCP 协议实现：

| 工具 | 行为 | 幂等等级 | 审批策略 |
|---|---|---|---|
| `query_metrics` | 返回预置错误率、延迟和连接池趋势 | `READ_ONLY` | `NONE` |
| `search_logs` | 返回预置服务日志和时间窗结果 | `READ_ONLY` | `NONE` |
| `create_ticket` | 创建并返回合成 P1 工单 | `IDEMPOTENT` | `REQUIRE_APPROVAL` |

`create_ticket` 是业务写操作，因此首次执行必须审批；同时它接收由 ReAgent 注入的稳定 `idempotency_key=tool_call_id`，所以崩溃后可安全重放。两者是不同维度。

`idempotency_key` 是适配器保留字段：Fake MCP 的服务器 Schema 接受它，但 `McpToolAdapter` 从暴露给 LLM 的 Schema 中移除该字段，并在实际 callTool 前注入。模型或用户参数若主动包含同名字段则校验失败，不能覆盖 Runtime 生成的键。

Fake Server 把工单写入 MySQL `demo_ticket` 表，`idempotency_key` 具有唯一约束。`create_ticket` 使用“插入或读取既有行”的事务语义：同一键重复调用必须返回同一 `ticket_id`。demo/test Profile 额外提供只读验收端点，用于读取调用次数、唯一工单数和指定幂等键对应的工单；该端点不进入普通生产 Profile。

### 8.3 失败关闭

MCP Server 提供的 annotations 只作提示，不直接成为本地安全策略。ReAgent 本地 Profile 必须为工具指定：

- 重放等级；
- 审批策略；
- 超时；
- 是否允许暴露给 LLM。

缺失配置的动态 MCP 工具默认 `SIDE_EFFECTFUL + REQUIRE_APPROVAL`，且不自动进入 Profile 白名单。

Client 重连后重新发现到的工具 Schema 必须与 Task 快照中的 Schema hash 一致；不一致时返回明确的 schema-drift 错误并停止该调用，不能用新 Schema 悄悄继续旧任务。

## 9. 审批与工具安全模型

### 9.1 两条独立轴

`IdempotencyClass` 回答“崩溃后能否安全重放”：

- `READ_ONLY`；
- `IDEMPOTENT`；
- `SIDE_EFFECTFUL`。

新增 `ApprovalPolicy` 回答“首次执行前是否需要授权”：

- `NONE`；
- `REQUIRE_APPROVAL`。

不能使用 `IdempotencyClass` 推导审批，否则会把授权和恢复语义混在一起，并可能破坏现有 Coding Agent 行为。

### 9.2 持久化审批

新增 `approval_request`，以 `tool_call_id` 为主键，保存：

- `task_id`；
- `assistant_message_seq`；
- `tool_name`；
- 参数快照；
- 请求原因与引用摘要；
- `PENDING | APPROVED | REJECTED`；
- 请求时间、决定时间、决定人和可选理由；
- 乐观锁版本。

新增 Task 状态 `WAITING_APPROVAL`。它与用户主动 `PAUSED` 不同：

- `PAUSED` 表示用户暂停整个 Agent；
- `WAITING_APPROVAL` 表示某个已持久化工具调用正在等待授权；
- 等待期间任务释放 lease，故障扫描不能自动执行该工具；
- 应用或浏览器重启后审批卡仍存在。

首版审批没有自动超时：`PENDING` 会一直保持，直到收到明确决定或 Task 被取消。取消一个 `WAITING_APPROVAL` Task 时，系统原子地把尚未决定的审批和未执行账本项写为 `REJECTED`，reason 固定为 `task_cancelled`，然后把 Task 置为 `CANCELLED`；任何 MCP 写操作都不得发生。

### 9.3 整批审批屏障

一次 assistant 消息可能包含多个工具调用。首版采用整批屏障：

1. assistant 消息和全部 `tool_call=PENDING` 先事务落库；
2. 如果该批次有任何尚未决定的审批项，则整批工具都不执行；
3. `ApprovalGate` 创建所需审批并把 Task 原子转为 `WAITING_APPROVAL`；
4. 所有审批项进入 `APPROVED` 或 `REJECTED` 后，任务才恢复；
5. 可执行项运行，拒绝项生成 synthetic tool result；
6. 所有结果按原始 tool-call 顺序落库。

这样不会出现半批结果已写、半批仍等待而导致上下文顺序不确定。

### 9.4 批准与拒绝

决定 API 使用条件更新：

- `PENDING → APPROVED`；
- `PENDING → REJECTED`；
- 重复提交相同决定返回当前状态，保持幂等；
- 已有相反决定时返回 `409 Conflict`；
- Task 已取消或终态时拒绝新决定。

拒绝是正常业务分支：

- 不调用远端工具；
- 工具账本增加 `REJECTED` 终态；
- 写入与原 `tool_call_id` 配对的 tool result；
- Agent 继续解释“用户拒绝，未创建工单”，而不是把任务标成 FAILED。

所有审批决定完成后，Task 转回 `RUNNING` 并清空 owner。API 会立即尝试异步恢复；即使进程在数据库提交后、启动线程前崩溃，扫描器也必须能发现 `owner IS NULL` 的 RUNNING 任务并接管，避免恢复双写窗口。

## 10. Fencing 与持久化一致性

### 10.1 Run Token

每次成功 claim 产生：

```text
TaskRunToken(taskId, workerId, leaseEpoch)
```

Agent 驱动期间的持久写入必须携带此 token，包括：

- assistant 消息和其 tool-call 登记；
- `PENDING → IN_PROGRESS`；
- `DONE | IN_DOUBT | REJECTED` 与 tool result；
- 进入 `WAITING_APPROVAL`；
- Task 完成或失败；
- Agent 产生的持久化 STEP/TOOL/APPROVAL 事件。

### 10.2 事务守卫

StateStore 写事务使用 `SELECT ... FOR UPDATE` 锁定 Task 行，再验证：

- Task 仍为当前可写状态；
- `owner_id` 等于当前 Worker；
- `lease_epoch` 等于 token epoch。

验证和消息/账本写入处于同一事务。若新 Worker 已 claim，旧 Worker 的验证失败并抛出专用 `FencedExecutionException`。AgentRunner 捕获后只停止旧驱动，绝不把任务标成 FAILED 或覆盖新 Worker 结果。

LLM token 是 live-only 数据，可带 epoch 并在本地收到 fenced 信号后丢弃；它不作为恢复真相源。

### 10.3 工具调用边界

任何远端调用前仍先提交 `IN_PROGRESS`。恢复决策如下：

| 本地账本 | 工具等级 | 恢复行为 |
|---|---|---|
| `PENDING` | 任意 | 一定尚未执行，可正常进入审批/执行 |
| `IN_PROGRESS` | `READ_ONLY` | 安全重放 |
| `IN_PROGRESS` | `IDEMPOTENT` | 使用同一幂等键安全重放 |
| `IN_PROGRESS` | `SIDE_EFFECTFUL` | 不盲目重放；先对账，否则 `IN_DOUBT` |
| `DONE/IN_DOUBT/REJECTED` | 任意 | 终态，不重复执行 |

框架账本只能缩小并识别崩溃窗口。真正关闭“远端成功、本地未记账”窗口需要下游接受幂等键；本项目只对 Fake `create_ticket` 自动证明这一点。

## 11. 端到端数据流

主场景目标：调查 checkout 错误率突增；证据满足条件时创建 P1 工单。

1. 控制台用 `incident-ops` Profile 创建任务。
2. Task 持久化 Profile 快照、system/user 消息并获得 Worker A 的 lease。
3. LLM 调用 `search_knowledge`。
4. Redis 8 返回连接池 Runbook 和历史事故，结果带稳定引用并作为 tool message 落库。
5. LLM 调用 `mcp_ops_query_metrics` 与 `mcp_ops_search_logs`。
6. Fake MCP 返回错误率 14.2%、延迟升高和 `pool exhausted` 日志。
7. LLM 生成 `mcp_ops_create_ticket` 调用及参数。
8. assistant/tool-call 先落库，ApprovalGate 创建审批，Task 进入 `WAITING_APPROVAL` 并释放 lease。
9. 控制台显示工具、参数、理由和相关引用。
10. 用户批准后任务恢复；执行前账本置 `IN_PROGRESS`，适配器注入 `tool_call_id` 幂等键。
11. Fake MCP 创建并返回如 `OPS-1042` 的唯一工单。
12. StateStore 事务写 `DONE + tool result`。
13. LLM 生成包含诊断、引用、观测事实和 ticket_id 的最终答案。
14. Task 以 epoch 守卫写 COMPLETED，控制台和 Jaeger 展示完整链路。

拒绝分支在第 10 步不调用 MCP，而是写 REJECTED tool result；LLM 说明未建单并给出人工建议。

## 12. 自动故障注入与恢复

用户不需要手动制造故障、杀进程或修改数据库。

### 12.1 CI 故障点

生产代码保留默认 no-op 的 `FaultInjector` 接口；测试实现可在以下位置确定性暂停、抛出或关闭 Worker：

- assistant/tool-call 已提交，审批请求未提交；
- 审批已决定，恢复线程未启动；
- 工具账本已为 IN_PROGRESS，远端尚未调用；
- 远端副作用成功，本地 tool result 尚未提交；
- tool result 已提交，最终答案尚未生成。

使用 latch、注入 Clock 和显式事件，不依赖长时间 `sleep` 猜时序。

### 12.2 危险窗口证明

核心自动化场景：

1. Worker A 获得 epoch 1；
2. Fake MCP 按幂等键创建 `OPS-1042`；
3. 故障点让 Worker A 在本地记账前停止；
4. lease 过期或由测试 Clock 推进；
5. Worker B claim，epoch 变为 2；
6. Worker B 看到 IN_PROGRESS + IDEMPOTENT，使用同一键重放；
7. Fake MCP 返回原 `OPS-1042`；
8. Worker B 写 DONE 并完成 Task；
9. 恢复 Worker A 后尝试陈旧写入，必须收到 FencedExecution；
10. 断言 Fake MCP 只有一个唯一工单，Task 只有一套有效结果。

### 12.3 可选现场故障演示

除普通 `demo-up.sh` 外，提供：

```bash
./scripts/demo-failover.sh
```

脚本只操作本项目的隔离 demo 容器，自动：

- 启动 Worker A、Worker B 和 Fake MCP；
- 创建并批准预置任务；
- 在受控危险窗口停止 Worker A；
- 等待 Worker B 接管；
- 检查任务完成；
- 检查唯一 ticket 数为 1；
- 输出任务、工单和 trace 地址。

正常演示不会随机崩溃；只有显式运行该脚本才启用 demo-only 故障注入能力。

为保证现场脚本不靠猜时序，demo-chaos 下的 Fake MCP 在工单事务提交后支持配置一个短暂的响应阻塞点。脚本轮询只读验收端点确认唯一工单已经存在，再只对 compose 项目内的 Worker A 执行 `docker compose kill`；Worker B 随后用同一幂等键恢复。该阻塞点和验收端点默认关闭。

## 13. API、事件与薄控制台

### 13.1 API

在保持现有 API 兼容的基础上增加：

- `POST /api/tasks`：接受可选 `profile`；
- `GET /api/tasks/{taskId}/approvals`：读取待处理和历史审批；
- `POST /api/tasks/{taskId}/approvals/{toolCallId}/decision`：批准或拒绝；
- `GET /api/knowledge/sources/{chunkId}`：读取演示知识来源的安全摘要；
- 既有任务详情与 SSE stream 继续使用。

审批 decision body 至少包含 `APPROVE | REJECT` 和可选理由。首版没有真实身份系统，`decided_by` 只能作为 demo 元数据，不能宣称为安全认证。

### 13.2 事件

新增或明确事件：

- `RAG_RETRIEVED`；
- `APPROVAL_REQUIRED`；
- `WAITING_APPROVAL`；
- `APPROVAL_APPROVED`；
- `APPROVAL_REJECTED`；
- `RECOVERY_ATTEMPT`；
- 既有 `TOOL_CALL`、`TOOL_RESULT`、`COMPLETED` 等继续使用。

`WAITING_APPROVAL` 对当前一次 SSE run 是收尾事件，但不是 Task 终态。控制台在用户决定后用 last event id 重新连接，继续接收恢复后的事件。

Task、approval 和 tool-call 的 REST 查询结果是权威状态；SSE 是可重放的展示投影。若进程恰好在状态事务提交后、事件发布前崩溃，页面通过重新读取 Task/approval 状态完成纠正，不能只依赖某一条事件决定安全行为。

### 13.3 页面

页面由 Spring Boot 静态资源直接提供，不引入 Node：

- 预置事故描述与启动按钮；
- Task 状态和顺序时间线；
- RAG 引用卡片；
- MCP 调用参数与结果；
- 写操作醒目标识；
- 审批/拒绝卡片；
- 最终诊断与 ticket_id；
- 失败、超时、IN_DOUBT 和接管信息。

前端渲染 LLM/MCP/RAG 外部文本必须使用 `textContent`；结构化卡片逐字段创建 DOM 节点，不能把外部内容直接注入 `innerHTML`。

## 14. 错误处理

| 情况 | 行为 |
|---|---|
| RAG 无命中 | 返回空 hits 和查询元数据；Agent 明确证据不足 |
| Redis 索引未就绪 | readiness 失败；一键启动不宣布成功 |
| MCP 初始化/发现失败 | 对必需 Server 启动失败；不提供半可用 Profile |
| MCP 读取工具超时 | 返回结构化错误 tool result，可由 Agent解释或重试 |
| create_ticket 超时 | 使用同一幂等键恢复；若下游不支持幂等则 IN_DOUBT |
| MCP 未知工具/非法 Schema | 不注册，启动或 Profile 解析失败 |
| 用户拒绝 | REJECTED tool result，任务继续，不标 FAILED |
| 重复同一审批决定 | 幂等返回当前结果 |
| 冲突审批决定 | 409，不覆盖先前事实 |
| 等待审批时重启 | 保持 WAITING_APPROVAL，不自动执行 |
| 旧 Worker 写入 | FencedExecution，旧 Worker 停止，不改 Task 终态 |
| 恢复次数超限 | 沿用现有止损策略，Task FAILED |

## 15. 可观测性

保留现有 task/step/LLM/tool span，并增加：

- `rag.embed`；
- `rag.search`；
- `mcp.initialize`；
- `mcp.list_tools`；
- `mcp.call_tool`；
- `approval.wait`；
- `approval.decision`；
- `agent.recovery`。

新增 span 必须记录适用的以下属性：

- task/profile/worker/lease epoch；
- MCP server 和工具名；
- tool_call_id；
- RAG topK、命中数和 chunk_id；
- approval status；
- recovery attempt；
- idempotency replay / deduplicated 标记。

不在 span 中记录 API Key、完整文档正文、敏感工具参数或未截断的模型输出。

## 16. 安全边界

- LLM Key 只从环境或本地 `.env` 读取，`.env` 必须忽略；
- Fake MCP 默认只在 demo 网络和本机端口可达；
- Task 输入不能动态指定任意 MCP URL，避免 SSRF；
- MCP endpoint 由受信配置/Profile 决定；
- Schema、参数、工具结果和 RAG excerpt 都有大小上限；
- 所有工具有超时；
- 未分类工具 fail-closed；
- demo 工单没有真实外部副作用；
- reset 数据必须是单独显式命令，普通 down 不删除 volume；
- 故障注入只在 test/demo-chaos Profile 开启，默认生产 Profile 没有控制入口。

## 17. 一键启动与最终使用体验

### 17.1 首次配置

用户只需一次：

```bash
cp .env.example .env
# 在 .env 中填写 DEEPSEEK_API_KEY
```

不提交真实 Key。真实演示不承诺零凭据；Fake LLM smoke 不需要 Key。

### 17.2 后续启动

```bash
./scripts/demo-up.sh
```

该脚本负责：

1. 检查 Docker/Compose、端口和必要配置；
2. 构建 ReAgent 与 Fake MCP 镜像；
3. 启动 MySQL、Redis 8、Jaeger、Fake MCP 和 ReAgent；
4. 等待各容器 healthcheck；
5. 幂等初始化知识库和向量索引；
6. 检查 ReAgent readiness、MCP discovery 和 RAG index；
7. 失败时明确指出未通过的依赖，不输出假成功；
8. 成功后打印控制台、API 和 Jaeger 地址。

默认完整栈包括：

- ReAgent App + 静态控制台；
- MySQL 8；
- Redis 8；
- Jaeger；
- Fake Ops MCP Server；
- 预置知识数据与索引初始化。

配套命令：

- `demo-down.sh`：停止但保留数据；
- `demo-reset.sh`：显式删除本项目 demo 数据并重建；
- `demo-smoke.sh`：Fake LLM 下自动跑完整闭环；
- `demo-failover.sh`：自动运行双 Worker 故障恢复演示。

用户无需手动启动 Maven、初始化数据库、录入知识、杀 Worker 或修改工单数据。

## 18. 自动化证据设计

### 18.1 测试分层

#### 单元测试

- Profile 解析和工具白名单；
- 文档切分、稳定 chunk id 和引用格式；
- FakeEmbedding 与检索排序；
- MCP 名称转换、Schema 适配和冲突；
- MCP 本地安全策略默认值；
- ApprovalPolicy 与 IdempotencyClass 两轴组合；
- 整批审批屏障；
- 批准、拒绝、重复和冲突决定；
- tool_call_id 到下游幂等键的映射；
- LLM 决策序列和错误结果格式；
- 现有沙箱、trace、stream replay 和工具测试继续通过。

#### Testcontainers 集成测试

- MySQL 真实 Schema 和枚举长度；
- StateStore 上下文重建；
- approval_request 跨 Context/重启保持；
- assistant/tool-call、账本和消息事务一致性；
- 所有运行期写入的 epoch guard；
- Redis 8 HNSW 创建、索引、topK、元数据和空结果；
- Redis 8 上现有 Streams publish/replay/live 行为；
- 官方 MCP Client 与 Fake Server 的 initialize/listTools/callTool；
- MCP 超时、错误、重复幂等键和唯一工单。

#### Runtime 场景测试

使用 scripted Fake LLM、FakeEmbedding、真实 MySQL/Redis 和 Fake MCP：

- RAG → metrics/logs → approval → ticket → final 的 happy path；
- 审批拒绝且 MCP create_ticket 调用数为 0；
- 等待审批时应用重启；
- 批准提交后、恢复线程前崩溃；
- 五个关键故障点；
- Worker A/Worker B 抢租约只有一个成功；
- 旧 epoch 消息、账本、结果和终态写入全部失败；
- 危险窗口恢复后唯一工单数为 1；
- RAG 无命中、MCP 不可用和最大恢复次数止损。

双 Worker 测试使用不同 WorkerIdentity、共享 Testcontainers 数据库/Redis、可控 Clock 和 latch，不以随机 sleep 作为正确性依据。

#### RAG 质量测试

用实际打包的量化 MiniLM 对固定语料和固定查询运行小型离线评测：

- 所有关键查询的预期文档必须进入 top 3；
- 固定评测集的 MRR 必须达到 `0.80`；
- 每个返回 hit 都能解析到真实 chunk 和 source；
- 评测失败时输出 query、预期文档和实际排名。

该测试不需要 API Key 或外部推理服务。

#### Compose smoke

`demo-smoke.sh` 使用 Fake LLM：

1. 启动完整 compose；
2. 创建预置任务；
3. 等待审批；
4. 调用批准 API；
5. 等待 COMPLETED；
6. 断言最终答案包含 citation 和 ticket_id；
7. 断言 Fake MCP 只有一个工单；
8. 输出可读验收摘要。

### 18.2 CI

CI 分为三个明确 job：

- fast unit job：`./mvnw -B test`；
- Docker integration job：先 `docker info`，再 `./mvnw -B -Pci verify`；
- compose smoke job：独立运行 `demo-smoke.sh`。

现有需要 Docker 的 sandbox 用例迁移到 integration/Failsafe 测试集；fast unit job 不依赖 Docker。

`-Pci` 下核心 Docker/Testcontainers 测试不得因 Docker 不可用而静默 skip，环境缺失应直接失败。真实 LLM 永不进入 CI。

无论成功失败，上传 Surefire/Failsafe 报告和可读的 acceptance summary。测试数量只作结果，不作为验收目标；验收以行为矩阵是否全部覆盖为准。

## 19. 手工真实 LLM smoke

真实 LLM 只验证：

- 模型能理解 `incident-ops` system prompt；
- 能按 Schema 调用 RAG/MCP 工具；
- 能在审批拒绝后正常解释；
- 能基于已有引用和工具结果生成自然最终答案。

不对具体措辞做 CI 断言，也不把真实 LLM 成功率包装成确定性证据。

## 20. 验收标准

### 20.1 功能

- 缺省 Profile 保持现有 Coding Agent 兼容；
- `incident-ops` 能完成预置事故全链路；
- 最终答案至少包含一个真实 RAG 引用、指标/日志证据和唯一 ticket_id；
- 审批拒绝时不调用 create_ticket；
- 等待审批期间重启后审批仍可继续；
- MCP 工具只从冻结的任务目录暴露。

### 20.2 可靠性

- 双 Worker 同时 claim 时只有一个获得执行权；
- 新 Worker 接管后，旧 epoch 的所有 StateStore 持久写入被拒绝；
- 五个关键崩溃窗口均有确定性测试；
- 远端工单已创建、本地未记账时恢复，最终唯一工单数仍为 1；
- 不支持幂等的副作用工具不会被自动重放；
- Redis 8 升级后现有 Streams 行为不退化。

### 20.3 自动化证据

- 单元测试通过；
- Testcontainers MySQL/Redis 8 测试通过；
- 官方 MCP 协议契约测试通过；
- Runtime happy/reject/restart/crash/fencing 测试通过；
- 真实本地 Embedding 离线评测达标；
- Compose Fake LLM smoke 通过；
- CI 核心集成测试无静默跳过；
- 报告可下载/查看，测试名称能直接对应验收行为。

### 20.4 演示与使用

- 首次填写 Key 后，`./scripts/demo-up.sh` 一条命令完成全栈启动；
- 启动脚本只有在依赖、MCP 和 RAG 全部 ready 后才报告成功；
- 控制台能展示时间线、引用、工具结果、审批和最终工单；
- `demo-failover.sh` 自动制造并验证故障，用户无需手动杀进程；
- 普通演示不随机注入故障；
- README 提供 5 分钟正常演示和可选故障演示脚本。

## 21. 与当前版本的最终区别

| 当前版本 | 本轮完成后 |
|---|---|
| compose 只起依赖，应用需手动 Maven 启动 | 配置一次 Key 后，一条命令启动完整栈并健康检查 |
| 通用 Coding Agent、固定提示和静态工具 | 保留 Coding Agent，新增持久化 incident-ops Profile 与工具快照 |
| 无知识检索和引用 | 本地 MiniLM + Redis 8 RAG，结果带稳定引用 |
| 无 MCP 标准连接 | 官方 MCP Java SDK 2.0 + Streamable HTTP + Fake Ops Server |
| 无持久化业务审批 | tool_call 级审批、WAITING_APPROVAL、批准/拒绝与跨重启恢复 |
| fencing 主要保护任务终态 | 运行期消息、账本、工具结果、审批和持久事件全部受 epoch 保护 |
| 主要是小型单元测试 | 真实 MySQL/Redis/MCP、双 Worker、故障矩阵和 compose smoke |
| 可靠性靠设计说明 | 危险崩溃窗口自动证明只产生一个工单 |
| 无业务演示界面 | 薄控制台展示证据、决策、审批、恢复和工单闭环 |

## 22. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Redis 8 升级破坏既有 Streams | 真实 Redis 8 回归作为合并门禁 |
| MiniLM 对中文不理想 | 首版语料/query 用英文；离线质量阈值；最终答案可中文 |
| ONNX 模型增大首次构建时间 | 使用量化模型；文档说明；不再增加外部 Embedding 服务 |
| MCP SDK 与 Jackson 版本冲突 | 只用 core + jackson2 模块，BOM 固定 2.0.0 |
| MCP 工具安全声明不可信 | 本地白名单和 fail-closed 策略，不信任远端 annotations |
| 审批与恢复形成新的双写窗口 | approval/Task 原子状态转换；RUNNING owner-null 由扫描器兜底 |
| 旧 Worker 在接管后写陈旧历史 | 所有 StateStore 运行期写入携带 token 并在事务内锁行验证 |
| “exactly-once”被过度宣传 | 明确限定：只有支持幂等键的下游关闭最后窗口，其他工具 IN_DOUBT |
| 真实 LLM 输出不稳定 | CI 全部使用 scripted Fake LLM，真实模型只 smoke |
| 范围膨胀 | 坚持合成数据、Fake MCP、薄 UI 和明确非目标 |

## 23. 官方技术依据

- MCP Java SDK：[GitHub](https://github.com/modelcontextprotocol/java-sdk)
- MCP Java SDK 2.0.0：[Release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0)
- MCP Java Client 文档：[Client](https://java.sdk.modelcontextprotocol.io/latest/client/)
- MCP Java Quickstart：[Quickstart](https://java.sdk.modelcontextprotocol.io/latest/quickstart/)
- Redis 向量数据库：[Vector database](https://redis.io/docs/latest/develop/get-started/vector-database/)
- Redis Search/Query：[Search and query](https://redis.io/docs/latest/develop/ai/search-and-query/)
- LangChain4j 1.18.0：[Release](https://github.com/langchain4j/langchain4j/releases/tag/1.18.0)

## 24. 后续流程

本规格经整体复核后，下一步才进入逐 Task 的 TDD 实施计划。实施计划需要把旧 Runtime 证据补强和新 RAG/MCP 业务闭环拆成可独立验收的纵向增量，先写失败测试，再写最小实现，并在每个增量记录可运行证据。
