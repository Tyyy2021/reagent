# Task 9 Final-Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two remaining Task 9 Important findings by rejecting unsafe tool-call identity before persistence/execution, projecting only trusted bounded identity into observability, and making frozen tool deadlines return even when a task ignores interruption.

**Architecture:** `ToolCall` becomes the common validation boundary for streaming, non-streaming, and restored calls. `DefaultToolBatchCoordinator`, `ToolExecutor`, and `AgentRunner` retain their durable/model-visible semantics while publishing only validated or frozen metadata. `ToolExecutor` keeps per-batch virtual-thread executors but tears them down with non-waiting `shutdownNow()` instead of JDK 21 wait-on-close behavior.

**Tech Stack:** Java 21, Spring Boot 3.3, JUnit 5, Mockito, OpenTelemetry SDK test exporter, Maven Wrapper, Python 3.12/uv, Docker/Testcontainers, official MCP Java SDK 2.0.0.

## Global Constraints

- Work only in `/root/reagent/.worktrees/reagent-rag-mcp` on `feature/reagent-rag-mcp-interview`.
- Start implementation from exact clean commit `68e41dded1a8c80b8a2d5cb38d7fa38cd16865cb`.
- Do not amend, reset, rebase, clean, push, merge, or start Task 10.
- `ToolCall.id` must match `^[A-Za-z0-9_-]{1,255}$`; `ToolCall.name` must match `^[A-Za-z0-9_-]{1,64}$`.
- Invalid identity must fail before assistant persistence, ledger creation, `markInProgress`, or tool execution, and the thrown message must not echo the rejected value.
- Valid unknown-tool calls remain definitive model-visible observations; only their event/log/span name becomes the fixed value `unknown`.
- Known-tool event/log/span identity comes from the frozen `ToolSnapshot`.
- Valid call IDs remain exact in persistence and event/span correlation.
- Runtime `FAILED` events retain type `FAILED` and field `error`, but the value is exactly `task_execution_failed`.
- Durable failure text, synchronous return text, ledger results, and model context remain unchanged.
- No event/span/log may contain full arguments, evidence, log lines, response bodies, URLs, credentials, goal text, secrets, or raw exception messages/causes.
- Frozen `ToolSnapshot.timeoutMs` remains a submission-time absolute deadline.
- IDEMPOTENT MCP timeout remains `REMOTE_OUTCOME_UNKNOWN`; READ_ONLY MCP/local timeout remains `DEFINITIVE`; crash/fence signals propagate.
- Executor cleanup must request cancellation without waiting for an interrupt-ignoring in-process task to terminate.
- Hard termination remains owned by subprocess/container sandbox boundaries; add no shared executor and no dependency.
- Every production change requires a genuine focused RED before implementation and exact-test GREEN afterward.
- Task-level completion still requires fresh real `McpProtocolIT`, real `RagGatewayIT`, full CI with zero failure/error/skip, and independent full-range review with Critical 0 / Important 0.

---

### Task 1: Close both remaining Task 9 Important findings

**Files:**

- Modify: `src/main/java/com/reagent/core/ToolCall.java`
- Modify: `src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/tool/ToolExecutor.java`
- Create: `src/test/java/com/reagent/core/ToolCallTest.java`
- Modify: `src/test/java/com/reagent/core/ContextTest.java`
- Modify: `src/test/java/com/reagent/core/AgentRunnerFaultInjectionTest.java`
- Modify: `src/test/java/com/reagent/core/ToolBatchCoordinatorTest.java`
- Modify: `src/test/java/com/reagent/llm/StreamingDecisionAssemblerTest.java`
- Modify: `src/test/java/com/reagent/tool/ToolExecutorTracingTest.java`
- Append evidence only: `.superpowers/sdd/task-9-report.md`
- Append task ledger only: `.superpowers/sdd/progress.md`

Do not modify persistence, migrations, LLM clients/assemblers, MCP gateway,
properties/YAML, profiles, approvals, Python source/tests, UI, Compose, README,
dependencies, lock files, or unrelated tests. If the implementation cannot fit
this list, stop with `NEEDS_CONTEXT`.

**Interfaces:**

- Consumes: `ToolCall(String id, String name, String arguments)`,
  `TaskToolCatalog.snapshot(String)`, `ToolSnapshot.name()`,
  `ToolExecutor.executeConcurrently(TaskToolCatalog, List<ToolCall>, ToolContext)`,
  and `StreamTransport.publish(TaskRunToken, TaskEvent.Type, Map<String,Object>)`.
- Produces: the same public interfaces; validation is in the `ToolCall` compact
  constructor, and observability projection remains private to the two owning
  runtime classes.

- [ ] **Step 1: Verify exact implementation base and read the binding inputs**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git log -3 --oneline
```

Expected:

```text
## feature/reagent-rag-mcp-interview
68e41dded1a8c80b8a2d5cb38d7fa38cd16865cb
68e41dd docs: specify Task 9 final review fixes
00d9705 fix: close Task 9 review gaps
e0a0c95 feat: add official Java MCP gateway and adapters
```

Read completely before editing:

```text
docs/superpowers/specs/2026-07-25-task-9-final-review-fixes-design.md
docs/superpowers/plans/2026-07-25-task-9-final-review-fixes.md
.superpowers/sdd/task-9-brief.md
.superpowers/sdd/task-9-review-fix-brief.md
.superpowers/sdd/task-9-report.md
```

- [ ] **Step 2: Write the tool-call identity boundary tests**

Create `src/test/java/com/reagent/core/ToolCallTest.java` with these three
behaviors:

```java
package com.reagent.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCallTest {

    @Test
    void acceptsExactIdentityBoundaries() {
        String id = "i".repeat(255);
        String name = "n".repeat(64);

        ToolCall call = new ToolCall(id, name, "{}");

        assertEquals(id, call.id());
        assertEquals(name, call.name());
    }

    @Test
    void rejectsUnsafeOrOversizedIdsWithoutEchoingInput() {
        String sentinel = "https://secret.example/token=CALL_SECRET_SENTINEL";
        List<String> invalid = Arrays.asList(
                null, "", " ", "call.with.dot", sentinel, "i".repeat(256));

        for (String id : invalid) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ToolCall(id, "read_file", "{}"));
            assertEquals("Invalid tool call id", error.getMessage());
            assertFalse(error.getMessage().contains("CALL_SECRET_SENTINEL"));
        }
    }

    @Test
    void rejectsUnsafeOrOversizedNamesWithoutEchoingInput() {
        String sentinel = "SECRET/TOOL/URL";
        List<String> invalid = Arrays.asList(
                null, "", " ", "tool.with.dot", sentinel, "n".repeat(65));

        for (String name : invalid) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ToolCall("call-safe", name, "{}"));
            assertEquals("Invalid tool call name", error.getMessage());
            assertFalse(error.getMessage().contains(sentinel));
        }
    }
}
```

- [ ] **Step 3: Write the observability projection tests**

In `AgentRunnerFaultInjectionTest`, add a test using the existing `Fixture`.
It must prove the runtime exception remains durable/returned but the event is
generic:

```java
@Test
void runtimeFailurePublishesGenericBoundedEvent(@TempDir Path workspace) {
    String sentinel = "https://secret.example/body=FAILED_SECRET_SENTINEL";
    Fixture fixture = fixture(workspace, Decision.finalAnswer(
            "unused", Map.of("role", "assistant", "content", "unused")));
    when(fixture.llm.chatStream(same(fixture.context), any(), any()))
            .thenThrow(new IllegalStateException(sentinel));

    AgentRunner.RunResult result =
            fixture.runner(FaultInjector.none()).run("goal", "coding");

    assertEquals("任务执行失败:" + sentinel, result.result());
    verify(fixture.stateStore).failTask(
            fixture.token, "执行异常: " + sentinel);
    verify(fixture.transport).publish(
            fixture.token,
            TaskEvent.Type.FAILED,
            Map.of("error", "task_execution_failed"));
}
```

In `StreamingDecisionAssemblerTest`, add a test that proves invalid streamed
identity prevents a `Decision` from being returned to `AgentRunner`:

```java
@Test
void unsafeToolIdentityFailsBeforeDecisionCanBePersisted() {
    StreamingDecisionAssembler invalidId =
            new StreamingDecisionAssembler(null);
    invalidId.acceptDelta(toolCallDelta(
            0,
            "https://secret.example/CALL_SECRET_SENTINEL",
            "read_file",
            "{}"));
    IllegalArgumentException idError = assertThrows(
            IllegalArgumentException.class, invalidId::build);
    assertEquals("Invalid tool call id", idError.getMessage());
    assertFalse(idError.getMessage().contains("CALL_SECRET_SENTINEL"));

    StreamingDecisionAssembler invalidName =
            new StreamingDecisionAssembler(null);
    invalidName.acceptDelta(toolCallDelta(
            0,
            "call-safe",
            "SECRET/TOOL/NAME",
            "{}"));
    IllegalArgumentException nameError = assertThrows(
            IllegalArgumentException.class, invalidName::build);
    assertEquals("Invalid tool call name", nameError.getMessage());
    assertFalse(nameError.getMessage().contains("SECRET/TOOL/NAME"));
}
```

Add the existing JUnit `assertThrows` and `assertFalse` static imports if they
are not already present.

In `ContextTest`, add a restored-context regression proving malformed legacy
identity cannot become an executable pending call:

```java
@Test
void pendingToolCallsRejectUnsafeLegacyIdentityBeforeExecution() {
    assertPendingIdentityRejected(
            "https://secret.example/CALL_SECRET_SENTINEL",
            "read_file",
            "Invalid tool call id",
            "CALL_SECRET_SENTINEL");
    assertPendingIdentityRejected(
            "call-safe",
            "SECRET/TOOL/NAME",
            "Invalid tool call name",
            "SECRET/TOOL/NAME");
}

private static void assertPendingIdentityRejected(
        String id,
        String name,
        String expectedMessage,
        String sentinel) {
    Context context = new Context("system");
    context.addAssistant(Map.of(
            "role", "assistant",
            "content", "",
            "tool_calls", List.of(Map.of(
                    "id", id,
                    "type", "function",
                    "function", Map.of(
                            "name", name,
                            "arguments", "{}")))));

    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class, context::pendingToolCalls);

    assertEquals(expectedMessage, error.getMessage());
    assertFalse(error.getMessage().contains(sentinel));
}
```

Add the JUnit `assertFalse` static import.

In `ToolBatchCoordinatorTest`, add a valid but unknown name whose value is an
unmistakable sentinel. Use `IN_PROGRESS` with no journal so the test covers
both logs and `TOOL_CALL`/`TOOL_RESULT` events while preserving the
model-visible reconciliation text:

```java
@Test
void unknownToolUsesTrustedObservabilityClassificationButPreservesModelMessage(
        @TempDir Path workspace) {
    String modelName = "SECRET_MODEL_TOOL";
    ClassifiedTool listed = new ClassifiedTool("read", IdempotencyClass.READ_ONLY);
    TaskToolCatalog catalog = catalog(listed);
    ToolCall call = new ToolCall("call-unknown", modelName, "{}");
    Fixture fixture = fixture(workspace);
    when(fixture.stateStore.statusOf(TOKEN, call.id()))
            .thenReturn(ToolCallStatus.IN_PROGRESS);
    ListAppender<ILoggingEvent> logs =
            captureLogs(DefaultToolBatchCoordinator.class);
    try {
        fixture.coordinator.process(
                TOKEN, fixture.toolContext, fixture.context, catalog, List.of(call));

        verify(fixture.transport).publish(
                TOKEN, TaskEvent.Type.TOOL_CALL,
                Map.of("id", call.id(), "name", "unknown"));
        verify(fixture.transport).publish(
                TOKEN, TaskEvent.Type.TOOL_RESULT,
                Map.of(
                        "id", call.id(),
                        "name", "unknown",
                        "outcome", "IN_DOUBT",
                        "inDoubt", true));
        assertTrue(String.valueOf(
                fixture.context.messages().getLast().get("content"))
                .contains(modelName));
        assertFalse(capturedLogText(logs).contains(modelName));
    } finally {
        detachLogs(DefaultToolBatchCoordinator.class, logs);
    }
}
```

Replace `executorCannotRunToolOutsideTaskCatalog` in
`ToolExecutorTracingTest` with:

```java
@Test
void executorCannotRunToolOutsideTaskCatalog() {
    AtomicInteger executions = new AtomicInteger();
    Tool listed = new FakeTool("listed");
    Tool extra = new Tool() {
        @Override public String name() { return "SECRET_UNKNOWN_TOOL"; }
        @Override public String description() { return "not allowlisted"; }
        @Override public Map<String, Object> parameterSchema() {
            return Map.of("type", "object");
        }
        @Override public String execute(JsonNode args, ToolContext ctx) {
            executions.incrementAndGet();
            return "must not run";
        }
    };
    ObjectMapper mapper = new ObjectMapper();
    ToolProperties properties = new ToolProperties();
    ToolCatalogResolver resolver = new ToolCatalogResolver(
            new ToolRegistry(List.of(listed, extra)),
            new SchemaHasher(mapper),
            properties);
    TaskToolCatalog catalog = resolver.resolve(resolver.snapshot(
            new AgentProfileDefinition(
                    "coding", "v1", "prompt", null, null,
                    List.of(), List.of("listed"))));
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    ToolExecutor executor = new ToolExecutor(
            mapper,
            properties,
            OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .build()
                    .getTracer("test"));
    try {
        ToolExecutionOutcome result = executor.execute(
                catalog,
                new ToolCall("call-extra", extra.name(), "{}"),
                new ToolContext("task-1", Path.of(".")));

        assertEquals(0, executions.get());
        assertEquals(
                ToolExecutionOutcome.definitive(
                        "错误:不存在名为 'SECRET_UNKNOWN_TOOL' 的工具。"),
                result);
        SpanData toolSpan = exporter.getFinishedSpanItems().getFirst();
        assertEquals("execute_tool unknown", toolSpan.getName());
        assertEquals(
                "unknown",
                toolSpan.getAttributes().get(Trace.TOOL_NAME));
        assertEquals(
                "call-extra",
                toolSpan.getAttributes().get(Trace.TOOL_CALL_ID));
        assertFalse((toolSpan.getAttributes() + " " + toolSpan.getEvents())
                .contains("SECRET_UNKNOWN_TOOL"));
    } finally {
        provider.close();
    }
}
```

- [ ] **Step 4: Run the first focused RED and preserve the evidence**

Run before changing production:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest \
  test
```

Expected genuine behavior RED:

```text
Tests run: 39
Failures: 7
Errors: 0
Skipped: 0
```

The seven failures must be the two missing `ToolCall` rejections, streamed
identity acceptance, restored pending identity acceptance, raw FAILED event
value, raw unknown coordinator identity, and raw unknown tool span identity. A
compile error, fixture error, or unrelated failure is not valid RED evidence;
repair only the test fixture and rerun until the expected behavior failures are
observed.

Append the command, totals, failing assertions, and why each failure is
expected to `.superpowers/sdd/task-9-report.md`.

- [ ] **Step 5: Implement central identity validation**

Replace the empty `ToolCall` record body with:

```java
private static final Pattern LEGAL_ID =
        Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
private static final Pattern LEGAL_NAME =
        Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

public ToolCall {
    if (id == null || !LEGAL_ID.matcher(id).matches()) {
        throw new IllegalArgumentException("Invalid tool call id");
    }
    if (name == null || !LEGAL_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid tool call name");
    }
}
```

Add `java.util.regex.Pattern`. Do not validate or rewrite `arguments` here;
the existing adapter/gateway owns argument structure and byte limits.

- [ ] **Step 6: Implement trusted event/log/span projection**

In `DefaultToolBatchCoordinator`, add:

```java
private static String observableToolName(
        TaskToolCatalog catalog, ToolCall call) {
    ToolSnapshot snapshot = catalog.snapshot(call.name());
    return snapshot == null ? "unknown" : snapshot.name();
}
```

For every coordinator log and every `TOOL_CALL`/`TOOL_RESULT` event, replace
`call.name()` with `observableToolName(catalog, call)`. Do not change
`reconciledMessage`, `inDoubtMessage`, ledger calls, context messages, call
IDs, outcome fields, or recovery disposition.

In `ToolExecutor.executeResolved`, derive:

```java
String observableName = observableToolName(snapshot);
Span span = tracer.spanBuilder("execute_tool " + observableName)
        .setAttribute(Trace.TOOL_NAME, observableName)
        .setAttribute(Trace.TOOL_CALL_ID, call.id())
        .startSpan();
```

Add:

```java
private static String observableToolName(ToolSnapshot snapshot) {
    return snapshot == null ? "unknown" : snapshot.name();
}
```

Use `observableName` for completion/failure logs in `executeResolved`. Change
`await` to receive the frozen `ToolSnapshot`, derive both
`isIdempotentMcp(snapshot)` and `observableToolName(snapshot)` inside it, and
use only the observable name in its timeout log. Keep `call.name()` in
durable/model-visible result strings.

In `AgentRunner`, add:

```java
private static final String TASK_EXECUTION_FAILED =
        "task_execution_failed";
```

Change only the runtime-exception event publication to:

```java
bus.publish(
        token,
        TaskEvent.Type.FAILED,
        Map.of("error", TASK_EXECUTION_FAILED));
```

Keep `stateStore.failTask(token, "执行异常: " + ex.getMessage())` and the
synchronous return unchanged.

- [ ] **Step 7: Run the exact first GREEN**

Run:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest \
  test
```

Expected:

```text
Tests run: 39
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Append exact output totals and the preserved durable/model-visible assertions
to `.superpowers/sdd/task-9-report.md`. Do not start the executor-lifecycle
production change until this command is green.

- [ ] **Step 8: Write interrupt-ignoring serial and concurrent deadline tests**

In `ToolExecutorTracingTest`, add two tests:

```java
@Test
void serialDeadlineReturnsWhileTimedOutToolIgnoresInterrupt() throws Exception {
    assertDeadlineReturnsWhileToolIgnoresInterrupt(false);
}

@Test
void concurrentDeadlineReturnsWhileTimedOutToolIgnoresInterrupt() throws Exception {
    assertDeadlineReturnsWhileToolIgnoresInterrupt(true);
}
```

Add this helper, adapting only local variable names to existing style:

```java
private static void assertDeadlineReturnsWhileToolIgnoresInterrupt(
        boolean concurrent) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ToolProperties properties = new ToolProperties();
    properties.setTimeoutMs(35);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Tool stubborn = throwingTool("stubborn", () -> {
        started.countDown();
        while (release.getCount() > 0) {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
        return "late";
    });
    Tool fast = new FakeTool("fast");
    List<Tool> tools = concurrent
            ? List.of(stubborn, fast)
            : List.of(stubborn);
    List<ToolCall> calls = concurrent
            ? List.of(
                    new ToolCall("call-stubborn", stubborn.name(), "{}"),
                    new ToolCall("call-fast", fast.name(), "{}"))
            : List.of(new ToolCall("call-stubborn", stubborn.name(), "{}"));
    TaskToolCatalog catalog = catalog(
            mapper,
            properties,
            null,
            tools,
            List.of(),
            tools.stream().map(Tool::name).toList());
    ToolExecutor executor = new ToolExecutor(
            mapper,
            properties,
            OpenTelemetrySdk.builder().build().getTracer("test"));
    FutureTask<Map<String, ToolExecutionOutcome>> batch =
            new FutureTask<>(() -> executor.executeConcurrently(
                    catalog,
                    calls,
                    new ToolContext("task-1", Path.of("."))));
    Thread driver = Thread.ofPlatform().daemon(true).unstarted(batch);
    driver.start();
    try {
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Map<String, ToolExecutionOutcome> outcomes =
                batch.get(500, TimeUnit.MILLISECONDS);
        assertTrue(outcomes.get("call-stubborn").content().contains(">35ms"));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        if (concurrent) {
            assertEquals(
                    ToolExecutionOutcome.definitive("ok:fast"),
                    outcomes.get("call-fast"));
        }
    } finally {
        release.countDown();
        driver.join(1_000);
    }
    assertFalse(driver.isAlive());
}
```

Add the `FutureTask` import. In the existing fatal crash/sibling test, replace
the immediate `AtomicBoolean` cancellation assertion with a
`CountDownLatch` and `await(1, TimeUnit.SECONDS)` so the assertion does not
depend on executor close waiting for the sibling.

- [ ] **Step 9: Run the executor-lifecycle RED**

Run before changing executor ownership:

```bash
./mvnw -B -Dtest=ToolExecutorTracingTest test
```

Expected genuine behavior RED:

```text
Tests run: 10
Failures: 0
Errors: 2
Skipped: 0
```

Both new tests must time out at `FutureTask.get(..., 500ms)` because unchanged
JDK 21 `ExecutorService.close()` waits for the stubborn task. The `finally`
release must let both driver threads terminate; no Maven process may remain.
Append exact evidence to `.superpowers/sdd/task-9-report.md`.

- [ ] **Step 10: Replace wait-on-close with non-waiting teardown**

In both serial and concurrent branches of `executeConcurrently`, replace
try-with-resources ownership with explicit ownership:

```java
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
try {
    // existing submit/await loop, unchanged except for the frozen snapshot
    // passed to await
} finally {
    pool.shutdownNow();
}
```

Do not call `close()`, `awaitTermination`, or block in `finally`. Preserve
existing per-future timeout cancellation and fatal sibling cancellation.
Update the obsolete source comment that currently says try-with-resources
waits for every task.

- [ ] **Step 11: Run exact lifecycle GREEN and aggregate focused GREEN**

Run:

```bash
./mvnw -B -Dtest=ToolExecutorTracingTest test
```

Expected:

```text
Tests run: 10
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Then run:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest \
  test
```

Expected:

```text
Tests run: 41
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Append exact totals and cancellation hygiene to the report.

- [ ] **Step 12: Run Task 9 focused Java/Python and static gates**

Run:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest,McpGatewayContractTest \
  test
```

Expected:

```text
Tests run: 69
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Run:

```bash
cd services/agent-capabilities
UV_CACHE_DIR=/tmp/reagent-task9-uv-cache \
PYTHONDONTWRITEBYTECODE=1 \
uv run --locked pytest -q tests/test_mcp_protocol.py
UV_CACHE_DIR=/tmp/reagent-task9-uv-cache \
PYTHONDONTWRITEBYTECODE=1 \
uv run --locked ruff check .
UV_CACHE_DIR=/tmp/reagent-task9-uv-cache \
PYTHONDONTWRITEBYTECODE=1 \
uv run --locked pyright
```

Expected:

```text
3 passed
All checks passed!
0 errors, 0 warnings, 0 informations
```

Return to the worktree root after the Python commands.

- [ ] **Step 13: Build the fresh image and run the real protocol gate**

Build:

```bash
docker build \
  -t reagent-agent-capabilities:task9 \
  services/agent-capabilities
docker image inspect reagent-agent-capabilities:task9
```

Record the exact image ID, creation time, and size in the report.

Run:

```bash
./mvnw -B -Dit.test=McpProtocolIT verify
```

Expected:

```text
Surefire tests: 206 passed
McpProtocolIT: 1 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 14: Run fresh fast and full CI regression gates**

Run:

```bash
./mvnw -B test
```

Expected:

```text
Tests run: 206
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Run:

```bash
./mvnw -B \
  -Dreagent.test.hf-cache=/root/reagent/.worktrees/reagent-rag-mcp/.superpowers/sdd/hf-cache-task9 \
  -Pci verify
```

Expected:

```text
Surefire tests: 206 passed
Failsafe tests: 58 passed
Total: 264 passed
Failures: 0
Errors: 0
Skipped: 0
RagGatewayIT: passed
McpProtocolIT: passed
BUILD SUCCESS
```

- [ ] **Step 15: Audit dependency, scope, leaks, and runtime hygiene**

Run:

```bash
./mvnw -B dependency:tree -Dincludes=io.modelcontextprotocol.sdk
git diff --check 68e41dded1a8c80b8a2d5cb38d7fa38cd16865cb..HEAD
git status --short
docker ps
```

Expected dependency tree:

```text
io.modelcontextprotocol.sdk:mcp-core:jar:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:jar:2.0.0
```

Inspect the exact working diff and confirm:

```text
only the ten allowlisted product/test paths are modified or created
no dependency, lock, persistence, migration, YAML, profile, approval, Python,
UI, Compose, README, Task 10, generated cache, pyc, class, or report artifact
is staged
no running test container or Maven/Python process remains
```

Search the changed production paths and captured test surfaces to confirm no
raw call name or exception message remains in event/log/span code.

- [ ] **Step 16: Commit the implementation and verify the exact state**

Append every RED/GREEN command, exact total, design decision, image identity,
full-gate result, scope audit, and concern to
`.superpowers/sdd/task-9-report.md`.

Stage exactly:

```bash
git add \
  src/main/java/com/reagent/core/ToolCall.java \
  src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java \
  src/main/java/com/reagent/core/AgentRunner.java \
  src/main/java/com/reagent/tool/ToolExecutor.java \
  src/test/java/com/reagent/core/ToolCallTest.java \
  src/test/java/com/reagent/core/ContextTest.java \
  src/test/java/com/reagent/core/AgentRunnerFaultInjectionTest.java \
  src/test/java/com/reagent/core/ToolBatchCoordinatorTest.java \
  src/test/java/com/reagent/llm/StreamingDecisionAssemblerTest.java \
  src/test/java/com/reagent/tool/ToolExecutorTracingTest.java
git diff --cached --check
git diff --cached --stat
git commit -m "fix: enforce Task 9 safety boundaries"
```

Verify:

```bash
git rev-parse HEAD
git status --short --branch
git diff --check \
  19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..HEAD
```

Expected: a new implementation commit after `68e41dd`, empty index, clean
worktree, and clean full Task 9 range.

- [ ] **Step 17: Freeze and hand off the final full-range review**

Create an immutable package for exact range:

```text
19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..HEAD
```

Record its exact path, line count, byte count, SHA-256, commit list, diffstat,
and full-range `git diff --check` result. A fresh read-only reviewer must read:

```text
.superpowers/sdd/task-9-brief.md
.superpowers/sdd/task-9-resume-brief.md
.superpowers/sdd/task-9-review-fix-brief.md
docs/superpowers/specs/2026-07-25-task-9-final-review-fixes-design.md
docs/superpowers/plans/2026-07-25-task-9-final-review-fixes.md
.superpowers/sdd/task-9-report.md
the complete immutable review package
```

The reviewer must issue both spec-compliance and code-quality verdicts and
classify Critical/Important/Minor. Do not mark Task 9 complete unless Critical
and Important are both zero. Record any Minor for the final whole-branch
review, per the user's review policy.
