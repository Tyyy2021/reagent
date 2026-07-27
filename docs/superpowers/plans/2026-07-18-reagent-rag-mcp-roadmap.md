# ReAgent RAG + MCP Interview Edition Implementation Plan

> **已部分替代：** Runtime Task 1–4 仍按本计划套件中的 `2026-07-18-reagent-runtime-evidence.md` 执行；Task 5–14 已由 `2026-07-19-reagent-java-python-hybrid-roadmap.md` 及其三个子计划替代，不得继续按本文件的 Java-only RAG/Fake MCP 路线实施。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the approved ReAgent design into a reproducible interview project that proves runtime recovery with automation and demonstrates one complete RAG → MCP → durable approval → idempotent ticket workflow.

**Architecture:** Keep ReAgent as the only Agent control plane. First harden its persistence, fencing, profile, and testing seams; then add RAG as a read-only tool; then add MCP tools and durable approval through the same ledger; finally package the static console, compose stack, fault scripts, CI evidence, and documentation.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Maven, MySQL 8, Redis 8 Search/HNSW and Streams, Flyway, Testcontainers, official MCP Java SDK 2.0.0 with Jackson 2, LangChain4j quantized all-MiniLM-L6-v2 ONNX model, OpenTelemetry, static HTML/CSS/vanilla JavaScript, Docker Compose.

## Global Constraints

- The approved specification at `docs/superpowers/specs/2026-07-18-reagent-rag-mcp-design.md` is authoritative. If an implementation choice conflicts with it, stop that task and amend the plan/spec explicitly before coding.
- Preserve `message` as the only recoverable LLM-context source and `tool_call` as the execution/idempotency ledger. Do not add a second Agent loop or a second conversation store.
- Preserve the existing Coding Agent API: omitted `profile` means `coding`; existing local tools keep `ApprovalPolicy.NONE`.
- Use test-driven development for every behavior: run the named test and see the expected failure before implementation; then make the smallest production change; then rerun focused and regression commands.
- Unit tests belong to Surefire and must not require Docker. Docker/Testcontainers tests end in `IT` and belong to Failsafe. Under `-Pci`, unavailable Docker is a hard failure, never an assumption skip.
- CI and deterministic acceptance use scripted Fake LLM, deterministic Fake Embedding, and Fake Ops MCP. A paid LLM is only a documented manual smoke path.
- All runtime-originated durable writes carry `TaskRunToken(taskId, workerId, leaseEpoch)`. An old token throws `FencedExecutionException`; it must never turn the task into `FAILED` or overwrite the new worker.
- `IdempotencyClass` and `ApprovalPolicy` stay independent. A write can be `IDEMPOTENT + REQUIRE_APPROVAL`; a missing MCP policy fails closed as `SIDE_EFFECTFUL + REQUIRE_APPROVAL` and is not automatically allowlisted.
- No external RAG/MCP text reaches the page through `innerHTML`. Build DOM nodes and assign untrusted text with `textContent`.
- Normal demo startup never injects random failure. Only tests and `scripts/demo-failover.sh` enable controlled fault points.
- Do not update README claims or test counts until the final plan has produced the corresponding executable evidence.
- After each task: run `git diff --check`, inspect `git status --short`, and commit only the task's intended files with the specified message.

---

## Plan Suite and Required Order

Execute these files in order. Do not begin a later plan until the preceding plan's final verification block passes.

1. [`2026-07-18-reagent-runtime-evidence.md`](./2026-07-18-reagent-runtime-evidence.md)
   - Adds Flyway and a real integration-test lifecycle.
   - Makes all runtime persistence token-fenced.
   - Freezes profiles/tool catalogs per task.
   - Extracts the tool-batch seam and proves AgentRunner, MySQL, Redis Streams, dual-worker claim, and crash recovery.
2. [`2026-07-18-reagent-rag.md`](./2026-07-18-reagent-rag.md)
   - Adds stable document chunks/citations and deterministic search ports.
   - Adds Redis 8 HNSW storage and index initialization.
   - Adds quantized MiniLM, offline retrieval quality evidence, and `search_knowledge`.
3. [`2026-07-18-reagent-mcp-approval.md`](./2026-07-18-reagent-mcp-approval.md)
   - Adds the official MCP client/adapter and independent Fake Ops MCP server.
   - Adds durable approval and the whole-batch execution barrier.
   - Proves approve, reject, restart, five crash windows, schema drift, and unique-ticket recovery.
4. [`2026-07-18-reagent-demo-acceptance.md`](./2026-07-18-reagent-demo-acceptance.md)
   - Adds final acceptance/readiness evidence and observability.
   - Adds the static console.
   - Adds Docker images, compose, one-command scripts, three CI jobs, and final README.

The suite has 14 implementation tasks. Each task ends in a focused commit so a reviewer can bisect the finished branch without mixing infrastructure, behavior, and presentation.

## Shared Contracts

These names and shapes are owned by the first plan and consumed unchanged by later plans. If compilation forces a signature adjustment, update all five plan files in the same documentation commit before implementation continues.

### Runtime ownership

```java
package com.reagent.core;

public record TaskRunToken(String taskId, String workerId, long leaseEpoch) {
    public TaskRunToken {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId is required");
        if (leaseEpoch < 0) throw new IllegalArgumentException("leaseEpoch must be non-negative");
    }
}
```

`StateStore.claim(String taskId)` returns `Optional<TaskRunToken>`. Every run-path mutator receives that record, including assistant registration, ledger state changes, tool results, rejected results, wait transitions, terminal task transitions, recovery counters, and durable event appends.

`ToolContext` retains compatibility constructors for isolated Tool tests but AgentRunner creates it from the current `TaskRunToken`. `forCall(toolCallId)` preserves that token and binds `idempotencyKey`; RAG/MCP adapters can therefore publish fenced events/fault context without a ThreadLocal.

### Tool safety and frozen catalog

```java
package com.reagent.tool;

public enum ApprovalPolicy {
    NONE,
    REQUIRE_APPROVAL
}
```

```java
package com.reagent.profile;

public record ToolSnapshot(
        String name,
        String displayName,
        String description,
        Map<String, Object> parameterSchema,
        String schemaHash,
        IdempotencyClass idempotencyClass,
        ApprovalPolicy approvalPolicy,
        long timeoutMs,
        String provider
) {}

public record TaskProfileSnapshot(
        String profileId,
        String profileVersion,
        String systemPrompt,
        String systemPromptHash,
        String knowledgeBaseId,
        String knowledgeIndexVersion,
        List<String> mcpServerIds,
        List<ToolSnapshot> tools
) {}
```

`TaskToolCatalog` is an in-memory resolved view of the persisted snapshots. Both `LlmClient.chatStream(..., catalog.toOpenAiSpec(), ...)` and `ToolExecutor`/replay lookup use that same catalog. Resolution checks current tool name plus canonical schema hash against the snapshot and fails on drift.

### Tool batch

```java
package com.reagent.core;

public enum BatchDisposition {
    EXECUTED,
    WAITING_APPROVAL
}
```

```java
public interface ToolBatchCoordinator {
    BatchDisposition process(
            TaskRunToken token,
            ToolContext toolContext,
            Context context,
            TaskToolCatalog catalog,
            List<ToolCall> calls
    );
}
```

The coordinator owns recovery classification, approval barrier, `IN_PROGRESS` fencing, concurrent execution, ordered ledger/message persistence, synthetic rejected results, tool events, and fault hooks. `AgentRunner` owns only the step loop and reacts to `WAITING_APPROVAL` by ending the current run without marking a terminal task state.

### Fault injection

```java
package com.reagent.core;

public enum FaultPoint {
    AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL,
    AFTER_APPROVAL_DECIDED_BEFORE_RESUME,
    AFTER_TOOL_MARKED_IN_PROGRESS,
    AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
    AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER
}

@FunctionalInterface
public interface FaultInjector {
    void hit(FaultPoint point, FaultContext context);

    static FaultInjector none() {
        return (point, context) -> { };
    }
}
```

The production bean is always no-op unless an explicit `test` or `demo-chaos` profile supplies a controlled implementation. Tests coordinate with latches and a mutable clock; they do not guess timing with long sleeps.

### RAG ports

```java
package com.reagent.rag;

public interface EmbeddingPort {
    int dimensions();
    float[] embed(String text);
    List<float[]> embedAll(List<String> texts);
}

public interface KnowledgeIndex {
    void replace(String knowledgeBaseId, String indexVersion, List<EmbeddedChunk> chunks);
    List<KnowledgeHit> search(String knowledgeBaseId, String indexVersion,
                              float[] queryVector, int topK, double minScore);
}
```

`KnowledgeSearchService` combines these ports and returns bounded hits with stable `chunkId`, title, section, source, score, and excerpt. `KnowledgeSearchTool` serializes that result as JSON and declares `READ_ONLY + NONE`.

### MCP port and reserved idempotency field

```java
package com.reagent.mcp;

public interface McpGateway extends AutoCloseable {
    List<McpRemoteTool> discover(String serverId);
    McpCallResult call(String serverId, String remoteToolName, Map<String, Object> arguments);
    @Override void close();
}
```

`McpToolAdapter` removes `idempotency_key` from the model-visible schema, rejects model-supplied occurrences, and injects `ToolContext.idempotencyKey()` immediately before `McpGateway.call`. Task input never supplies an MCP URL; server IDs resolve only from trusted application configuration.

### Approval state

```java
package com.reagent.approval;

public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
public enum ApprovalDecision { APPROVE, REJECT }
```

`approval_request.tool_call_id` is the primary key. Decisions use conditional updates; same-decision replay is idempotent, opposite-decision replay is HTTP 409, and deciding a cancelled/terminal task is rejected. A waiting-task cancellation atomically rejects pending approvals and unexecuted ledger items and writes their synthetic tool results without invoking MCP.

## Migration Ownership

Use these versions once, in this order:

| Migration | Owning plan/task | Purpose |
|---|---|---|
| `V1__baseline_runtime.sql` | Runtime Task 1 | Current `task`, `message`, `tool_call`, and `event` schema for fresh databases |
| `V2__runtime_profiles_and_batches.sql` | Runtime Task 3 | Task profile snapshot and tool-call assistant batch sequence |
| `V3__fake_ops_ticket.sql` | MCP/Approval Task 2 | `demo_ticket` with unique `idempotency_key` |
| `V4__durable_approval.sql` | MCP/Approval Task 3 | `approval_request` table and supporting indexes |

Configure Flyway with `baseline-on-migrate=true` and baseline version `1`. Thus a non-empty pre-Flyway volume is marked at V1 and receives V2–V4; a fresh database executes V1–V4. Keep Hibernate on `validate`, not `update`, after V1 is introduced.

## Configuration Namespace

Final configuration keys must stay under these roots:

```yaml
reagent:
  profiles:
  rag:
  mcp:
  approval:
  faults:
  worker:
  streaming:
  tracing:
  recovery:
  tool:
  sandbox:
  llm:
```

Profiles refer to trusted `mcp.server-id` values, never URLs from a task body. `application.yml` contains safe defaults; `application-test.yml`, `application-demo-smoke.yml`, and `application-demo-chaos.yml` contain deterministic mode overrides.

## Acceptance-to-Test Traceability

| Approved behavior | Required automated evidence |
|---|---|
| Current schema upgrades without manual SQL | `SchemaMigrationIT.migratesLegacySchemaAndFreshSchemaToSameVersion` |
| MySQL context and ledger are reconstructable | `StateStoreIT.rebuildsContextAndLedgerFromCommittedRows` |
| Only one worker claims a task | `LeaseFailoverIT.onlyOneWorkerWinsConcurrentClaim` |
| Every old-epoch runtime write is rejected | `StateStoreFencingIT` parameterized mutator matrix plus fenced event test |
| Redis 8 keeps existing stream behavior | `RedisStreamTransportIT` replay/live/TTL scenarios |
| Profile/catalog stays frozen across recovery | `TaskProfilePersistenceIT` and schema-drift test |
| Stable chunks and citations | `DocumentChunkerTest`, `KnowledgeSearchServiceTest` |
| Redis HNSW returns expected metadata/topK | `RedisKnowledgeIndexIT` |
| Real packaged MiniLM retrieves expected documents | `MiniLmRetrievalQualityIT` with top-3 and MRR ≥ 0.80 |
| Official MCP initialize/list/call works | `McpProtocolIT` against the independent Fake Ops MCP app |
| Reserved idempotency key cannot be forged | `McpToolAdapterTest` |
| Same downstream key creates one ticket | `FakeOpsTicketIT` and dangerous-window runtime test |
| Approval persists, resumes, rejects, and conflicts correctly | `ApprovalServiceIT`, `ApprovalControllerTest` |
| Whole batch waits before any tool executes | `ToolBatchApprovalTest` |
| Five crash windows recover deterministically | `IncidentCrashRecoveryIT` parameterized by `FaultPoint` |
| Reject path calls create_ticket zero times | `IncidentWorkflowIT.rejectsTicketAndCompletesWithoutMcpWrite` |
| Full fake workflow reaches citation + ticket | `IncidentWorkflowIT.approvesAndCompletesWithCitationAndUniqueTicket` |
| Static UI avoids unsafe HTML injection | `DemoConsoleContractTest` |
| One-command stack works | `scripts/demo-smoke.sh` in the compose-smoke CI job |
| Automated Worker A crash still yields one ticket | `scripts/demo-failover.sh` and its CI-safe assertions |

## Full Verification Gate

Run from `/root/reagent` after all four plans:

```bash
./mvnw -B clean test
./mvnw -B -Pci verify
./scripts/demo-smoke.sh
./scripts/demo-failover.sh
git diff --check
git status --short
```

Expected outcomes:

- Surefire reports no failures/errors and executes no `*IT` class.
- Failsafe reports no failures/errors and no Docker-dependent test is skipped.
- Retrieval quality output reports every key query in top 3 and aggregate MRR at least `0.80`.
- Compose smoke reports a completed task containing at least one real chunk citation and one ticket ID.
- Failover smoke reports Worker B takeover, the same `tool_call_id` replayed as the idempotency key, and `uniqueTicketCount=1`.
- `git diff --check` is silent.
- `git status --short` contains only intentional plan-tracking artifacts if they were deliberately kept outside commits; product files are clean.

## Final Review Checklist

- [ ] Read every subplan's final verification output rather than relying on commit messages.
- [ ] Search for `TODO|TBD|FIXME|placeholder|skip|disabledWithoutDocker` in production, test, scripts, workflows, and docs; justify or remove every match.
- [ ] Search for `innerHTML` in static JavaScript; the only acceptable match is the contract test's forbidden-token assertion.
- [ ] Search for `Instant.now()` in runtime persistence entities/services; all test-sensitive time comes from the injected `Clock`.
- [ ] Search every `StateStore` run mutator and durable run event call; each carries `TaskRunToken`.
- [ ] Verify no task DTO accepts an MCP URL and no API returns a secret.
- [ ] Verify default Coding Agent tests still pass without approval.
- [ ] Verify normal `demo-up.sh` does not enable `demo-chaos`.
- [ ] Verify README distinguishes framework ledger guarantees from downstream idempotency guarantees.
- [ ] Request code review with `superpowers:requesting-code-review`, address findings, then run `superpowers:verification-before-completion` before claiming completion.
