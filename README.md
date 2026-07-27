# ReAgent

ReAgent 是一个可恢复的事故调查 Agent：接收受信告警，在冻结知识库中检索
证据，查询合成 metrics/logs，经人工审批后创建幂等 Fake Ops 工单，并在
worker 崩溃后从持久化断点继续。

它不是监控平台、通用工作流引擎或生产工单系统。仓库中的告警、指标、日志和
工单全部是可重复的本地演示数据，不会对真实生产系统执行操作。

## 一分钟看懂

```text
Browser / alert source
          |
          v
Java worker A  <---- Redis Streams ---->  Java worker B
  API + UI          replay / live             failover
  durable runtime -------- MySQL(reagent)
          |
          +---- trusted HTTP ---- Python agent-capabilities
                                  RAG -> Redis index
                                  MCP -> synthetic metrics/logs
                                  ticket -> MySQL(fake_ops)
          |
          +---- OTLP ---- Jaeger
```

Java 负责 durable task、审批、幂等账本、租约/fencing、SSE、UI 和验收投影。
Python 负责 MiniLM RAG、官方 MCP Streamable HTTP、合成运维信号、Fake Ops
工单幂等性，以及只在 chaos 演示中启用的提交后故障门。

两个服务共享基础设施，但不共享数据库账号：`reagent_app` 只能访问
`reagent` schema，`fake_ops_app` 只能访问 `fake_ops` schema。

## 前置条件

- Linux 或 WSL2；
- Docker Engine 与 `docker compose` v2；
- Bash、curl、Python 3；
- 充足磁盘与首次下载时间。

Python 依赖严格来自 `uv.lock`。当前 Linux 锁包含 PyTorch CUDA 运行库，
所以首次镜像构建较大；这是已知的可复现成本，不代表演示需要 GPU。容器首次
启动还会下载 `sentence-transformers/all-MiniLM-L6-v2`，随后保存在
`model-cache` named volume 中。建议首次运行预留至少 20 GB 可用空间和稳定
网络。

## 启动

普通模式不会启用 scripted LLM、acceptance API 或 chaos gate：

```bash
# 可选：普通模式实际调查告警时提供 OpenAI-compatible key
export DEEPSEEK_API_KEY=your-key

bash scripts/demo-up.sh
```

打开：

- 事故响应 UI/API：<http://127.0.0.1:8080>
- Jaeger UI：<http://127.0.0.1:16686>

只有这两个端口映射到宿主机。MySQL、Redis、Python capability API 和 OTLP
只在 Compose 网络内可见。

触发一个新的合成 checkout 告警：

```bash
bash scripts/demo-alert.sh
```

脚本每次生成严格格式的 run-scoped `externalAlertId`，也允许传入一个符合
同一格式的 ID。重复 ID 会被 intake 去重，因此脚本明确要求
`deduplicated=false`。

## 三条确定性演示

这些场景不需要真实模型 key。它们显式启用受 profile 保护的 scripted LLM
和 task-scoped acceptance，并在断言前真实验证两个 MySQL 账号的同 schema
访问成功、cross-schema 访问失败。

### 审批并创建唯一工单

```bash
bash scripts/demo-smoke.sh
```

场景检索 checkout connection-pool runbook，查询合成 metrics/logs，等待
`create_ticket` 审批，批准后要求任务 `COMPLETED`、create attempt 为一、
unique ticket 为一，并打印有界证据摘要。

### 拒绝远端写入

```bash
bash scripts/demo-reject.sh
```

同一调查链在审批边界被拒绝。任务仍以可解释的最终答案完成，但 Fake Ops 的
create attempt 与 unique ticket 都必须为零。

### 提交后丢响应并由 Worker B 接管

```bash
bash scripts/demo-failover.sh
```

脚本预先按 run-scoped alert 推导本次 `create_ticket` 幂等 key，在 Python
工单事务已提交、HTTP 响应尚未返回时阻塞，只通过
`docker compose ps -q reagent-worker-a` 得到的容器 ID 强杀 Worker A。
释放故障门后，Worker B 以更高 lease epoch 接管并重放幂等调用。验收要求
create attempt 至少为二、unique ticket 仍为一。

## 停止与重置

```bash
# 停容器和项目网络，保留 MySQL/Redis/model/workspace named volumes
bash scripts/demo-down.sh

# 显式删除固定 reagent-demo 项目的容器、网络和 named volumes
bash scripts/demo-reset.sh
```

只有 `demo-reset.sh` 执行项目级 `down -v`。其他脚本不运行
`docker system prune`，也不做宽泛目录删除。

## 运行时语义

- **Durable context**：task/message/tool/event 写入 Java schema，恢复时从
  持久化消息和工具账本重建上下文。
- **人工审批**：`create_ticket` 在远端调用前进入 durable `PENDING`
  审批；拒绝会写入有界 synthetic tool result，不执行远端动作。
- **幂等远端写入**：Java 把 `tool_call_id` 下推为 Python
  `idempotency_key`。同 key、同请求只对应一个 Fake Ops 工单；同 key、
  不同请求 fail closed。
- **租约与 fencing**：worker claim 增加 epoch；旧 owner 续租或终态写入
  会被 epoch/owner 条件挡住，避免脑裂双写。
- **可恢复 SSE**：Redis Streams 同时承载跨 worker replay 与 live
  cursor；浏览器以 REST 为权威状态，只保存 task ID 和 durable cursor。
- **可观测性**：Java 和 Python 传播 W3C trace context，并只记录 bounded
  IDs、计数和阶段，不记录完整告警、工具参数、凭据或模型 key。

“Exactly once” 不是跨 MySQL 与任意外部系统的分布式事务保证。ReAgent 的
安全边界是：副作用工具必须诚实声明幂等等级；未知/非幂等调用不会盲目重放；
可恢复远端写入依赖下游按 idempotency key 去重。真实生产集成仍需下游提供
同等级契约或采用人工对账。

## UI

根路径由 Spring Boot 直接提供无构建步骤的事故响应控制台。界面是线性的
incident flight recorder：readiness、告警摘要、citation、tool 证据、审批
边界、worker epoch/fencing 和最终工单沿同一时间脊呈现。所有外部文本通过
`textContent` 渲染；不使用 CDN、远程字体、`innerHTML`、`eval` 或 payload
localStorage。

## 验证

快速门：

```bash
./mvnw -B test

cd services/agent-capabilities
uv sync --locked --all-groups
uv run --locked ruff check .
uv run --locked pyright
uv run --locked pytest -m "not integration and not quality" -q
```

完整本地门：

```bash
bash scripts/verify-all.sh
```

`verify-all.sh` 运行 Java fast/integration、Python
lint/type/fast/integration/quality，以及 Compose happy/reject/failover。
所有 HTTP 等待、Compose 命令、Maven、uv 和 pytest 都有时间上限；失败时
输出项目状态和有限日志尾。它先用锁定 Python 环境准备固定 MiniLM host
cache；显式 reset 后再以非 root one-off 容器把同一缓存种入新的
`model-cache` volume，因此首次准备完成后，Compose 验收不依赖重复外网
下载。

生成的主要证据：

- Java JUnit：`target/surefire-reports/`
- Java integration：`target/failsafe-reports/`
- 事故验收 JSON/Markdown：`target/acceptance/`
- RAG quality：`services/agent-capabilities/build/reports/rag-quality.json`
- Compose/Jaeger CI 证据：`build/compose-artifacts/`

产物字段与复验命令见
[docs/acceptance/README.md](docs/acceptance/README.md)。

CI 分为四个非静默跳过的 job：

1. `java-fast`
2. `python-fast`
3. `integration-quality`
4. `compose-smoke-failover`

每个 job 在成功或失败时上传对应测试报告或运行证据。缺少 Docker、
Compose、锁定依赖、模型缓存构建能力或必须的服务会使 job 失败。

## 配置与安全边界

- `.env.example` 只含醒目的本地演示凭据；不要在其他环境复用。
- `.env`、私钥、证书、local application config、缓存与报告均被忽略。
- 正常 profile 不暴露 acceptance/chaos 路由，也不选择 scripted LLM。
- incident source allowlist 只接受 `fake-alertmanager`。
- HTTP body、RAG/MCP response、SSE cursor、ID、日志和 acceptance 投影均有
  大小或格式限制。
- Compose 演示显式使用 subprocess sandbox；它提供进程级期限与工作区边界，
  但不宣称网络隔离。选择 Docker sandbox 时才通过 `--network=none` 隔离
  命令网络。生产级多租户隔离、Kubernetes 部署、真实 Alertmanager/日志
  平台/工单系统不在本仓库范围内。

## 代码入口

```text
src/main/java/com/reagent/
  api/          incident/task/approval/readiness/acceptance HTTP
  core/         durable Agent loop, recovery, lease and fencing
  persist/      MySQL task/message/tool/event ledger
  rag/          trusted Python RAG client
  mcp/          official MCP client and tool adapters
  stream/       in-process and Redis Streams transport
  demo/         profile-gated deterministic incident LLM
  obs/          OpenTelemetry tracing

services/agent-capabilities/
  src/agent_capabilities/rag/       MiniLM indexing and retrieval
  src/agent_capabilities/fake_ops/  synthetic signals, MCP and ticket ledger
```
