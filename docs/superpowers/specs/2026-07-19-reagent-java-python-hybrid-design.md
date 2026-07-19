# ReAgent Java + Python 混合面试工程版设计规格

- 日期：2026-07-19
- 状态：待用户书面复核
- 目标项目：/root/reagent
- 适用范围：Runtime Task 4 收口，以及全局 Task 5–14 的替代设计
- 前置规格：docs/superpowers/specs/2026-07-18-reagent-rag-mcp-design.md

## 1. 决策与治理关系

本规格采用“Java 可靠控制面 + 单个 Python 能力服务 + 一个事故处理闭环”的混合方案。

原规格继续约束已经完成或正在收口的 Runtime Task 1–4，包括：

- 消息日志与工具账本两个事实来源；
- TaskRunToken、lease epoch 与全运行期 fencing；
- Profile 和工具目录快照；
- ToolBatchCoordinator；
- AgentRunner 恢复、故障注入与最大恢复次数；
- SSE、Redis Streams 与 OpenTelemetry。

本规格替代原规格中尚未开始的全局 Task 5–14 的实现位置：

- RAG 的切片、Embedding、索引、检索和离线评测由 Python 服务负责；
- Fake Ops MCP Server 由 Python 服务负责；
- Java 保留 RAG Tool 适配器、MCP Client、审批、恢复和唯一控制面；
- 增加告警接入与去重；
- 每个实现 Task 同步交付面试材料。

若两份规格对 Task 5–14 有冲突，以本规格为准。Task 1–4 不因本次语言调整返工、重写或重置。

## 2. 产品定位

最终产品不是监控平台，也不是通用工作流引擎，而是一套可演示、可测试的智能运维事故调查 Agent：

1. 外部监控系统产生告警；
2. ReAgent 去重并创建持久任务；
3. Agent 从 Runbook 和历史事故中检索带引用的证据；
4. Agent 通过 MCP 查询指标和日志；
5. 证据充分时，Agent 请求人工批准创建工单；
6. 即使 Worker 在远端工单提交后、本地记账前崩溃，另一个 Worker 仍能恢复，且只产生一张工单。

首版使用合成告警、合成知识、合成指标、合成日志和 Fake 工单，不连接真实生产环境。

## 3. 成功标准

面试官应能在五分钟内看到：

- 告警从哪里进入系统；
- Agent 为什么检索某份 Runbook；
- 引用对应哪个真实 chunk；
- Agent 查询了什么指标和日志；
- 为什么创建工单需要审批；
- 批准或拒绝后系统如何继续；
- Worker 崩溃后为什么没有重复创建工单；
- 自动化测试、验收摘要和 trace 如何证明上述行为。

项目还应支持二十分钟技术深挖：

- 消息重放与工具账本；
- fencing 与分布式锁的区别；
- 审批和幂等的两轴模型；
- RAG 切片、索引版本和评测；
- MCP Schema 快照和漂移；
- Java/Python 服务边界；
- 远端副作用的危险崩溃窗口。

## 4. 总体架构

~~~text
Synthetic Alert Generator
          |
          | POST /api/incidents
          v
+--------------------------------------------------+
| Java ReAgent Runtime                             |
| Incident Intake / AgentRunner / StateStore       |
| Tool Catalog / Approval / Fencing / SSE / Trace  |
+-------------------------+------------------------+
                          |
             +------------+-------------+
             |                          |
             | internal RAG HTTP        | MCP Streamable HTTP
             v                          v
+--------------------------------------------------+
| Python agent-capabilities                        |
|                                                  |
| rag: chunk / embed / Redis search / evaluation   |
| fake_ops: metrics / logs / idempotent ticket     |
+----------------------+---------------------------+
                       |
               +-------+-------+
               |               |
             Redis 8          MySQL
             RAG index        fake_ops schema
~~~

部署上只有一个 Python 容器。代码内部的 rag 与 fake_ops 模块拥有独立接口和数据模型，不能相互调用业务逻辑；未来如果需要真实外部系统，可以独立拆分，但首版不增加第二个 Python 服务。

## 5. 唯一控制面

Java ReAgent Runtime 是唯一 Agent 控制面，负责：

- 创建和恢复任务；
- 调用 LLM 并驱动 ReAct 循环；
- 保存 message 上下文日志；
- 保存 tool_call 执行与幂等账本；
- 冻结 Profile、Prompt、工具 Schema 和知识索引版本；
- 判断工具是否可见、是否需要审批、能否安全重放；
- claim、lease、epoch fencing 和旧 Worker 停止；
- 进入和退出 WAITING_APPROVAL；
- 发布持久事件和 SSE；
- 形成主 OpenTelemetry trace。

Python 服务不得：

- 运行第二套 Agent 循环；
- 保存 Agent task、message 或 tool_call 状态；
- 决定是否审批；
- 修改 Java 任务状态；
- 重新解释 Java 的 IdempotencyClass 或 ApprovalPolicy；
- 接受用户动态指定的任意回调 URL 或 MCP URL。

Python 的失败只能表现为一个能力调用失败，不能成为平行任务状态机。

## 6. 告警来源与接入

### 6.1 首版监控对象

首个场景模拟电商 checkout 后端服务，关注：

- HTTP 请求量、5xx 错误率和 P95 延迟；
- 数据库连接池 max、active、idle、pending；
- 获取数据库连接的超时计数；
- checkout 服务日志中的连接池和数据库超时。

告警只报告表面现象：

~~~text
checkout 服务 5xx 错误率连续 5 分钟超过 10%
~~~

告警不直接携带“连接池耗尽”结论。Agent 必须通过 RAG、指标和日志取得证据。

### 6.2 统一告警契约

Java 暴露：

~~~http
POST /api/incidents
~~~

请求至少包含：

~~~json
{
  "source": "fake-alertmanager",
  "externalAlertId": "ALERT-CHECKOUT-001",
  "service": "checkout",
  "severity": "critical",
  "title": "Checkout error rate is above threshold",
  "summary": "5xx error rate exceeded 10% for five minutes",
  "startedAt": "2026-07-19T10:00:00Z",
  "labels": {
    "environment": "demo",
    "region": "cn-east"
  }
}
~~~

字段和 payload 必须有长度上限。source 只能来自受信配置。

### 6.3 去重与持久化

新增 incident_intake 表，仅承担入口审计与去重，不承担 Agent 状态：

- id；
- source；
- external_alert_id；
- bounded_payload_json；
- task_id；
- created_at；
- UNIQUE(source, external_alert_id)；
- UNIQUE(task_id)。

Incident 创建与 task 创建处于同一 Java 事务。并发收到相同告警时只创建一个 task；重试请求返回已存在的 taskId。

Python Fake Alert Generator 通过 demo 脚本或控制台按钮发送该 Webhook。生产替代方案可以是 Alertmanager、Grafana Alerting 或企业告警平台适配器，但不进入首版。

## 7. Python 服务

### 7.1 工程结构

~~~text
services/agent-capabilities/
├── pyproject.toml
├── uv.lock
├── alembic.ini
├── migrations/
├── src/agent_capabilities/
│   ├── app.py
│   ├── config.py
│   ├── observability.py
│   ├── rag/
│   │   ├── models.py
│   │   ├── loader.py
│   │   ├── chunker.py
│   │   ├── embedding.py
│   │   ├── index.py
│   │   ├── initializer.py
│   │   ├── service.py
│   │   └── api.py
│   └── fake_ops/
│       ├── alerts.py
│       ├── mcp_server.py
│       ├── metrics.py
│       ├── logs.py
│       ├── tickets.py
│       ├── faults.py
│       └── acceptance.py
└── tests/
~~~

使用 Python 3.12 和 uv 锁定依赖。生产镜像只安装锁文件中的运行依赖；开发工具留在 dependency group。

### 7.2 ASGI 与 MCP

Python 根应用使用 ASGI，提供：

- /internal/rag/search；
- /internal/readiness；
- /internal/acceptance；
- /mcp。

MCP 服务使用官方 MCP Python SDK 稳定版 mcp==1.28.0。当前 2.0 仍是预发布且官方明确提示可能逐版本破坏，不在本项目中采用。依赖升级必须显式修改 pyproject.toml 与锁文件并重跑跨语言协议测试。

MCP Server 使用 FastMCP 的 Streamable HTTP，配置 stateless_http=true、json_response=true 和 streamable_http_path="/"，再挂载到根 ASGI 应用的 /mcp，因此 Java Client 的最终连接地址就是 /mcp，而不是 /mcp/mcp。ASGI lifespan 必须启动和关闭 FastMCP session manager。业务持久性来自 MySQL 工单表，而不是 MCP session。

### 7.3 Python 数据所有权

Python 独占：

- Redis 中 rag:incident:* 键空间；
- MySQL 的 fake_ops schema；
- Alembic 迁移；
- demo_ticket 表；
- Python 服务 readiness 和 acceptance 状态。

Java 不直接读写 demo_ticket 表。端到端测试通过只读 acceptance API 核对调用次数、唯一工单数和稳定 ticketId，避免共享数据库所有权。

## 8. RAG 设计

### 8.1 首版语料

仓库内置英文 Markdown 合成语料：

- checkout 数据库连接池 Runbook；
- checkout 服务架构说明；
- 一次相似历史事故；
- search latency 和 Kafka lag 等干扰文档。

不支持上传、PDF、OCR、网页抓取或在线编辑。

### 8.2 确定性切片

切片规则：

1. 按 Markdown 标题和段落优先；
2. 超过上限的段落按句子边界继续拆分；
3. 仅保留小且固定的重叠；
4. 空白归一化；
5. chunk_id 由 document_id、section 和内容 checksum 稳定生成；
6. 相同数据重复初始化不产生重复 chunk。

每个 chunk 至少保存：

- chunk_id；
- document_id；
- title；
- section；
- source；
- content；
- checksum；
- embedding_model；
- index_version。

不做 LLM 语义切片。

### 8.3 Embedding

Python 使用本地 sentence-transformers/all-MiniLM-L6-v2 CPU 模型，输出 384 维向量，并在写入与查询前执行相同的向量归一化。模型由 EmbeddingPort 隔离，模型标识、文件校验和、向量维度进入索引版本。具体 Python 依赖由 uv.lock 精确固定；首版不增加量化导出或第二种模型后端。

测试分两层：

- 普通单元测试使用固定 FakeEmbedding；
- 专项质量测试使用真实本地模型。

不调用外部付费 Embedding API，不训练或微调模型，不在首版增加 reranker。

### 8.4 Redis 8 索引

Python 使用 Redis 8 HNSW 和余弦距离：

- chunk：rag:incident:chunk:{chunk_id}；
- versioned index：idx:rag:incident:{index_version}；
- active version：rag:incident:active；
- init lock：rag:incident:init:{index_version}。

初始化采用两阶段切换：

1. 计算 dataset、chunker 和 model manifest；
2. 如果相同版本已就绪则跳过；
3. 构建新版本；
4. 验证 chunk 数和查询 smoke；
5. 原子切换 active version。

失败版本不能覆盖上一个可用索引。

### 8.5 Java/Python RAG 契约

Java 的 KnowledgeSearchTool 调用：

~~~http
POST /internal/rag/search
~~~

请求：

~~~json
{
  "contractVersion": 1,
  "knowledgeBaseId": "incident-ops",
  "indexVersion": "v1",
  "query": "checkout connection pool exhaustion",
  "topK": 3
}
~~~

响应：

~~~json
{
  "contractVersion": 1,
  "indexVersion": "v1",
  "hits": [
    {
      "chunkId": "checkout-db-pool#mitigation",
      "title": "Checkout DB Pool Runbook",
      "section": "Mitigation",
      "source": "knowledge/incident-ops/runbooks/checkout-db-pool.md",
      "score": 0.91,
      "excerpt": "..."
    }
  ]
}
~~~

Java 必须校验：

- contractVersion；
- 响应 indexVersion 与任务快照一致；
- hit 数、excerpt 和总响应大小上限；
- chunkId、source 和 score 格式；
- 未知字段的兼容策略；
- 超时和非 2xx；
- Python 不得通过响应注入工具权限或审批策略。

RAG 是 READ_ONLY 工具。网络超时可以在同一次调用中进行一次短退避重试；失败后返回结构化 tool error，由 Agent 说明证据不足。

## 9. MCP Fake Ops

### 9.1 工具

Python /mcp 暴露三个工具：

| 工具 | 行为 | 重放等级 | 审批策略 |
|---|---|---|---|
| query_metrics | 返回预置时间序列和连接池指标 | READ_ONLY | NONE |
| search_logs | 返回预置日志和时间窗 | READ_ONLY | NONE |
| create_ticket | 创建合成 P1 工单 | IDEMPOTENT | REQUIRE_APPROVAL |

重放等级和审批策略只由 Java 受信配置决定。Python Tool annotation 只能作为描述，不能提升权限。

### 9.2 工单幂等

Java 从暴露给 LLM 的 create_ticket Schema 中移除 idempotency_key。实际调用前由 Java 注入：

~~~text
idempotency_key = tool_call_id
~~~

Python demo_ticket 表对 idempotency_key 建唯一约束。事务语义为：

1. 按 idempotency_key 插入；
2. 唯一冲突时读取已有记录；
3. 相同键始终返回同一 ticket_id；
4. 不同参数重用同一键时 fail-closed，并记录契约冲突。

Python 提供受限的测试/演示故障门，能在“工单事务已提交、MCP 响应尚未返回”处阻塞或模拟连接中断。该入口只在 test/demo-chaos Profile 开启。

### 9.3 Java MCP 适配

Java 继续使用受信配置中的官方 MCP Client，通过 Streamable HTTP：

1. initialize；
2. listTools；
3. 校验工具名和 JSON Schema；
4. 映射本地重放/审批/超时策略；
5. 冻结到 TaskToolCatalog；
6. callTool。

旧任务恢复时，当前发现的 Schema hash 必须与任务快照一致。漂移时返回 schema-drift 错误，不能用新 Schema 静默执行旧调用。

## 10. 审批与可靠性

原规格的审批和可靠性语义保持：

- IdempotencyClass 与 ApprovalPolicy 是两条独立轴；
- 任何需要审批的调用使整个 assistant 工具批次进入屏障；
- assistant 消息和全部 PENDING 账本先落库；
- WAITING_APPROVAL 跨进程和浏览器重启保持；
- 等待期间释放 lease；
- 同决定重复提交幂等，相反决定返回 409；
- 拒绝写 REJECTED tool result，不调用 Python，不把 Task 标为 FAILED；
- 批准后任务转 RUNNING、owner 置空并触发可恢复调度；
- 所有运行期 Java 持久写入携带 TaskRunToken；
- 旧 epoch 写入抛 FencedExecutionException 且不能覆盖新 Worker；
- create_ticket 在调用前写 IN_PROGRESS；
- 危险窗口恢复必须使用同一 tool_call_id；
- 不支持下游幂等的副作用工具仍进入 IN_DOUBT，不盲目重放。

## 11. 端到端流程

### 11.1 Happy Path

1. Fake Alert Generator 发送 checkout 高错误率告警；
2. Java 去重并创建 incident-ops Task；
3. Worker A claim 并冻结 Profile、RAG index version 和 MCP Schema；
4. Agent 调用 search_knowledge；
5. Python 返回 Runbook 和历史事故引用；
6. Agent 调用 query_metrics 与 search_logs；
7. Python 返回 14.2% 错误率、连接池耗尽指标和超时日志；
8. Agent 形成 create_ticket 调用；
9. Java 持久化工具批次与审批，Task 进入 WAITING_APPROVAL；
10. 用户批准；
11. Worker 恢复，Java 写 IN_PROGRESS 并注入 tool_call_id；
12. Python 创建并返回 OPS-1042；
13. Java 写 DONE、tool result 和最终消息；
14. Task COMPLETED，控制台展示诊断、引用、审批和 ticketId。

### 11.2 Reject Path

用户拒绝后：

- create_ticket 调用数保持 0；
- 账本进入 REJECTED；
- Agent 说明未创建工单并给出人工建议；
- Task 正常完成。

### 11.3 Dangerous Crash Path

1. Python 已提交 OPS-1042；
2. 故障门阻止响应或令连接断开；
3. Worker A 消失，Java 账本停在 IN_PROGRESS；
4. Worker B 以更高 epoch claim；
5. 旧 Worker A 的后续写入被 fence；
6. Worker B 使用同一 tool_call_id 恢复调用；
7. Python 唯一约束返回已有 OPS-1042；
8. Java 完成本地记账；
9. acceptance API 证明 attempt count 可大于 1，但 unique ticket count 等于 1。

### 11.4 五个确定性故障点

生产配置中的 FaultInjector 默认 no-op；测试和 demo-chaos 才能在以下位置暂停或终止 Worker：

1. assistant/tool_call 已提交，审批请求尚未提交；
2. 审批决定已提交，恢复线程尚未启动；
3. 工具账本已经 IN_PROGRESS，Python 尚未收到远端调用；
4. Python 工单事务已提交，Java tool result 尚未提交；
5. Java tool result 已提交，最终 LLM 答案尚未生成。

每个故障点都必须通过 latch、显式事件或可控 Clock 驱动，不允许依赖随机 sleep 猜测时序。

## 12. 错误处理

| 情况 | 行为 |
|---|---|
| 重复告警 | 返回原 taskId，不创建第二个任务 |
| Python 服务未 ready | incident-ops readiness 为 DOWN，不宣布完整栈可用 |
| RAG 无命中 | 返回空 hits；Agent 明确证据不足 |
| RAG 超时 | READ_ONLY 调用最多短重试一次，随后结构化失败 |
| 索引版本不匹配 | fail-closed，不用活动新版本替代旧任务版本 |
| MCP 初始化失败 | 必需 Profile 不可用 |
| MCP read tool 超时 | 结构化 tool error，由 Agent 解释或停止 |
| create_ticket 超时 | 保持 IN_PROGRESS，恢复时使用同一幂等键 |
| MCP Schema 漂移 | 旧任务停止该调用并报告 schema-drift |
| 用户拒绝 | 不调用远端工具，任务继续 |
| 冲突审批 | 409，不覆盖既有事实 |
| Java Worker 崩溃 | 另一 Worker claim 并从日志/账本恢复 |
| Python 重启 | Redis 索引和 MySQL 工单保持；readiness 恢复后继续 |
| 旧 Worker 写入 | FencedExecution，旧 Worker停止且不写 FAILED |
| 不支持幂等的副作用 | IN_DOUBT，不自动重放 |

## 13. 可观测性

Java 将 W3C trace context 传播到 RAG HTTP 和 MCP 调用。Python 增加：

- rag.embed；
- rag.index；
- rag.search；
- mcp.query_metrics；
- mcp.search_logs；
- mcp.create_ticket；
- ticket.insert_or_read。

适用 span 记录：

- taskId、profile、worker 和 lease epoch；
- Python service/version；
- knowledge base、index version、topK、hit count 和 chunk IDs；
- MCP tool、tool_call_id；
- idempotency replay/deduplicated；
- alert source 和 externalAlertId。

不得记录：

- API Key、内部 token；
- 完整告警 payload；
- 完整文档正文；
- 未截断日志；
- 敏感工单参数；
- 完整模型输出。

## 14. 安全边界

- Python 服务仅暴露在 Compose 内部网络；宿主只按演示需要映射受限端口；
- Java 不能从 Task 请求接收动态 Python/MCP URL；
- source、Profile、MCP Server 和知识库均来自受信配置；
- 所有跨服务请求有连接、响应、总大小和并发上限；
- create_ticket 的 idempotency_key 由 Java 注入，LLM 和用户不能覆盖；
- Python test/chaos/acceptance 入口只在明确 Profile 开启；
- Fake Ops 没有真实外部副作用；
- 普通 down 保留数据，reset 必须显式执行；
- 首版不声称具备生产认证、租户隔离或真实运维写权限。

## 15. 测试与自动化证据

### 15.1 Python Unit

- 告警 fixture；
- Markdown 切片和稳定 chunk_id；
- FakeEmbedding 排序；
- 响应大小和字段上限；
- 索引版本 manifest；
- MCP 工具参数验证；
- 工单 insert-or-read；
- 相同键不同参数 fail-closed；
- 故障门默认关闭。

### 15.2 Python Integration

- Redis 8 HNSW 创建、检索、版本切换和失败回滚；
- 真实本地 MiniLM 质量评测；
- Alembic fresh/upgrade；
- MySQL 并发幂等工单；
- MCP initialize/list/call over Streamable HTTP；
- committed-before-response 故障门；
- readiness 和 acceptance。

### 15.3 Java Unit/Integration

- Incident 并发去重；
- RAG contract、超时、非法响应和 index mismatch；
- MCP 名称、Schema、策略和漂移；
- 审批批准、拒绝、重复、冲突和取消；
- 整批屏障；
- 旧 epoch 对消息、账本、结果、事件和终态的拒绝；
- Python 失败时不破坏 Java 任务事实。

### 15.4 Full Scenario

使用 Scripted LLM、真实 MySQL、Redis 8 和真实 Python 服务：

- alert → RAG → metrics/logs → approval → ticket → final；
- 拒绝分支且 create_ticket calls=0；
- WAITING_APPROVAL 重启；
- 批准提交后、恢复线程前崩溃；
- 工单提交后、本地记账前崩溃；
- 双 Worker 接管和旧 epoch 拒写；
- 最终 unique ticket count=1；
- RAG 无命中、Python 不可用、Schema 漂移和恢复上限。

不使用随机 sleep 证明正确性；使用 latch、可控 Clock 和确定性故障点。

### 15.5 CI

CI 至少分为：

1. Java fast unit：./mvnw -B test；
2. Python fast unit/lint/type：uv run pytest、ruff、pyright；
3. Docker integration：Java Failsafe + Python Redis/MySQL/MCP；
4. Compose smoke/failover。

核心集成测试不得在 CI 因 Docker 缺失而静默跳过。上传 Java 报告、Python JUnit XML、RAG 质量报告和全栈 acceptance summary。

## 16. 演示控制台

Java Spring Boot 提供无 Node 构建的静态页面，显示：

- Runtime、Python、RAG、MCP、MySQL、Redis readiness；
- 触发 checkout 模拟告警按钮；
- alert source、externalAlertId、service 和 severity；
- Task 状态和 Worker/epoch；
- RAG 引用卡片；
- metrics 和 logs 工具卡；
- 创建工单风险与审批卡；
- Worker 崩溃、接管和 deduplicated 事件；
- 最终诊断和 ticketId；
- Jaeger 与验收报告入口。

外部文本必须使用 textContent 或逐字段 DOM 构造，不能直接写 innerHTML。

脚本：

- demo-up.sh：构建、启动、初始化并等待完整 readiness；
- demo-alert.sh：发送确定性告警；
- demo-smoke.sh：自动批准并验证 happy path；
- demo-reject.sh：验证拒绝路径；
- demo-failover.sh：自动制造危险崩溃窗口；
- demo-down.sh：停止并保留数据；
- demo-reset.sh：显式重置。

## 17. 面试材料双轨交付

每个 Task 的 Definition of Done 增加：

- 代码和测试；
- RED/GREEN 与集成证据；
- 一张主题面试卡；
- 30 秒回答；
- 2 分钟深入回答；
- 至少 5 个追问；
- 替代方案和边界；
- 可重复演示步骤。

建议目录：

~~~text
docs/interview/
├── 00-project-pitch.md
├── 01-architecture.md
├── 02-runtime.md
├── 03-rag.md
├── 04-mcp-approval.md
├── 05-testing.md
├── 06-tradeoffs.md
├── question-bank.md
└── demo-script.md
~~~

面试材料只能陈述已经通过测试或演示证明的行为。尚未完成的能力只能列为 roadmap。

## 18. Task 重排

| 全局 Task | 内容 | 主要语言 |
|---|---|---|
| 1–4 | 原 Runtime 证据、fencing、Profile、批次和恢复 | Java |
| 5 | 告警接入、跨语言契约、Python 服务骨架 | Java + Python |
| 6 | Python 切片、MiniLM、Redis 8 RAG 和评测 | Python |
| 7 | Java RAG Gateway、Tool、Profile 和事件 | Java |
| 8 | Python Fake Ops MCP、工单幂等和故障门 | Python |
| 9 | Java MCP Client、Schema 快照与工具适配 | Java |
| 10 | Java 持久审批、API 和取消语义 | Java |
| 11 | 整批屏障、事故闭环和崩溃矩阵 | Java + Python |
| 12 | Readiness、Trace、验收报告 | Java + Python |
| 13 | 薄控制台和浏览器流程 | Java + JS |
| 14 | Compose、CI、脚本、README和面试材料收口 | 全栈 |

Task 4 必须先完成 Step 4.7、全量验证、单一提交和独立审查。Task 5 之前不得修改或覆盖现有 21 个 Task 4 工作路径。

## 19. 迁移所有权

Java Flyway：

| Migration | Owner |
|---|---|
| V1 baseline runtime | 已完成 Runtime Task 1 |
| V2 profiles and batches | 已完成 Runtime Task 3 |
| V3 incident intake | 新 Task 5 |
| V4 durable approval | 新 Task 10 |

Python Alembic：

| Revision | Owner |
|---|---|
| 0001 demo_ticket | 新 Task 8 |

两个服务不能跨所有权修改对方表。Redis Streams 由 Java 管理，rag:incident:* 由 Python 管理。

## 20. 明确非目标

- 第二套 Python Agent 或 Agent 框架；
- 多 Agent；
- Celery 或第二套任务队列；
- 动态用户知识库；
- PDF、OCR、网页抓取；
- query rewrite、reranker、复杂混合检索；
- 真实 Prometheus、Loki、Jira 写操作；
- 自动重启、扩容或修改生产配置；
- React/Vue前端；
- 多租户、SSO、RBAC；
- Kubernetes和通用工作流平台；
- 宣称替代 Temporal；
- 宣称任意副作用 exactly-once。

## 21. 最终验收

功能：

- 相同告警只创建一个任务；
- incident-ops 完成预置事故全链路；
- 最终答案包含真实 RAG 引用、指标、日志和唯一 ticketId；
- 拒绝时不调用 create_ticket；
- 等待审批期间重启后仍可决定；
- 旧任务使用冻结的 RAG index 和 MCP Schema。

可靠性：

- 双 Worker 只有一个持有有效 epoch；
- 旧 epoch 的全部运行期写入被拒绝；
- 五个关键崩溃窗口有确定性测试；
- committed-before-response 恢复后 unique ticket count=1；
- Python 重启不丢失已提交工单和可用索引；
- 不支持幂等的副作用不自动重放。

质量：

- 固定评测集关键查询预期文档进入 top 3；
- MRR 达到 0.80；
- 所有 hit 能解析到真实 chunk 和 source；
- 跨语言契约和 MCP 协议测试通过；
- Java/Python fast 与 integration 门禁通过；
- Compose smoke 和 failover 通过。

演示：

- 一条命令启动完整栈；
- 一个按钮或脚本触发告警；
- 页面展示取证、审批、崩溃、接管和唯一工单；
- README 提供 30 秒介绍、5 分钟演示和深挖入口；
- 普通演示不随机故障，故障演示必须显式开启。

## 22. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 两种语言增加复杂度 | 只有一个 Python 服务；契约版本、锁文件和端到端测试 |
| Python 变成第二控制面 | 禁止保存 Agent 状态；Java 是唯一任务真相源 |
| MCP Python 2.0 预发布变化 | 精确固定稳定版 1.28.0；不自动升级 |
| Java/Python 协议不兼容 | 真实 Streamable HTTP initialize/list/call 作为合并门禁 |
| MiniLM 对中文不佳 | 首版英文语料和查询；真实离线阈值 |
| Python 镜像过大 | 单一MiniLM CPU模型、Docker分层缓存；首版不引入第二个推理框架 |
| Redis 8 升级影响 Streams | Java Redis Streams 与 Python向量检索同时回归 |
| 告警重复创建任务 | 数据库唯一约束和事务返回已有taskId |
| 工单远端成功、本地未知 | tool_call_id幂等键、唯一约束和故障矩阵 |
| 项目被认为造轮子 | 明确受限Runtime定位、替代方案和非目标 |
| 面试材料脱离代码 | 每Task同提交更新，只写已验证事实 |

## 23. 官方技术依据

- MCP Python SDK 官方仓库：https://github.com/modelcontextprotocol/python-sdk
- MCP Python SDK 稳定 v1 分支：https://github.com/modelcontextprotocol/python-sdk/tree/v1.x
- MCP Python SDK v1 服务端文档：https://github.com/modelcontextprotocol/python-sdk/blob/v1.x/docs/server.md
- MCP Java SDK 官方仓库：https://github.com/modelcontextprotocol/java-sdk
- Redis Search 与向量检索：https://redis.io/docs/latest/develop/ai/search-and-query/

## 24. 后续流程

1. 用户复核并批准本规格；
2. 在现有 worktree 原地完成 Runtime Task 4 Step 4.7；
3. 更新总路线图；
4. 为 Task 5–14 生成新的逐测试 TDD 实施计划；
5. 从 Task 5 起采用“实现 + 面试材料”双轨交付；
6. 未经用户复核，不开始 Python 服务或 Task 5。
