# ReAgent MCP and Durable Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect ReAgent to a real Streamable HTTP MCP server, persist approval before write tools, and automatically prove that a crash after remote ticket creation but before local bookkeeping still yields one unique ticket.

**Architecture:** Hide official MCP SDK details behind `McpGateway`, adapt discovered schemas into frozen ReAgent Tools with local fail-closed policies, and run an independent Fake Ops MCP application over real HTTP/MySQL. Add `approval_request` plus `WAITING_APPROVAL`, then integrate an all-or-nothing batch barrier into `ToolBatchCoordinator` and drive approve/reject/restart/five-fault scenarios with real MySQL, Redis, MCP protocol, and scripted LLM.

**Tech Stack:** Java 21, Spring Boot 3.3.5, official MCP Java SDK BOM 2.0.0 (`mcp-core` + `mcp-json-jackson2`), Streamable HTTP, Jackson 2, MySQL 8/Flyway, Testcontainers, JUnit 5, OpenTelemetry.

## Global Constraints

- Start only after both Runtime and RAG plan completion gates pass.
- Use the official MCP SDK; do not implement JSON-RPC manually and do not add Spring AI Agent/ChatClient abstractions.
- Use sync MCP client facade with bounded connect/request timeouts; close clients gracefully.
- MCP annotations are hints only. Local `McpToolPolicy` determines visibility, replay class, approval, timeout, and reserved idempotency behavior.
- A task or API body never supplies a URL. Server ID maps to a trusted configured base URL/endpoint.
- `idempotency_key` is never model-visible and model/user arguments cannot override it.
- `WAITING_APPROVAL` releases lease and ends the current SSE run, but is not a task terminal state.
- One assistant tool batch is a barrier: if any call awaits approval, no call in that batch executes.
- Rejection is a normal tool result and Agent continuation, not task failure.
- Only downstream systems that honor the stable idempotency key get the unique-effect proof. Preserve `IN_DOUBT` for unsupported side effects.
- The independent Fake MCP app remains synthetic/demo-only and has no real external integration.
- Commit after each task using the exact message shown.

---

## Task 1: Add the official MCP client and fail-closed ReAgent adapter

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/reagent/mcp/McpGateway.java`
- Create: `src/main/java/com/reagent/mcp/McpRemoteTool.java`
- Create: `src/main/java/com/reagent/mcp/McpCallResult.java`
- Create: `src/main/java/com/reagent/mcp/McpToolPolicy.java`
- Create: `src/main/java/com/reagent/mcp/McpProperties.java`
- Create: `src/main/java/com/reagent/mcp/McpClientManager.java`
- Create: `src/main/java/com/reagent/mcp/McpToolAdapter.java`
- Create: `src/main/java/com/reagent/mcp/McpToolName.java`
- Create: `src/main/java/com/reagent/mcp/McpResultFormatter.java`
- Create: `src/main/java/com/reagent/mcp/McpConfiguration.java`
- Modify: `src/main/java/com/reagent/tool/ToolRegistry.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/mcp/FakeMcpGateway.java`
- Create: `src/test/java/com/reagent/mcp/McpToolNameTest.java`
- Create: `src/test/java/com/reagent/mcp/McpToolAdapterTest.java`
- Create: `src/test/java/com/reagent/mcp/McpClientManagerTest.java`
- Modify: `src/test/java/com/reagent/profile/ToolCatalogResolverTest.java`

**Consumes:** official MCP client API, Tool/Profile contracts, ToolContext run token/idempotency key, schema hasher.

**Produces:** trusted MCP configuration, discover/call port, SDK client lifecycle, namespaced adapter, reserved-key enforcement, dynamic registry registration with duplicate protection.

### Step 1.1: Pin the Jackson-compatible SDK dependency

- [ ] Import the SDK BOM after the OpenTelemetry BOM:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-bom</artifactId>
    <version>2.0.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] Add dependencies `io.modelcontextprotocol.sdk:mcp-core` and `io.modelcontextprotocol.sdk:mcp-json-jackson2`. Do not add artifact `mcp`, which brings Jackson 3.
- [ ] Add a compile smoke test importing `McpClient`, `McpSyncClient`, `HttpClientStreamableHttpTransport`, `CallToolRequest`, and `ListToolsResult` from the 2.0.0 SDK.
- [ ] Run:

```bash
./mvnw -B -DskipTests compile
./mvnw -B dependency:tree | rg "modelcontextprotocol|jackson-(core|databind)"
```

Expected: SDK 2.0.0 resolves, only the project's Jackson 2 line is used by the adapter, and no Spring AI dependency appears.

### Step 1.2: Write trusted configuration and name-mapping tests

- [ ] `McpProperties` must bind configured servers with ID, base URL, endpoint, required flag, connect timeout, request timeout, result-size limit, and a tool-policy map.
- [ ] `McpToolNameTest` covers:
  - `ops + query_metrics` → `mcp_ops_query_metrics`;
  - illegal punctuation is normalized only by a documented lowercase underscore rule;
  - empty, over-64-character, duplicate normalized names, and names colliding with local Tools are rejected;
  - input server/tool names are retained separately for actual protocol calls.
- [ ] Assert configuration validation rejects non-HTTP(S) URL, user-info, fragment, missing endpoint, unbounded timeout, and unknown profile server IDs. Loopback/private URLs are allowed because this is trusted deploy configuration, not task input.
- [ ] Run:

```bash
./mvnw -B -Dtest=McpToolNameTest,McpClientManagerTest test
```

Expected red: MCP types/config missing.

- [ ] Implement immutable properties and name conversion; expected focused tests green.

### Step 1.3: Write adapter safety tests before implementation

- [ ] Build a `FakeMcpGateway` that records server/tool/arguments and returns scripted `McpCallResult` values.
- [ ] In `McpToolAdapterTest`, cover:
  - remote `query_metrics` maps to model-visible namespaced Tool and preserves object JSON Schema;
  - remote non-object/oversized/recursive-invalid schema is rejected at discovery;
  - configured read tool declares `READ_ONLY + NONE`;
  - configured ticket tool declares `IDEMPOTENT + REQUIRE_APPROVAL`;
  - missing policy resolves to `SIDE_EFFECTFUL + REQUIRE_APPROVAL` and is not allowlisted;
  - `idempotency_key` is removed from visible properties/required list;
  - a model-supplied reserved field is rejected before gateway call;
  - runtime key equals exact `tool_call_id` and is injected only for configured downstream-idempotent tools;
  - null runtime key on a required-idempotency write fails closed;
  - `isError`, timeout, protocol error, non-text content, and oversized result become bounded structured observations;
  - API keys/headers/full sensitive arguments are absent from result text and spans.
- [ ] Run:

```bash
./mvnw -B -Dtest=McpToolAdapterTest test
```

Expected red: adapter missing.

### Step 1.4: Implement the narrow adapter

- [ ] Implement records:

```java
public record McpRemoteTool(
        String serverId,
        String remoteName,
        String description,
        Map<String, Object> inputSchema
) {}

public record McpCallResult(boolean error, List<String> text, String errorCode) {}

public record McpToolPolicy(
        boolean exposed,
        IdempotencyClass idempotencyClass,
        ApprovalPolicy approvalPolicy,
        Duration timeout,
        boolean injectIdempotencyKey
) {}
```

- [ ] `McpToolAdapter.parameterSchema()` returns a defensive deep copy without the reserved field. `execute` parses an object, validates byte/depth/property limits, injects the runtime key, calls the remote name through `McpGateway`, and formats bounded JSON.
- [ ] Add `mcp.call_tool` span with task/profile/worker/epoch/server/tool/tool-call/replay fields but no secrets/full arguments.
- [ ] Rerun adapter tests; expected green.

### Step 1.5: Implement official sync client lifecycle

- [ ] `McpClientManager` constructs one client per configured server with the official sequence:

```java
McpTransport transport = HttpClientStreamableHttpTransport
        .builder(server.baseUrl())
        .endpoint(server.endpoint())
        .build();
McpSyncClient client = McpClient.sync(transport)
        .requestTimeout(server.requestTimeout())
        .build();
client.initialize();
ListToolsResult tools = client.listTools();
```

- [ ] Convert official SDK tool schema into `McpRemoteTool`, validate/canonical-hash once, and cache the frozen discovery result.
- [ ] Call with `CallToolRequest.builder(remoteName).arguments(arguments).build()` and convert all returned content/error flags without leaking SDK types outside this class.
- [ ] On reconnect, rediscover and require the current schema hash to equal any task snapshot before execution; return a structured `schema_drift` result and do not call the tool if unequal.
- [ ] When MCP is enabled, required-server initialize/discovery failure aborts application startup with a bounded diagnostic. Optional servers remain disabled and are not allowlisted. During reconnect, readiness is DOWN until the required server is healthy again.
- [ ] Implement `close()`/shutdown to call `closeGracefully()` exactly once for every built client.
- [ ] Add `mcp.initialize` and `mcp.list_tools` spans.
- [ ] Unit-test lifecycle through a small SDK-client factory seam; expected green.

### Step 1.6: Register discovered adapters safely

- [ ] Add synchronized/startup-only `ToolRegistry.registerDynamic(Collection<Tool>)`; reuse duplicate/illegal name checks and never replace an existing Tool.
- [ ] `McpConfiguration` discovers required servers, maps local policies, registers adapters, then marks MCP discovery ready. Task snapshots created afterward can include them.
- [ ] Add configured policies for `ops.query_metrics`, `ops.search_logs`, and `ops.create_ticket`; leave URLs disabled until the Fake server plan supplies test/demo configuration.
- [ ] Run:

```bash
./mvnw -B -Dtest='com.reagent.mcp.*Test',ToolCatalogResolverTest test
./mvnw -B test
git diff --check
```

Expected: all unit tests green, no SDK types outside `McpClientManager`/configuration.

- [ ] Commit:

```bash
git add pom.xml src/main/java/com/reagent/mcp src/main/java/com/reagent/tool/ToolRegistry.java src/main/java/com/reagent/obs/Trace.java src/main/resources/application.yml src/test/java/com/reagent/mcp src/test/java/com/reagent/profile
git commit -m "feat: add official MCP client adapters"
```

---

## Task 2: Build the independent Fake Ops MCP server and protocol evidence

**Files:**

- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V3__fake_ops_ticket.sql`
- Create: `src/main/java/com/reagentfake/FakeOpsMcpApplication.java`
- Create: `src/main/java/com/reagentfake/config/FakeMcpServerConfiguration.java`
- Create: `src/main/java/com/reagentfake/config/FakeOpsProperties.java`
- Create: `src/main/java/com/reagentfake/tool/OpsToolSchemas.java`
- Create: `src/main/java/com/reagentfake/tool/OpsToolHandlers.java`
- Create: `src/main/java/com/reagentfake/ticket/DemoTicketEntity.java`
- Create: `src/main/java/com/reagentfake/ticket/DemoTicketRepository.java`
- Create: `src/main/java/com/reagentfake/ticket/DemoTicketService.java`
- Create: `src/main/java/com/reagentfake/chaos/FakeMcpFaultGate.java`
- Create: `src/main/java/com/reagentfake/api/FakeMcpAcceptanceController.java`
- Create: `src/main/resources/application-fake-mcp.yml`
- Create: `src/test/java/com/reagentfake/FakeMcpServerSdkTest.java`
- Create: `src/test/java/com/reagentfake/FakeOpsTicketIT.java`
- Create: `src/test/java/com/reagent/mcp/McpProtocolIT.java`
- Modify: `src/test/java/com/reagent/persist/SchemaMigrationIT.java`

**Consumes:** official SDK server API, MySQL/Flyway integration support, MCP client manager.

**Produces:** separately executable Fake MCP Boot jar, real `/mcp` Streamable HTTP endpoint, deterministic metrics/logs, idempotent MySQL ticket write, chaos latch and read-only acceptance endpoint, protocol contract tests.

### Step 2.1: Add V3 ticket migration test first

- [ ] Extend `SchemaMigrationIT` fresh/legacy comparison to V3 and assert table/constraints:
  - unique `idempotency_key`;
  - unique `ticket_id`;
  - bounded title/severity;
  - MEDIUMTEXT evidence;
  - `attempt_count`, created/updated timestamps.
- [ ] Run:

```bash
./mvnw -B -Dit.test=SchemaMigrationIT verify
```

Expected red: V3 missing.

- [ ] Add:

```sql
CREATE TABLE demo_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(255) NOT NULL,
    ticket_id VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    evidence MEDIUMTEXT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_demo_ticket_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_demo_ticket_ticket_id UNIQUE (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] Rerun migration IT; expected green.

### Step 2.2: Write idempotent ticket transaction tests

- [ ] `FakeOpsTicketIT` starts a real MySQL container and constructs the fake app's repository/service only.
- [ ] Test sequential duplicate key returns same ticket ID, row count one, attempt count two.
- [ ] Test two concurrent inserts released by a barrier return the same ticket ID and row count one.
- [ ] Test different keys create different tickets.
- [ ] Test unsupported severity, blank/oversized title, forged/missing key, and oversized evidence fail before insert.
- [ ] Run:

```bash
./mvnw -B -Dit.test=FakeOpsTicketIT verify
```

Expected red: fake ticket types missing.

### Step 2.3: Implement insert-or-read semantics

- [ ] Generate stable ticket ID as `OPS-` plus the first 12 uppercase hex characters of SHA-256(idempotency key); retain the unique DB constraint as collision defense.
- [ ] Use one native MySQL statement inside a transaction:

```sql
INSERT INTO demo_ticket
    (idempotency_key, ticket_id, title, severity, evidence, attempt_count, created_at, updated_at)
VALUES
    (:key, :ticketId, :title, :severity, :evidence, 1, :now, :now)
ON DUPLICATE KEY UPDATE
    attempt_count = attempt_count + 1,
    updated_at = VALUES(updated_at);
```

- [ ] Select by idempotency key after the statement and return the stored row. A repeat must not overwrite original title/severity/evidence.
- [ ] Inject `Clock`; no entity/service `Instant.now()`.
- [ ] Rerun ticket IT; expected green.

### Step 2.4: Compile the official Streamable HTTP server adapter first

- [ ] Keep the fake main class under `com.reagentfake`, annotated to scan only that package and its JPA entities/repositories; the main `com.reagent` app must not discover fake-server beans.
- [ ] In `FakeMcpServerSdkTest`, use the Jackson 2 mapper `io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper` and instantiate the official servlet provider exactly through:

```java
HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .mcpEndpoint("/mcp")
                .build();
```

- [ ] Register it as `new ServletRegistrationBean<>(transportProvider)` and build the sync server with `McpServer.sync(transportProvider).serverInfo("reagent-fake-ops", "1.0.0").capabilities(ServerCapabilities.builder().tools(true).build()).build()`.
- [ ] Register a temporary `SyncToolSpecification` using the SDK `Tool.builder(name, schema)` and `CallToolResult.builder()` APIs, then close the sync server.
- [ ] Run:

```bash
./mvnw -B -Dtest=FakeMcpServerSdkTest test
```

Expected green once the SDK 2.0 servlet transport and Jackson 2 mapper compile. Keep all SDK bootstrap code inside `FakeMcpServerConfiguration`; do not switch to legacy SSE.

### Step 2.5: Register the three deterministic MCP tools

- [ ] `query_metrics` input: service, start, end. Return checkout error rate `14.2%`, p95 `1.8s`, active connections `40/40`, pending `27` for the preset window.
- [ ] `search_logs` input: service, query, start, end, limit. Return bounded entries including `HikariPool-1 - Connection is not available` and request correlation IDs.
- [ ] `create_ticket` input: title, severity (`P1|P2|P3`), evidence array/string, and required `idempotency_key`. Call `DemoTicketService` and return stored ticket ID plus `deduplicated = attemptCount > 1`.
- [ ] Expose exact JSON Schemas through official SDK tool specs. Mark protocol `isError` for validation/business failures; local ReAgent policy still owns approval/replay decisions.
- [ ] Start official sync server on Streamable HTTP `/mcp`; no deprecated SSE endpoint.

### Step 2.6: Add acceptance and controlled response gate

- [ ] Under `demo-chaos` only, `FakeMcpFaultGate` can block the `create_ticket` response after the DB transaction commits. Provide arm/status/release methods and bounded automatic release to prevent a permanently wedged demo.
- [ ] Under `test`/`demo-chaos` only, expose read-only endpoints:
  - `GET /__test/tickets` → unique count and rows without secrets;
  - `GET /__test/tickets/{idempotencyKey}` → ticket ID/attempt count;
  - `GET /__test/fault` → armed/reached state;
  - `POST /__test/fault/release` → release current response gate.
- [ ] No endpoint can mutate ticket facts or enable chaos in the normal profile.

### Step 2.7: Prove initialize/list/call over real HTTP

- [ ] `McpProtocolIT` starts `FakeOpsMcpApplication` on a random port with the shared MySQL container, configures `McpClientManager`, and calls real `/mcp`.
- [ ] Assert initialize succeeds, listTools returns exactly the three remote names/schemas, metrics/log calls return preset facts, ticket call returns a ticket, duplicate key returns same ID with unique count one.
- [ ] Add cases for server timeout, MCP `isError`, unavailable endpoint, result size cap, and reconnect schema drift.
- [ ] Run:

```bash
./mvnw -B -Dit.test=McpProtocolIT,FakeOpsTicketIT verify
```

Expected: all green with real Streamable HTTP.

### Step 2.8: Produce an independent executable jar

- [ ] Configure a second `spring-boot-maven-plugin:repackage` execution with classifier `fake-mcp` and main class `com.reagentfake.FakeOpsMcpApplication`; retain the normal ReAgent executable jar.
- [ ] Run:

```bash
./mvnw -B -DskipTests package
jar tf target/reagent-0.1.0-SNAPSHOT-fake-mcp.jar | rg "com/reagentfake/FakeOpsMcpApplication.class"
unzip -p target/reagent-0.1.0-SNAPSHOT-fake-mcp.jar META-INF/MANIFEST.MF | rg "Start-Class: com.reagentfake.FakeOpsMcpApplication"
```

Expected: class and Start-Class match; normal jar remains present.

- [ ] Run full tests and commit:

```bash
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
git add pom.xml src/main/resources src/main/java/com/reagentfake src/test/java/com/reagentfake src/test/java/com/reagent/mcp src/test/java/com/reagent/persist
git commit -m "feat: add idempotent Fake Ops MCP server"
```

---

## Task 3: Persist approval and expose approve/reject/cancel APIs

**Files:**

- Create: `src/main/resources/db/migration/V4__durable_approval.sql`
- Create: `src/main/java/com/reagent/approval/ApprovalStatus.java`
- Create: `src/main/java/com/reagent/approval/ApprovalDecision.java`
- Create: `src/main/java/com/reagent/approval/ApprovalRequestEntity.java`
- Create: `src/main/java/com/reagent/approval/ApprovalRequestRepository.java`
- Create: `src/main/java/com/reagent/approval/ApprovalView.java`
- Create: `src/main/java/com/reagent/approval/ApprovalConflictException.java`
- Create: `src/main/java/com/reagent/approval/ApprovalService.java`
- Create: `src/main/java/com/reagent/approval/ApprovalResumeDispatcher.java`
- Create: `src/main/java/com/reagent/api/ApprovalController.java`
- Modify: `src/main/java/com/reagent/api/ApiExceptionHandler.java`
- Modify: `src/main/java/com/reagent/persist/TaskStatus.java`
- Modify: `src/main/java/com/reagent/persist/TaskEntity.java`
- Modify: `src/main/java/com/reagent/persist/ToolCallStatus.java`
- Modify: `src/main/java/com/reagent/persist/ToolCallEntity.java`
- Modify: `src/main/java/com/reagent/persist/StateStore.java`
- Modify: `src/main/java/com/reagent/persist/TaskRepository.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/main/java/com/reagent/api/TaskController.java`
- Modify: `src/main/java/com/reagent/core/FailoverScanner.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Create: `src/test/java/com/reagent/approval/ApprovalServiceIT.java`
- Create: `src/test/java/com/reagent/api/ApprovalControllerTest.java`
- Modify: `src/test/java/com/reagent/persist/SchemaMigrationIT.java`
- Modify: `src/test/java/com/reagent/persist/ToolCallEntityTest.java`
- Modify: `src/test/java/com/reagent/stream/TaskEventBusReplayTest.java`

**Consumes:** TaskRunToken/row lock, assistant batch sequence, failover dispatcher, tool policy/catalog.

**Produces:** V4 approval audit table, WAITING state/REJECTED ledger state, idempotent decision API, owner-null resume fallback, atomic waiting-task cancellation.

### Step 3.1: Write V4 migration tests first

- [ ] Extend `SchemaMigrationIT` to compare fresh and legacy databases at V4 and assert `approval_request` columns/indexes plus existing data retention.
- [ ] Run migration IT; expected red.
- [ ] Add exact DDL:

```sql
CREATE TABLE approval_request (
    tool_call_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    assistant_message_seq INT NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    arguments_snapshot MEDIUMTEXT NOT NULL,
    reason MEDIUMTEXT NULL,
    citation_summary MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    decided_by VARCHAR(128) NULL,
    decision_reason VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tool_call_id),
    INDEX idx_approval_task_batch (task_id, assistant_message_seq),
    INDEX idx_approval_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] Rerun migration IT; expected green.

### Step 3.2: Write approval transaction tests before service code

- [ ] In `ApprovalServiceIT`, seed task/token/catalog/tool-call batches through StateStore, not direct table shortcuts except fixture setup.
- [ ] Cover:
  - entering wait creates one request per approval-required call, keeps argument snapshot, transitions task to WAITING, releases owner/lease;
  - repeated request creation is idempotent by tool-call ID;
  - same decision twice returns current state;
  - opposite decision after first returns `ApprovalConflictException` and preserves first fact;
  - decision on terminal/cancelled task is rejected;
  - pending decision survives closing/recreating service/context;
  - last decision changes task to RUNNING with owner null;
  - scanner's recoverable query includes that owner-null task;
  - rejection alone does not call a Tool/MCP gateway;
  - all timestamps come from MutableClock.
- [ ] Run:

```bash
./mvnw -B -Dit.test=ApprovalServiceIT verify
```

Expected red: approval model/service/status missing.

### Step 3.3: Implement approval entities and guarded wait transition

- [ ] Add `WAITING_APPROVAL` and `REJECTED` enum values; retain VARCHAR mappings.
- [ ] Add TaskEntity methods `waitForApproval(now)` and `makeRecoverable(now)`; waiting releases lease, resumption sets RUNNING with owner null.
- [ ] Add JPA `@Version` to approval entity and conditional repository updates where status is PENDING.
- [ ] Implement:

```java
public WaitResult requestBatch(
        TaskRunToken token,
        int assistantMessageSeq,
        List<ToolCall> calls,
        TaskToolCatalog catalog,
        String reason,
        String citationSummary)

public DecisionResult decide(
        String taskId,
        String toolCallId,
        ApprovalDecision decision,
        String decidedBy,
        String reason)
```

- [ ] `requestBatch` locks/validates the token and writes requests plus WAITING state in one transaction. Clamp and JSON-validate snapshots/reason/citations.
- [ ] `decide` is a control-plane transaction that locks task/request, applies exact idempotent/conflict rules, and makes the task owner-null RUNNING only when every approval in the assistant batch is decided.
- [ ] Publish approval events after commit; REST state remains authoritative if publication is interrupted.
- [ ] Rerun service IT; expected green.

### Step 3.4: Add post-commit resume with crash-safe fallback

- [ ] `ApprovalResumeDispatcher` calls `FaultInjector.hit(AFTER_APPROVAL_DECIDED_BEFORE_RESUME, ...)` after the decision transaction commits and before `FailoverService.submit(taskId)`.
- [ ] A normal decision submits immediately. If injected crash prevents submit, `FailoverScanner.findRecoverable` discovers RUNNING/owner-null and submits later.
- [ ] Add an IT that injects the fault, asserts committed APPROVED + RUNNING/owner-null, then invokes scanner and observes one claim/resume submission.
- [ ] Do not leave a transaction open while starting a worker thread.

### Step 3.5: Implement atomic cancellation of waiting tasks

- [ ] Add `ApprovalService.cancelWaiting(taskId)` that locks the WAITING task, rejects every PENDING approval with reason `task_cancelled`, marks every unexecuted PENDING/IN_PROGRESS ledger row REJECTED, appends one synthetic tool result per call in original order, and sets task CANCELLED in one transaction.
- [ ] An already APPROVED approval remains APPROVED as an audit fact, but its not-yet-executed ledger call is REJECTED by cancellation.
- [ ] Assert FakeMcpGateway call count remains zero.
- [ ] Update `TaskController.cancel`: route WAITING directly to this service; retain existing running-task control behavior.
- [ ] Add cancellation cases to `ApprovalServiceIT`; expected green.

### Step 3.6: Add API DTOs, conflicts, and events

- [ ] `GET /api/tasks/{taskId}/approvals` returns ordered `ApprovalView` records with argument/reason/citation bounds and audit timestamps.
- [ ] `POST /api/tasks/{taskId}/approvals/{toolCallId}/decision` accepts:

```json
{
  "decision": "APPROVE",
  "decidedBy": "demo-user",
  "reason": "Evidence supports P1 escalation"
}
```

- [ ] Validate body sizes/enums; return 200 for first/same decision, 409 for opposite/terminal conflict, 404 for mismatched task/tool call.
- [ ] Add events `APPROVAL_REQUIRED`, `WAITING_APPROVAL`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`; `WAITING_APPROVAL` ends the current SSE run in `TaskEvent.isTerminal()` without making task terminal.
- [ ] Add approval wait/decision spans with IDs/status only.
- [ ] `ApprovalControllerTest` covers response shapes/statuses and does not rely on MySQL.
- [ ] Run:

```bash
./mvnw -B -Dtest=ApprovalControllerTest,TaskEventBusReplayTest test
./mvnw -B -Dit.test=ApprovalServiceIT,SchemaMigrationIT verify
git diff --check
```

Expected: all green.

- [ ] Commit:

```bash
git add src/main/resources/db/migration/V4__durable_approval.sql src/main/java src/test/java
git commit -m "feat: add durable tool approval workflow"
```

---

## Task 4: Enforce the whole-batch barrier and prove the incident crash matrix

**Files:**

- Create: `src/main/java/com/reagent/approval/ApprovalGate.java`
- Create: `src/main/java/com/reagent/approval/ApprovalBatchPlan.java`
- Modify: `src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/tool/ToolExecutor.java`
- Modify: `src/main/java/com/reagent/mcp/McpToolAdapter.java`
- Modify: `src/main/java/com/reagent/profile/AgentProfileProperties.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/approval/ToolBatchApprovalTest.java`
- Create: `src/test/java/com/reagent/scenario/IncidentScenarioFixture.java`
- Create: `src/test/java/com/reagent/scenario/IncidentWorkflowIT.java`
- Create: `src/test/java/com/reagent/scenario/IncidentCrashRecoveryIT.java`
- Create: `src/test/java/com/reagent/scenario/McpSchemaDriftRecoveryIT.java`

**Consumes:** coordinator/fault hooks, RAG tool, Fake MCP HTTP server, approval service, scripted LLM, dual-worker infrastructure.

**Produces:** all-or-nothing approval barrier, rejected synthetic results, incident profile with all tools, crash-propagation correctness, full happy/reject/restart/five-window/unique-ticket evidence.

### Step 4.1: Specify whole-batch semantics in unit tests

- [ ] In `ToolBatchApprovalTest`, create a single assistant batch containing read-only metrics plus approval-required ticket.
- [ ] Assert first `process` creates approval and returns WAITING with zero executions for both calls.
- [ ] Assert PENDING approval on a resumed call returns WAITING without duplicate request/event.
- [ ] After approval, assert both executable calls run and results persist in original order.
- [ ] After rejection, assert read-only call runs only after the barrier resolves, ticket call does not run, ticket ledger is REJECTED, synthetic tool result pairs with original ID, and disposition is EXECUTED so Agent continues.
- [ ] Assert mixed multiple required approvals wait until all are decided.
- [ ] Run:

```bash
./mvnw -B -Dtest=ToolBatchApprovalTest test
```

Expected red: coordinator does not invoke approval gate.

### Step 4.2: Implement approval gate and coordinator integration

- [ ] `ApprovalGate.plan(...)` loads batch sequence from ledger and returns per-call state `EXECUTE`, `REJECT`, or `WAIT`; it never executes tools.
- [ ] If any call is WAIT, `DefaultToolBatchCoordinator` invokes `requestBatch`, publishes waiting events, and returns WAITING before any `markInProgress`.
- [ ] If fully decided, coordinator writes REJECTED synthetic results for rejected calls, then applies existing recovery classification/execution to executable calls, finally persists all observations in original order.
- [ ] Add `StateStore.recordRejectedResult(token, call, result)` with row-lock fencing and `REJECTED + tool message` atomicity.
- [ ] AgentRunner ends current run on WAITING without workspace commit, FAILED, or extra LLM call.
- [ ] Rerun batch tests; expected green.

### Step 4.3: Make injected worker crashes escape ToolExecutor

- [ ] In `McpToolAdapter`, hit `AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT` only after successful `callTool` result arrives and before returning to ToolExecutor.
- [ ] Preserve the TaskRunToken/tool-call ID from `ToolContext` in `FaultContext`.
- [ ] `ToolExecutor` must rethrow `InjectedWorkerCrashException` instead of converting it to an error observation. Concurrent execution must unwrap that exception from `ExecutionException`, cancel sibling futures, and rethrow.
- [ ] Coordinator/AgentRunner must leave the ledger IN_PROGRESS and task RUNNING for injected crash.
- [ ] Add a focused test proving a normal MCP exception becomes an observation while injected worker crash escapes and does not record DONE.

### Step 4.4: Assemble the deterministic incident fixture

- [ ] `IncidentScenarioFixture` starts shared MySQL/Redis and a real random-port Fake MCP app; builds Worker A/B StateStores with distinct identities and MutableClock; uses real RedisKnowledgeIndex with FakeEmbedding; uses real MCP client/adapter and ApprovalService.
- [ ] Scripted LLM sequence:
  1. call `search_knowledge` for checkout pool exhaustion;
  2. call `mcp_ops_query_metrics` and `mcp_ops_search_logs` in one batch;
  3. call `mcp_ops_create_ticket` with P1 title/evidence but no idempotency field;
  4. final answer containing returned chunk ID, metrics/log facts, and returned ticket ID.
- [ ] Assert each turn sees only the frozen incident tool list and exact accumulated tool messages.
- [ ] Enable incident profile only when RAG ready and required MCP discovery ready; coding profile remains unchanged.

### Step 4.5: Prove happy, reject, and restart flows

- [ ] `IncidentWorkflowIT.approvesAndCompletesWithCitationAndUniqueTicket`:
  - submit task; await WAITING by database/event latch;
  - assert RAG/metrics/log results committed and no ticket yet;
  - approve; recover; assert COMPLETED final answer contains a real chunk ID and ticket ID;
  - assert Fake server unique count one and ticket attempt count one.
- [ ] `rejectsTicketAndCompletesWithoutMcpWrite`:
  - reject approval; recover; assert REJECTED result and final explanation;
  - assert create_ticket protocol call count zero and unique tickets zero.
- [ ] `waitingApprovalSurvivesApplicationRestart`:
  - close Worker A services/clients after WAITING;
  - recreate approval/runtime components from same DB/Redis; approve and complete.
- [ ] `approvalCommitBeforeResumeCrashFallsBackToScanner`:
  - inject post-commit crash; assert owner-null RUNNING; invoke scanner/Worker B and complete once.
- [ ] Run:

```bash
./mvnw -B -Dit.test=IncidentWorkflowIT verify
```

Expected: all green, no real LLM/API key.

### Step 4.6: Prove all five fault windows

- [ ] Parameterize `IncidentCrashRecoveryIT` by every `FaultPoint`, resetting isolated task/ticket facts per case.
- [ ] Assertions by point:
  - assistant persisted before approval: recovery creates exactly one approval and executes nothing first;
  - approval decided before resume: scanner claims owner-null task;
  - ledger IN_PROGRESS before remote call: replay uses same call ID/key and creates one ticket;
  - remote ticket committed before local result: Worker B calls same key, receives same ticket, unique count one, attempt count two;
  - result persisted before final: recovery does not call ticket again and only requests final LLM turn.
- [ ] In every case, resume Worker A after Worker B claim and force its next write; assert fenced exception, no stale message/event/result, and no FAILED transition.
- [ ] Advance MutableClock instead of sleeping for lease expiry. Use latches/fault gate to know exact commit boundary.
- [ ] Run:

```bash
./mvnw -B -Dit.test=IncidentCrashRecoveryIT verify
```

Expected: five parameter cases green; dangerous window reports one unique ticket.

### Step 4.7: Prove schema drift and unsupported side effects stay closed

- [ ] `McpSchemaDriftRecoveryIT` persists a task snapshot, restarts a fake server/client with changed create-ticket schema, and asserts no remote call, task FAILED with bounded code `tool_schema_drift`, and a durable FAILED event from the winning token.
- [ ] Add a SIDE_EFFECTFUL MCP test without downstream idempotency: crash at IN_PROGRESS, recover, assert IN_DOUBT and zero automatic replay.
- [ ] Run:

```bash
./mvnw -B -Dit.test=McpSchemaDriftRecoveryIT,IncidentCrashRecoveryIT verify
```

Expected: green and fail-closed.

### Step 4.8: Final plan verification and commit

- [ ] Run:

```bash
./mvnw -B clean test
./mvnw -B -Pci verify
rg -n "idempotency_key" src/main/java/com/reagent | sed -n '1,160p'
git diff --check
```

Expected: reserved field occurs only inside MCP configuration/adapter tests and is absent from model-visible profile snapshots; all tests green with no Docker skip.

- [ ] Commit:

```bash
git add src/main/java src/main/resources src/test/java
git commit -m "feat: close incident workflow with approval recovery"
```

## Plan 3 Completion Gate

- [ ] Official MCP client and independent server complete initialize/list/call over Streamable HTTP.
- [ ] Jackson 3 and Spring AI Agent dependencies are absent.
- [ ] Metrics/logs are read-only; ticket is `IDEMPOTENT + REQUIRE_APPROVAL`.
- [ ] Model cannot see or forge `idempotency_key`.
- [ ] Approval survives restart; same decisions are idempotent; opposite decisions conflict.
- [ ] Whole assistant batch performs zero tools while any approval is pending.
- [ ] Reject path completes with zero ticket calls.
- [ ] All five fault windows pass deterministically.
- [ ] Dangerous remote-success/local-crash window returns the same ticket with one unique row.
- [ ] Old Worker writes/events are fenced after takeover.
- [ ] Unsupported side effects remain IN_DOUBT and are not replayed.
- [ ] Proceed only then to `2026-07-18-reagent-demo-acceptance.md`.
