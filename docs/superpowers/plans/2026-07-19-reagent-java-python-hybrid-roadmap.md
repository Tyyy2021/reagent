# ReAgent Java + Python Hybrid Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留 Java 可恢复 Agent Runtime 的前提下，增加一个 Python 能力服务，完成“告警接入 → RAG 取证 → MCP 指标/日志 → 持久审批 → 幂等工单 → 崩溃接管”的可重复事故闭环。

**Architecture:** Java 是唯一控制面和任务真相源，拥有 task/message/tool_call/approval、lease epoch、fencing、SSE 与主 trace。单个 Python `agent-capabilities` 服务只拥有 RAG 与 Fake Ops MCP 能力，RAG 数据位于 Redis 8，Fake 工单位于 MySQL `fake_ops` schema，跨语言边界分别是 versioned JSON/HTTP 和 MCP Streamable HTTP。

**Tech Stack:** Java 21、Spring Boot 3.3.5、Maven、MCP Java SDK 2.0.0（`mcp-core` + `mcp-json-jackson2`）、Python 3.12、uv、MCP Python SDK 1.28.1、Starlette、Pydantic 2、sentence-transformers `all-MiniLM-L6-v2`、Redis 8 HNSW、MySQL 8、Alembic、OpenTelemetry、Docker Compose。

## Global Constraints

- `docs/superpowers/specs/2026-07-19-reagent-java-python-hybrid-design.md` 是 Task 5–14 的唯一权威规格；Runtime Task 1–4 仍受 `2026-07-18-reagent-rag-mcp-design.md` 约束。
- Task 4 Step 4.7、全量验证、单一提交和审查未完成前，不得开始 Task 5，也不得修改当前 Task 4 的 21 个工作路径。
- Java 是唯一 Agent 控制面；Python 不运行 Agent loop，不保存 task/message/tool_call，不决定审批或重放策略。
- 只部署一个 Python 容器；`rag` 与 `fake_ops` 模块禁止互相调用业务服务。
- Python 固定 `requires-python = ">=3.12,<3.13"`，精确固定 `mcp==1.28.1`，并提交 `uv.lock`；MCP Java SDK 固定 `2.0.0`。
- Spring Boot 3 使用 Jackson 2，因此 Java 依赖 `mcp-core` 与 `mcp-json-jackson2`，禁止引入默认 Jackson 3 的 `mcp` 聚合包。
- Java Flyway 只修改 `reagent` schema；Python Alembic 只修改 `fake_ops` schema。Java 不直接查询 `demo_ticket`。
- Compose 为 Java 使用仅授权 `reagent.*` 的 `reagent_app`，为 Python 使用仅授权 `fake_ops.*` 的 `fake_ops_app`；禁止共享跨 schema 应用账号。
- Redis Streams 继续由 Java 管理；Python 只能读写 `rag:incident:*` 和 `idx:rag:incident:*`。
- 所有跨服务 URL、告警 source、Profile、MCP server ID 和知识库 ID 均来自受信配置，不从 Task 或 LLM 参数接收动态 URL。
- `idempotency_key` 不出现在 LLM 可见 Schema；Java 在调用 `create_ticket` 前强制注入 `tool_call_id`，Python 对同一键不同参数 fail-closed。
- Python acceptance 的工单统计可按 Java 持久化的 `tool_call_id` 查询；Task 级验收和重复运行脚本不得用全局历史计数判定本次成功。
- 所有跨服务请求设置连接、调用、响应体、字段长度和并发上限；外部错误转成结构化结果，不污染 Java 持久事实。
- 每个行为先写失败测试并看到预期 RED，再写最小实现并看到 GREEN；fast test 不依赖 Docker，integration/quality/compose test 不得因基础设施缺失而静默跳过。
- 每个全局 Task 独立提交。提交前执行 focused test、相关回归、`git diff --check` 和 `git status --short`，只暂存该 Task 文件。
- 本轮不创建 `docs/interview`，不逐 Task 产出面试卡、口述稿或追问题库；只交付代码、测试、验收证据和必要技术文档。

---

## Execution Worktree and Task 4 Gate

执行位置是现有 worktree：

```bash
cd /root/reagent/.worktrees/reagent-rag-mcp
git status --short
```

当前 21 个 Task 4 路径属于未完成实现，必须原地保留。先执行原计划的 Step 4.7：

```bash
./mvnw -B clean test
./mvnw -B -Pci verify
rg -n "private void executeTools|long myEpoch|registry\.toOpenAiSpec" src/main/java/com/reagent
git diff --check
```

预期：两条 Maven 命令成功；搜索无遗留旧实现；diff check 成功。然后只提交 Task 4：

```bash
git add src/main/java src/test/java
git commit -m "refactor: extract recoverable tool batch coordinator"
```

Task 4 审查通过且 worktree 干净后，将 main 上批准的规格和新计划合入功能分支：

```bash
git merge main
```

若 merge 出现冲突，只解决 `docs/superpowers` 的旧/新计划指向；不得用 reset/checkout 覆盖 Task 4 提交。

---

## Plan Suite and Required Order

1. [`2026-07-19-reagent-python-rag.md`](./2026-07-19-reagent-python-rag.md)
   - Task 5：告警去重、初始无工具 `incident-ops` Profile、提交后单次调度、共享契约、Python ASGI 骨架。
   - Task 6：确定性切片、MiniLM、Redis 8 HNSW、索引切换与离线评测。
   - Task 7：Java RAG Gateway、冻结 index version、`search_knowledge` 与边界验证。
2. [`2026-07-19-reagent-mcp-incident.md`](./2026-07-19-reagent-mcp-incident.md)
   - Task 8：Python Fake Ops MCP、Alembic 工单幂等和 committed-before-response 故障门。
   - Task 9：Java 官方 MCP Client、Schema 快照、适配器和未知远端结果处理。
   - Task 10：持久审批、整批屏障、REST 决策和取消语义。
   - Task 11：完整事故闭环与五个确定性崩溃窗口。
3. [`2026-07-19-reagent-demo-delivery.md`](./2026-07-19-reagent-demo-delivery.md)
   - Task 12：readiness、跨服务 trace 和验收报告。
   - Task 13：无 Node 的静态事故控制台与浏览器流程。
   - Task 14：镜像、Compose、脚本、CI、README 和验收文档。

不得跨 Task 提前实现后续能力。基础文件应归入第一个需要它并能独立验收的 Task，不单独创建“脚手架提交”。

## Shared HTTP Contracts

共享示例位于：

```text
contracts/
├── incident-intake-v1.example.json
├── rag-search-v1.request.json
├── rag-search-v1.response.json
└── acceptance-v1.response.json
```

Java 与 Python 测试必须分别读取同一份 fixture；fixture 是兼容性样例，不是第二个运行时数据源。

### Incident intake

```java
public record IncidentRequest(
        String source,
        String externalAlertId,
        String service,
        String severity,
        String title,
        String summary,
        Instant startedAt,
        Map<String, String> labels
) {}

public record IncidentAccepted(
        String incidentId,
        String taskId,
        boolean deduplicated
) {}
```

`POST /api/incidents` 首次返回 HTTP 202 与 `deduplicated=false`；重复请求返回 HTTP 200、相同 `incidentId/taskId` 与 `deduplicated=true`。

### RAG search

```java
public record RagSearchRequest(
        int contractVersion,
        String knowledgeBaseId,
        String indexVersion,
        String query,
        int topK
) {}

public record RagHit(
        String chunkId,
        String title,
        String section,
        String source,
        double score,
        String excerpt
) {}

public record RagSearchResponse(
        int contractVersion,
        String indexVersion,
        List<RagHit> hits
) {}
```

`search_knowledge` 的 model-visible `topK` 可省略；Java Tool 把省略值固定为 `3`、显式值只接受 1–5，并始终向 Python HTTP 契约发送该合法整数。

端点：

```http
GET  /internal/rag/indexes/{knowledgeBaseId}/active
POST /internal/rag/search
GET  /internal/readiness
GET  /internal/acceptance
POST /mcp
```

active-version 响应固定为：

```json
{
  "contractVersion": 1,
  "knowledgeBaseId": "incident-ops",
  "indexVersion": "v1-0123456789abcdef",
  "ready": true
}
```

### Acceptance evidence

`contracts/acceptance-v1.response.json` fixes the bounded Python response consumed by Java. The optional `idempotencyKey` query value is never echoed:

```json
{
  "contractVersion": 1,
  "scope": "idempotency-key",
  "toolAttempts": {
    "query_metrics": 0,
    "search_logs": 0,
    "create_ticket": 2
  },
  "createTicketAttempts": 2,
  "uniqueTicketCount": 1,
  "ticketIds": ["OPS-0123456789AB"],
  "faultGateState": "released"
}
```

`scope` is `all` without a query and `idempotency-key` with one. Counts are non-negative, `ticketIds` has at most 16 bounded IDs, and `faultGateState` is one of `disabled|idle|armed|blocked|released`.

## Shared MCP Contracts

Java 受信配置只允许 server ID `fake-ops`，连接地址在配置中解析为 Python `/mcp`。工具名和策略固定为：

| Name | Model-visible arguments | Java idempotency | Java approval |
|---|---|---|---|
| `query_metrics` | `service,start,end` | `READ_ONLY` | `NONE` |
| `search_logs` | `service,start,end,query,limit` | `READ_ONLY` | `NONE` |
| `create_ticket` | `title,severity,evidence` | `IDEMPOTENT` | `REQUIRE_APPROVAL` |

`create_ticket` 的远端实际参数在 Java 注入后为：

```json
{
  "title": "Checkout database connection pool exhaustion",
  "severity": "P1",
  "evidence": "bounded evidence",
  "idempotency_key": "call-create-ticket-001"
}
```

MCP 连接必须执行 `initialize → listTools → callTool`。旧任务恢复时，当前发现 Schema 的 canonical SHA-256 必须等于 `TaskProfileSnapshot.tools[].schemaHash`；不相等抛 `ToolSchemaDriftException`，禁止静默采用新 Schema。

## Runtime Outcome Contract

Task 9 扩展工具执行返回值，以区分“有确定结果”和“远端副作用结果未知”：

```java
public record ToolExecutionOutcome(Kind kind, String content) {
    public enum Kind { DEFINITIVE, REMOTE_OUTCOME_UNKNOWN }

    public static ToolExecutionOutcome definitive(String content) {
        return new ToolExecutionOutcome(Kind.DEFINITIVE, content);
    }

    public static ToolExecutionOutcome remoteOutcomeUnknown(String content) {
        return new ToolExecutionOutcome(Kind.REMOTE_OUTCOME_UNKNOWN, content);
    }
}
```

`READ_ONLY` 超时形成结构化 definitive error 并回给 Agent；`IDEMPOTENT create_ticket` 在连接中断/超时时保持 `IN_PROGRESS`，`ToolBatchCoordinator` 返回 `RECOVERY_REQUIRED`，当前 drive 不写 tool result、不写 FAILED，恢复时用同一 `tool_call_id` 重放；非幂等副作用仍进入 `IN_DOUBT`。

## Migration Ownership

| Migration | Owner | Content |
|---|---|---|
| Java `V1__baseline_runtime.sql` | Runtime Task 1 | 现有 runtime baseline |
| Java `V2__runtime_profiles_and_batches.sql` | Runtime Task 3 | profile snapshot 与 batch sequence |
| Java `V3__incident_intake.sql` | Task 5 | 告警审计、唯一 `(source, external_alert_id)`、唯一 task ID |
| Python Alembic `0001_demo_ticket.py` | Task 8 | `fake_ops.demo_ticket` 与唯一 idempotency key |
| Java `V4__durable_approval.sql` | Task 10 | approval request、状态、决定和索引 |

两个迁移系统不能创建、修改或验证对方拥有的表。

## Configuration Namespace

Java 新配置只能位于：

```yaml
reagent:
  incidents:
  rag:
  mcp:
  approval:
  faults:
  profiles:
```

Python 环境变量使用 `AGENT_CAPABILITIES_` 前缀：

```text
AGENT_CAPABILITIES_ENV
AGENT_CAPABILITIES_REDIS_URL
AGENT_CAPABILITIES_MYSQL_URL
AGENT_CAPABILITIES_KNOWLEDGE_ROOT
AGENT_CAPABILITIES_MODEL_ID
AGENT_CAPABILITIES_ACCEPTANCE_ENABLED
AGENT_CAPABILITIES_CHAOS_ENABLED
AGENT_CAPABILITIES_OTLP_ENDPOINT
```

## Task-to-Evidence Traceability

| Required behavior | Owning task | Mandatory evidence |
|---|---:|---|
| 相同告警并发只建一个 task | 5 | `IncidentIntakeIT.concurrentDuplicateReturnsOneTask` |
| 新告警 commit 后只调度一次，重复告警不调度 | 5 | `IncidentIntakeIT.freshStartsOnceAndDuplicateDoesNotRestart` |
| 两语言解析同一 RAG fixture | 5/7 | Python `test_contract.py` + Java `RagContractTest` |
| 稳定 chunk ID、无重复初始化 | 6 | `test_chunker.py` + `test_initializer.py` |
| Redis HNSW、失败版本不切 active | 6 | `test_redis_index_integration.py` |
| MiniLM top-3、MRR ≥ 0.80 | 6 | `test_retrieval_quality.py` + JSON report |
| Java 冻结并校验 indexVersion | 7 | `KnowledgeSearchToolTest` + `RagGatewayIT` |
| MCP Streamable HTTP initialize/list/call | 8/9 | Python `test_mcp_protocol.py` + Java `McpProtocolIT` |
| 同键同工单、异参冲突 | 8 | `test_ticket_repository_integration.py` |
| LLM 不能伪造 idempotency key | 9 | `McpToolAdapterTest` |
| 审批跨重启、重复幂等、相反 409 | 10 | `ApprovalServiceIT` + `ApprovalControllerTest` |
| 整批审批屏障 | 10/11 | `ToolBatchApprovalTest` |
| 五个确定性崩溃窗口 | 11 | `IncidentCrashMatrixIT` |
| committed-before-response 仅一张工单 | 11 | `IncidentDangerousWindowIT` + acceptance count |
| readiness 与 W3C trace 贯通 | 12 | `ReadinessIT` + `CrossServiceTraceIT` |
| 浏览器批准/拒绝/重连 | 13 | API/static contract + Playwright-less HTTP flow |
| 一键 happy/reject/failover | 14 | Compose smoke scripts 与 CI artifact |

## Spec-to-Plan Coverage Matrix

| Approved spec section | Owning plan/tasks | Executable evidence or gate |
|---:|---|---|
| 1. 决策与治理关系 | Roadmap Task 4 gate；Tasks 5–14 replacement order | Task 4 review must pass before merge/start of Task 5 |
| 2. 产品定位 | Tasks 5, 11, 14 | Incident HTTP entry, full workflow IT, README boundary |
| 3. 成功标准 | Tasks 11–14 | Scenario ITs, trace/evidence reports, console and scripts |
| 4. 总体架构 | Tasks 5, 8, 14 | One Python ASGI image plus Java/MySQL/Redis/Jaeger Compose contract |
| 5. 唯一控制面 | Tasks 5, 7, 9–11 | Java owns task/profile/tool/approval; Python tests expose capability-only APIs |
| 6. 告警来源与接入 | Tasks 5, 13, 14 | `IncidentIntakeIT`, console trigger, unique-ID demo scripts |
| 7. Python 服务 | Tasks 5, 8, 12, 14 | locked package, one lifespan, ownership/readiness tests, one image |
| 8. RAG 设计 | Tasks 5–7 | contract tests, chunk/index integration, MiniLM report, Java gateway IT |
| 9. MCP Fake Ops | Tasks 8–9 | Python and Java initialize/list/call protocol tests; ticket concurrency IT |
| 10. 审批与可靠性 | Tasks 9–11 | unknown-outcome tests, approval restart/conflict tests, whole-batch barrier |
| 11. 端到端流程 | Tasks 11, 14 | happy/reject/crash matrix/dangerous-window ITs and three demo scripts |
| 12. 错误处理 | Tasks 5–11, 12 | duplicate/timeout/no-hit/drift/restart/recovery-limit tests plus readiness |
| 13. 可观测性 | Tasks 7, 9, 11, 12 | bounded events, W3C MCP/RAG propagation and `CrossServiceTraceIT` |
| 14. 安全边界 | Global constraints; Tasks 5, 7–9, 12–14 | trusted configuration, bounded bodies, hidden key, DOM/trace/Compose scans |
| 15. 测试与自动化证据 | Every task; Task 14 CI | Java/Python fast, Docker integration, quality and Compose jobs without skip |
| 16. 演示控制台 | Task 13 | static/API contract and reconnecting console flow IT |
| 17. 文档交付范围 | Tasks 12, 14 | generated technical evidence and README; no `docs/interview` |
| 18. Task 重排 | Plan Suite and Required Order | sequential Task 4 → 5–7 → 8–11 → 12–14 gates |
| 19. 迁移所有权 | Tasks 5, 8, 10 | V3/V4 Flyway and Alembic 0001 fresh/legacy/ownership tests |
| 20. 明确非目标 | Global constraints; Task 14 README | dependency/structure scans and documented limitations |
| 21. 最终验收 | Task-to-Evidence table; all completion gates | top-3/MRR, unique ticket, failover, trace, UI and one-command evidence |
| 22. 风险与缓解 | Tasks 5–14 | locks/pins, versioned contracts, scoped ownership, restart and drift tests |
| 23. 官方技术依据 | Tasks 6, 8, 9 | locked MiniLM, MCP Python 1.28.1, MCP Java 2.0.0/Jackson 2 compile gates |
| 24. 后续流程 | Task 4 gate and execution handoff | no Task 5 code before approved plans and Task 4 Step 4.7 review |

## Final Completion Gate

- [ ] `./mvnw -B clean test` 成功。
- [ ] `./mvnw -B -Pci verify` 成功且 Docker 测试无 skip。
- [ ] `cd services/agent-capabilities && uv sync --locked --all-groups` 成功。
- [ ] `uv run ruff check . && uv run pyright && uv run pytest -m "not integration and not quality"` 成功。
- [ ] Python Redis/MySQL/MCP integration 与 MiniLM quality gate 成功。
- [ ] `scripts/demo-smoke.sh`、`scripts/demo-reject.sh`、`scripts/demo-failover.sh` 全部成功。
- [ ] acceptance 报告证明同一 `tool_call_id` 的 attempt count 可大于 1 且 unique ticket count 等于 1。
- [ ] 所有 RAG hit 可解析到真实 chunk/source，关键查询 top 3 且 MRR ≥ 0.80。
- [ ] 普通 Profile 没有 chaos，Python acceptance/chaos 接口在未启用时不可用。
- [ ] README 只描述已通过上述命令证明的能力，且不存在逐 Task 面试材料目录。
