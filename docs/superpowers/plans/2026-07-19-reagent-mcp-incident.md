# ReAgent MCP, Durable Approval, and Incident Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 Python Fake Ops MCP、Java 官方 MCP Client 和持久审批，把 Task 5–7 的告警与 RAG 取证扩展为可恢复的事故闭环，并证明危险崩溃窗口只创建一张工单。

**Architecture:** Python FastMCP 以无状态 Streamable HTTP 暴露指标、日志和幂等工单，业务持久性来自 `fake_ops.demo_ticket`。Java 发现并冻结远端 Schema，以本地策略包装成 ReAgent Tool；`ToolBatchCoordinator` 在任何需要审批的工具出现时阻断整个 assistant batch，决定持久化后再恢复同一 task/tool_call。

**Tech Stack:** MCP Python SDK 1.28.1、FastMCP、Starlette、SQLAlchemy 2、Alembic、MySQL 8；MCP Java SDK 2.0.0、Jackson 2、Spring Boot 3.3.5、JPA/Flyway、Testcontainers、JUnit 5。

## Global Constraints

- 先通过 `2026-07-19-reagent-python-rag.md` Completion Gate。
- Python FastMCP 必须是 `stateless_http=True`、`json_response=True`、`streamable_http_path="/"`，挂载到 Starlette `/mcp`；最终 URL 是 `/mcp`，不是 `/mcp/mcp`。
- ASGI lifespan 必须显式进入并退出 `mcp.session_manager.run()`。
- Java 使用官方 JDK Streamable HTTP transport，不采用 legacy SSE，不让 Task/LLM 指定 URL。
- Java 冻结远端 JSON Schema，但 IdempotencyClass、ApprovalPolicy、timeout 和 provider 均来自本地受信配置。
- `query_metrics`、`search_logs` 为 `READ_ONLY + NONE`；`create_ticket` 为 `IDEMPOTENT + REQUIRE_APPROVAL`。
- `idempotency_key` 只由 Java 从 `tool_call_id` 注入；模型传入、未知字段或同键异参全部 fail-closed。
- 等待审批时 task 状态是 `WAITING_APPROVAL`，owner/lease 释放；批准或拒绝后切回 `RUNNING`、owner 为空并走恢复调度。
- 拒绝不是 Task 失败：写 `REJECTED` tool result，远端调用数为 0，Agent 继续生成最终说明。
- 整批屏障意味着存在任何 PENDING approval 时，同一个 assistant message 的只读工具也暂不执行。
- 远端幂等副作用超时/断连时不伪造成 DONE error；账本保持 `IN_PROGRESS`，后续恢复使用原 tool_call_id。
- 故障测试使用 latch、事件或可控 Clock，不使用随机 sleep 猜时序。
- Task 8–11 不添加最终 UI/Compose/README，也不生成逐 Task 面试材料。

---

### Task 8: Python Fake Ops MCP, idempotent ticket, and fault gate

**Files:**

- Create: `contracts/acceptance-v1.response.json`
- Create: `services/agent-capabilities/alembic.ini`
- Create: `services/agent-capabilities/migrations/env.py`
- Create: `services/agent-capabilities/migrations/script.py.mako`
- Create: `services/agent-capabilities/migrations/versions/0001_demo_ticket.py`
- Create: `services/agent-capabilities/src/agent_capabilities/database.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/__init__.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/models.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/metrics.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/logs.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/tickets.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/faults.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/mcp_server.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/acceptance.py`
- Create: `services/agent-capabilities/src/agent_capabilities/fake_ops/alerts.py`
- Modify: `services/agent-capabilities/src/agent_capabilities/app.py`
- Modify: `services/agent-capabilities/src/agent_capabilities/readiness.py`
- Create: `services/agent-capabilities/tests/test_fake_metrics.py`
- Create: `services/agent-capabilities/tests/test_fake_logs.py`
- Create: `services/agent-capabilities/tests/test_ticket_repository_integration.py`
- Create: `services/agent-capabilities/tests/test_fault_gate.py`
- Create: `services/agent-capabilities/tests/test_mcp_protocol.py`
- Create: `services/agent-capabilities/tests/test_acceptance.py`
- Create: `services/agent-capabilities/tests/test_alembic_integration.py`

**Interfaces:**

- Consumes: Task 5 ASGI/config, Python-owned MySQL URL and MCP SDK 1.28.1.
- Produces: `/mcp` tools, `TicketService.create_or_read`, chaos gate, acceptance API, Alembic-managed `demo_ticket`.

- [ ] **Step 8.1: Write Alembic fresh/upgrade tests before the migration**

`test_alembic_integration.py` creates an empty `fake_ops` database, runs `alembic upgrade head` twice, and asserts exactly one revision and this table contract:

```sql
CREATE TABLE fake_ops.demo_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    ticket_id VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    evidence MEDIUMTEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    conflict_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_demo_ticket_key UNIQUE (idempotency_key),
    CONSTRAINT uk_demo_ticket_id UNIQUE (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

The migration and metadata must explicitly use schema `fake_ops`; it must not inspect or alter the Java `reagent` schema.

Run:

```bash
cd services/agent-capabilities
uv run pytest tests/test_alembic_integration.py -m integration -q
```

Expected RED: Alembic files and table are absent.

- [ ] **Step 8.2: Implement migration and idempotent repository tests**

Write tests for sequential replay, 16-way concurrent replay, different keys, same key/different payload, title/evidence/severity bounds, and stable ticket ID. The canonical request hash is SHA-256 of sorted compact JSON containing only `title`, `severity`, and `evidence`; it excludes idempotency key.

Stable ID:

```python
def ticket_id_for(idempotency_key: str) -> str:
    digest = hashlib.sha256(idempotency_key.encode("utf-8")).hexdigest().upper()
    return f"OPS-{digest[:12]}"
```

Use one MySQL upsert that increments `attempt_count`, increments `conflict_count` when the existing `request_hash` differs, and never overwrites original fields. Select the stored row in the same transaction. Commit the counter update, then raise `IdempotencyConflict` when hashes differ.

Run:

```bash
uv run pytest tests/test_ticket_repository_integration.py -m integration -q
```

Expected GREEN: same key yields one row and one ticket ID; conflicting parameters raise and leave original content unchanged.

- [ ] **Step 8.3: Add deterministic metrics and logs fixtures**

`query_metrics` accepts service/start/end and only supports the fixed checkout demo window. It returns bounded structured JSON with request rate, error rate `14.2`, p95 seconds `1.8`, pool max `40`, active `40`, idle `0`, pending `27`, and acquisition timeout count `83`.

`search_logs` accepts service/start/end/query/limit (1–50), returns timestamps and bounded lines containing `SQLTransientConnectionException`, `Connection is not available, request timed out after 30000ms`, and pool name. It rejects unknown service, reversed/oversized windows, regex-like unbounded query syntax and unknown fields.

Run:

```bash
uv run pytest tests/test_fake_metrics.py tests/test_fake_logs.py -q
```

Expected RED, then GREEN after deterministic providers are implemented.

- [ ] **Step 8.4: Build the post-commit fault gate**

Define an async `TicketFaultGate.after_commit(idempotency_key)` with disabled and latch implementations. The latch exposes `arm`, `wait_until_blocked`, and `release`; it blocks only after `TicketService.create_or_read` has committed and only for the selected key. `test_fault_gate.py` proves the database row exists before `wait_until_blocked` completes.

Chaos routes exist only when `settings.chaos_enabled`:

```http
POST /internal/chaos/ticket-after-commit/arm
GET  /internal/chaos/ticket-after-commit/status
POST /internal/chaos/ticket-after-commit/release
```

When disabled they return 404, not a descriptive 403 that exposes the feature.

- [ ] **Step 8.5: Register exact FastMCP tools and mount `/mcp`**

Create FastMCP exactly as:

```python
mcp = FastMCP(
    "reagent-fake-ops",
    stateless_http=True,
    json_response=True,
    streamable_http_path="/",
)
```

Register typed async functions `query_metrics`, `search_logs`, and `create_ticket`. `create_ticket` requires `idempotency_key` in the remote MCP schema, calls the committed transaction, awaits `fault_gate.after_commit`, then returns `{ticketId, deduplicated, attemptCount}`. The Java adapter removes that field before exposure to the LLM.

Combine lifespan:

```python
@contextlib.asynccontextmanager
async def lifespan(_: Starlette) -> AsyncIterator[None]:
    run_migrations(settings.mysql_url)
    initialize_rag()
    async with mcp.session_manager.run():
        yield

app = Starlette(
    routes=[
        Route("/internal/readiness", readiness, methods=["GET"]),
        Route("/internal/acceptance", acceptance, methods=["GET"]),
        Route("/internal/rag/search", rag_search, methods=["POST"]),
        Mount("/mcp", app=mcp.streamable_http_app()),
    ],
    lifespan=lifespan,
)
```

The actual factory may close over Settings and services, but route paths and session-manager lifecycle must match this contract.

- [ ] **Step 8.6: Prove initialize/list/call over Streamable HTTP**

Use the official Python client:

```python
async with streamable_http_client(server_url) as streams:
    read_stream, write_stream, _ = streams
    async with ClientSession(read_stream, write_stream) as session:
        await session.initialize()
        listed = await session.list_tools()
        result = await session.call_tool(
            "query_metrics",
            {"service": "checkout", "start": START, "end": END},
        )
```

Tests assert exactly three names, `create_ticket` includes remote `idempotency_key`, all calls return JSON content, and the mounted URL is `/mcp`. Also exercise two concurrent `create_ticket` MCP calls with the same key.

Run:

```bash
uv run pytest tests/test_mcp_protocol.py -m integration -q
```

Expected GREEN.

- [ ] **Step 8.7: Add bounded acceptance state and verify Task 8**

`GET /internal/acceptance` is available only when enabled and returns contract version, per-tool attempt counts, unique ticket count, ticket IDs, last committed idempotency key, and fault gate state. It never returns full evidence, DB URL or secrets. Validate the shared fixture.

Run:

```bash
uv run ruff check .
uv run pyright
uv run pytest -m "not integration and not quality" -q
uv run pytest -m integration -q
alembic upgrade head
alembic current
git diff --check
```

Commit:

```bash
git add contracts/acceptance-v1.response.json services/agent-capabilities
git commit -m "feat: add idempotent Fake Ops MCP service"
```

---

### Task 9: Java official MCP client, frozen Schema, and uncertain outcomes

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/reagent/mcp/McpProperties.java`
- Create: `src/main/java/com/reagent/mcp/McpGateway.java`
- Create: `src/main/java/com/reagent/mcp/McpRemoteTool.java`
- Create: `src/main/java/com/reagent/mcp/McpCallResult.java`
- Create: `src/main/java/com/reagent/mcp/OfficialMcpGateway.java`
- Create: `src/main/java/com/reagent/mcp/McpContractException.java`
- Create: `src/main/java/com/reagent/mcp/RemoteOutcomeUnknownException.java`
- Create: `src/main/java/com/reagent/mcp/McpToolAdapter.java`
- Create: `src/main/java/com/reagent/mcp/McpToolConfiguration.java`
- Create: `src/main/java/com/reagent/mcp/McpReadiness.java`
- Create: `src/main/java/com/reagent/tool/ToolExecutionOutcome.java`
- Modify: `src/main/java/com/reagent/tool/ToolExecutor.java`
- Modify: `src/main/java/com/reagent/core/BatchDisposition.java`
- Modify: `src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/profile/ToolCatalogResolver.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/mcp/McpSdkCompatibilityTest.java`
- Create: `src/test/java/com/reagent/mcp/McpToolAdapterTest.java`
- Create: `src/test/java/com/reagent/mcp/McpGatewayContractTest.java`
- Create: `src/test/java/com/reagent/mcp/McpProtocolIT.java`
- Modify: `src/test/java/com/reagent/tool/ToolExecutorTracingTest.java`
- Modify: `src/test/java/com/reagent/core/ToolBatchCoordinatorTest.java`

**Interfaces:**

- Consumes: Python `/mcp`, current Tool/Profile snapshots, Task 4 coordinator/fault hooks.
- Produces: `McpGateway.discover/call`, three local `McpToolAdapter` beans, exact Schema drift enforcement, `ToolExecutionOutcome`, and `RECOVERY_REQUIRED` batch handling.

- [ ] **Step 9.1: Pin Jackson-2-compatible SDK modules and compile the official API**

Add:

```xml
<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp-core</artifactId>
  <version>2.0.0</version>
</dependency>
<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp-json-jackson2</artifactId>
  <version>2.0.0</version>
</dependency>
```

Do not add `io.modelcontextprotocol.sdk:mcp`, because it brings the SDK's default Jackson 3 bundle. Characterization test imports `JacksonMcpJsonMapper`, creates `HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/mcp").build()`, creates `McpClient.sync(transport).requestTimeout(Duration.ofSeconds(3)).build()`, and closes it without network I/O.

Run:

```bash
./mvnw -B -Dtest=McpSdkCompatibilityTest test
```

Expected RED before dependencies, then GREEN with only MCP 2.0.0 artifacts in `dependency:tree`.

- [ ] **Step 9.2: Define the gateway and write protocol contract tests**

Exact port:

```java
public interface McpGateway extends AutoCloseable {
    List<McpRemoteTool> discover(String serverId);
    McpCallResult call(String serverId, String toolName, Map<String, Object> arguments);
    @Override void close();
}

public record McpRemoteTool(
        String serverId,
        String name,
        String description,
        Map<String, Object> inputSchema
) {}

public record McpCallResult(String text, boolean error) {}
```

`McpProperties` maps trusted server ID to base URL, endpoint, connect/request timeout, maximum response bytes and exact allowlisted tool policies. Unknown server/tool fails closed.

`McpGatewayContractTest` covers duplicate tool names, illegal names, non-object schemas, schema/body bounds, non-text result content, remote `isError`, timeout and reconnect-after-Python-restart.

- [ ] **Step 9.3: Implement initialize/list/call and W3C propagation**

For each configured server, build a sync client using the official JDK Streamable HTTP transport, call `initialize`, cache `listTools` for readiness, and recreate the client once after transport failure. Use the transport's HTTP request customizer to inject current W3C `traceparent`/`tracestate`; never send task goal or secret as headers.

`call` uses:

```java
client.callTool(CallToolRequest.builder(toolName)
        .arguments(Map.copyOf(arguments))
        .build());
```

Accept only bounded text content and canonical JSON object schemas. Close all clients on Spring shutdown.

- [ ] **Step 9.4: Adapt discovered tools with local policies and a reserved key**

Configure three beans with known server/tool mapping. `parameterSchema()` discovers the current remote schema and returns an immutable copy; for `create_ticket` it removes `idempotency_key` from properties and required, then sets `additionalProperties=false`. `execute` rejects any model-supplied key before injection:

```java
if (arguments.has("idempotency_key")) {
    throw new McpContractException("reserved idempotency_key is model-inaccessible");
}
Map<String, Object> remote = mapper.convertValue(arguments, MAP_TYPE);
if (idempotencyClass == IdempotencyClass.IDEMPOTENT) {
    remote.put("idempotency_key", Objects.requireNonNull(context.idempotencyKey()));
}
return gateway.call(serverId, remoteToolName, remote).text();
```

Snapshot creation hashes the sanitized model-visible schema. Recovery calls `parameterSchema()` again, so `ToolCatalogResolver` rejects current remote drift against the frozen hash.

Tests assert local policy wins over MCP annotations, missing local policy fails closed, key cannot be forged, and the injected value equals tool_call_id.

- [ ] **Step 9.5: Preserve unknown idempotent remote outcomes**

Refactor executor results to the shared `ToolExecutionOutcome`. Existing local tools and read-only MCP errors return `DEFINITIVE`. A timeout/connection break while invoking the idempotent `create_ticket` returns `REMOTE_OUTCOME_UNKNOWN`; `InjectedWorkerCrashException` and `FencedExecutionException` must propagate rather than be converted to text.

Extend `BatchDisposition`:

```java
public enum BatchDisposition {
    EXECUTED,
    WAITING_APPROVAL,
    RECOVERY_REQUIRED
}
```

Coordinator behavior:

- mark all selected calls `IN_PROGRESS` before execution;
- persist only definitive results;
- leave unknown idempotent result `IN_PROGRESS` and publish a bounded recovery event;
- return `RECOVERY_REQUIRED` after other definitive results are recorded in call order;
- AgentRunner exits the current drive without terminal state or FAILED;
- recovery classifies the same IDEMPOTENT call as safely replayable with unchanged idempotency key.

Add unit tests for single/concurrent execution, timeout, propagated crash, read-only definitive error, idempotent unknown result and side-effectful IN_DOUBT.

- [ ] **Step 9.6: Run real Java ↔ Python MCP protocol evidence**

`McpProtocolIT` starts the Python image with MySQL and Redis, then uses `OfficialMcpGateway` to initialize, list exactly three tools and call metrics/logs/ticket. It asserts `/mcp` works and `/mcp/mcp` does not, same Java key returns one Python ticket, reserved key is absent from the model-visible Schema, and a modified remote Schema causes `ToolSchemaDriftException` for a persisted task snapshot.

Run:

```bash
./mvnw -B -Dit.test=McpProtocolIT verify
```

Expected GREEN.

- [ ] **Step 9.7: Verify and commit Task 9**

```bash
./mvnw -B test
./mvnw -B -Pci verify
./mvnw -B dependency:tree -Dincludes=io.modelcontextprotocol.sdk
git diff --check
```

Expected dependency tree: version 2.0.0 only, with `mcp-core` and Jackson 2 adapter and no Jackson 3 adapter.

Commit:

```bash
git add pom.xml src/main/java/com/reagent/mcp src/main/java/com/reagent/tool \
  src/main/java/com/reagent/core src/main/java/com/reagent/profile src/main/java/com/reagent/obs \
  src/main/resources/application.yml src/test/java/com/reagent/mcp \
  src/test/java/com/reagent/tool src/test/java/com/reagent/core
git commit -m "feat: add official Java MCP gateway and adapters"
```

---

### Task 10: Durable approval, whole-batch barrier, API, and cancellation

**Files:**

- Create: `src/main/resources/db/migration/V4__durable_approval.sql`
- Create: `src/main/java/com/reagent/approval/ApprovalStatus.java`
- Create: `src/main/java/com/reagent/approval/ApprovalDecision.java`
- Create: `src/main/java/com/reagent/approval/ApprovalRequestEntity.java`
- Create: `src/main/java/com/reagent/approval/ApprovalRequestRepository.java`
- Create: `src/main/java/com/reagent/approval/ApprovalView.java`
- Create: `src/main/java/com/reagent/approval/ApprovalConflictException.java`
- Create: `src/main/java/com/reagent/approval/ApprovalDecisionTransaction.java`
- Create: `src/main/java/com/reagent/approval/ApprovalService.java`
- Create: `src/main/java/com/reagent/api/ApprovalController.java`
- Modify: `src/main/java/com/reagent/api/ApiExceptionHandler.java`
- Modify: `src/main/java/com/reagent/persist/TaskStatus.java`
- Modify: `src/main/java/com/reagent/persist/ToolCallStatus.java`
- Modify: `src/main/java/com/reagent/persist/TaskRepository.java`
- Modify: `src/main/java/com/reagent/persist/StateStore.java`
- Modify: `src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/api/TaskController.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/test/java/com/reagent/persist/SchemaMigrationIT.java`
- Create: `src/test/java/com/reagent/approval/ApprovalServiceIT.java`
- Create: `src/test/java/com/reagent/api/ApprovalControllerTest.java`
- Create: `src/test/java/com/reagent/core/ToolBatchApprovalTest.java`
- Modify: `src/test/java/com/reagent/api/TaskControllerTest.java`

**Interfaces:**

- Consumes: frozen ToolSnapshot policies, token-fenced coordinator, `AgentRunner.resumeAsync`.
- Produces: durable approval rows, WAITING state, decision/cancel APIs, batch barrier and synthetic REJECTED results.

- [ ] **Step 10.1: Write V4 and state-machine tests first**

Migration:

```sql
CREATE TABLE approval_request (
    tool_call_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    assistant_message_seq INT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    arguments_snapshot MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    decision_reason VARCHAR(512) NULL,
    requested_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (tool_call_id),
    INDEX idx_approval_task_status (task_id, status),
    CONSTRAINT fk_approval_task FOREIGN KEY (task_id) REFERENCES task(id),
    CONSTRAINT fk_approval_tool_call FOREIGN KEY (tool_call_id) REFERENCES tool_call(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Add `WAITING_APPROVAL` and `REJECTED`. Extend migration/fresh/legacy assertions, entity enum tests and `loadContext` tests so a REJECTED tool message is recoverable.

Run:

```bash
./mvnw -B -Dit.test=SchemaMigrationIT,ApprovalServiceIT verify
```

Expected RED.

- [ ] **Step 10.2: Implement the whole-batch approval barrier**

Before any tool in one assistant sequence executes, coordinator reads each frozen `ApprovalPolicy`. It upserts PENDING requests for approval-required calls. If any request is PENDING, atomically call `StateStore.waitForApproval(token)` to set task WAITING_APPROVAL, clear owner/lease, publish `APPROVAL_REQUIRED`, and return `WAITING_APPROVAL` without marking any call IN_PROGRESS.

On recovery:

- APPROVED calls may execute;
- REJECTED calls are skipped because their synthetic tool message and REJECTED ledger are terminal;
- other calls in the same batch execute only when no approval is PENDING;
- repeated coordinator entry does not duplicate requests or messages.

`ToolBatchApprovalTest` uses one READ_ONLY and one approval-required call in both orders and proves zero execution before decision.

- [ ] **Step 10.3: Implement transactional decisions and idempotent conflict rules**

Exact API:

```http
GET  /api/tasks/{taskId}/approvals
POST /api/tasks/{taskId}/approvals/{toolCallId}/decision
```

Request:

```json
{"decision":"APPROVE","reason":"Evidence confirms pool exhaustion"}
```

`ApprovalDecisionTransaction.decide` locks task, approval and tool-call rows. Same decision replay returns current view; opposite decision throws `ApprovalConflictException` mapped to 409; missing/wrong task is 404; terminal/cancelled task is 409. APPROVE leaves tool call PENDING. REJECT sets approval and ledger REJECTED and appends exactly one bounded tool message stating no remote action occurred.

When no PENDING approvals remain, set task RUNNING, owner/lease null and commit. Only after transaction returns does `ApprovalService` hit `AFTER_APPROVAL_DECIDED_BEFORE_RESUME` and call `runner.resumeAsync(taskId)`.

- [ ] **Step 10.4: Implement waiting-task cancellation atomically**

`POST /api/tasks/{id}/cancel` on WAITING_APPROVAL locks the task, changes all PENDING approvals to REJECTED with reason `task-cancelled`, changes all PENDING calls in the blocked batch to REJECTED, appends one synthetic tool result per call in original order, and sets task CANCELLED. It never invokes Python and never schedules resume. Existing RUNNING cancel semantics remain unchanged.

Tests cover waiting cancel, repeated cancel, decision-after-cancel conflict and create-ticket remote call count zero.

- [ ] **Step 10.5: Prove restart and decision HTTP behavior**

`ApprovalServiceIT` creates WAITING state, rebuilds a new Spring context against the same MySQL, decides, and asserts recovery completes. `ApprovalControllerTest` covers list, approve, reject, same replay, opposite 409, cross-task ID, bounds and safe response fields.

Run:

```bash
./mvnw -B -Dtest=ApprovalControllerTest,TaskControllerTest,ToolBatchApprovalTest test
./mvnw -B -Dit.test=ApprovalServiceIT verify
```

Expected GREEN.

- [ ] **Step 10.6: Verify and commit Task 10**

```bash
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
```

Commit:

```bash
git add src/main/resources/db/migration/V4__durable_approval.sql \
  src/main/java/com/reagent/approval src/main/java/com/reagent/api \
  src/main/java/com/reagent/persist src/main/java/com/reagent/core \
  src/main/java/com/reagent/stream src/test/java
git commit -m "feat: add durable approval and batch barrier"
```

---

### Task 11: Full incident workflow and deterministic crash matrix

**Files:**

- Create: `src/test/java/com/reagent/testsupport/PythonCapabilitiesContainer.java`
- Create: `src/test/java/com/reagent/testsupport/IncidentScenarioFixture.java`
- Create: `src/test/java/com/reagent/testsupport/LatchingFaultInjector.java`
- Create: `src/test/java/com/reagent/incident/IncidentWorkflowIT.java`
- Create: `src/test/java/com/reagent/incident/IncidentRejectIT.java`
- Create: `src/test/java/com/reagent/incident/IncidentCrashMatrixIT.java`
- Create: `src/test/java/com/reagent/incident/IncidentDangerousWindowIT.java`
- Modify: `src/main/java/com/reagent/mcp/McpToolAdapter.java`
- Modify: `src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**

- Consumes: real Java runtime/MySQL/Redis, real Python RAG/MCP/MySQL, scripted LLM, all five FaultPoint values.
- Produces: happy/reject/dangerous-window scenarios and a deterministic five-point crash matrix.

- [ ] **Step 11.1: Build one strict four-turn incident fixture**

The scripted LLM must assert actual persisted messages and exact tool catalog on every turn:

1. call `search_knowledge` with checkout pool query;
2. after real citations, call `query_metrics` and `search_logs` in one assistant batch;
3. after metrics/logs, call `create_ticket` using bounded evidence and no idempotency key;
4. after ticket or REJECTED result, return a final answer containing actual chunk ID, metrics facts, log fact, decision and actual ticket ID when present.

Tool-call IDs are fixed constants per scenario so replay assertions are stable. The fake fails on missing evidence, extra turns, wrong tool names or hard-coded ticket output.

- [ ] **Step 11.2: Prove happy and reject flows over real protocols**

`IncidentWorkflowIT` posts the shared alert through HTTP, waits for WAITING_APPROVAL, asserts RAG/metrics/logs results exist, approves through HTTP, then asserts COMPLETED and Python acceptance unique ticket count one.

`IncidentRejectIT` rejects through HTTP, asserts `create_ticket` call count zero, tool ledger REJECTED, Task COMPLETED, and final answer says no ticket was created.

Run:

```bash
./mvnw -B -Dit.test=IncidentWorkflowIT,IncidentRejectIT verify
```

Expected GREEN.

- [ ] **Step 11.3: Wire and test all five exact fault boundaries**

Use one parameterized `IncidentCrashMatrixIT` with these committed-state assertions:

| Fault point | State before crash | Expected recovery fact |
|---|---|---|
| `AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL` | assistant + PENDING ledger | approval created once after recovery |
| `AFTER_APPROVAL_DECIDED_BEFORE_RESUME` | decision committed + RUNNING owner null | scanner/second worker resumes |
| `AFTER_TOOL_MARKED_IN_PROGRESS` | ledger IN_PROGRESS, Python calls 0 | same tool_call_id executes after recovery |
| `AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT` | Python ticket committed, Java IN_PROGRESS | replay returns same ticket |
| `AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER` | DONE + tool message | tool not called again; final answer resumes |

`McpToolAdapter` hits the remote-side-effect point after a successful `create_ticket` response and before returning the result to ToolExecutor. Crash exceptions must escape executor/coordinator and leave task nonterminal.

- [ ] **Step 11.4: Prove the true committed-before-response window**

Arm Python's post-commit gate for the fixed create-ticket key. Start Worker A, approve, wait until Python acceptance reports the ticket committed and gate blocked, then terminate Worker A. Start/claim with Worker B, release the Python gate/connection, and recover with the same tool_call_id.

Assertions:

```java
assertTrue(acceptance.createTicketAttemptCount() >= 2);
assertEquals(1, acceptance.uniqueTicketCount());
assertEquals(Set.of(acceptance.ticketIds().getFirst()), finalAnswerTicketIds);
assertTrue(workerBEpoch > workerAEpoch);
assertThrows(FencedExecutionException.class, staleWorkerWrite);
```

No direct Java query of `fake_ops.demo_ticket` is allowed; use `/internal/acceptance`.

Run:

```bash
./mvnw -B -Dit.test=IncidentDangerousWindowIT verify
```

Expected GREEN.

- [ ] **Step 11.5: Add failure branches and recovery limits**

Extend scenario tests for RAG no hit, Python unavailable, MCP schema drift, conflicting approval, Python restart with persistent Redis/MySQL, and maximum Java recovery attempts. Each case asserts Java message/tool ledger remains reconstructable and no unsafe duplicate write occurs.

- [ ] **Step 11.6: Verify and commit Task 11**

```bash
./mvnw -B test
./mvnw -B -Pci verify
cd services/agent-capabilities
uv run pytest -m "not quality" -q
git diff --check
```

Commit:

```bash
git add src/main src/test services/agent-capabilities
git commit -m "test: prove recoverable incident workflow"
```

## Plan Completion Gate

- [ ] Python `/mcp` passes initialize/list/call with the official Python client and Java SDK.
- [ ] Java dependency tree contains MCP 2.0.0 with Jackson 2 only.
- [ ] Model-visible `create_ticket` Schema has no `idempotency_key`; actual Python call receives tool_call_id.
- [ ] Same key concurrently creates one row and returns one stable ticket ID; same key/different payload fails closed.
- [ ] Approval survives restart; duplicate decision is idempotent; opposite decision is 409.
- [ ] Whole batch executes nothing while one approval is PENDING.
- [ ] Reject/cancel paths call no remote write and do not mark the Task FAILED.
- [ ] All five committed boundaries recover under deterministic tests.
- [ ] Dangerous window has attempt count at least two and unique ticket count exactly one.
- [ ] Old worker writes are fenced and never overwrite the new epoch.
- [ ] Proceed only then to `2026-07-19-reagent-demo-delivery.md`.
