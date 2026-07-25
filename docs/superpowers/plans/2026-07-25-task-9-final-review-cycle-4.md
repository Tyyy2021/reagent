# Task 9 Final-Review Cycle 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three remaining Task 9 Important findings by making MCP session/discovery state atomic, freezing validated decisions, and rejecting reused tool-call identities before persistence.

**Architecture:** One implementation worker executes the complete final-review fix wave so the gateway, live-decision, persistence, and recovery invariants stay coherent. `OfficialMcpGateway` invalidates session and discovery together, `Decision` owns a deeply immutable raw JSON snapshot, and `StateStore` rejects every new batch whose ID already exists while verifying stored identity before ledger mutation. Three focused TDD commits are followed by the full Task 9 gate set and exactly one scoped re-review of the complete fix range.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Jackson 2, JUnit 5, Mockito, official MCP Java SDK 2.0.0, Maven Wrapper, MySQL 8 and Redis 8 through Testcontainers, Python 3.12/uv.

## Global Constraints

- Work only in `/root/reagent/.worktrees/reagent-rag-mcp` on branch
  `feature/reagent-rag-mcp-interview`.
- Approved Cycle 4 design baseline is exact
  `b4ebe6911f1ab9e8a7ffee4492ce7538be93038e`.
- Binding specification:
  `docs/superpowers/specs/2026-07-25-task-9-final-review-cycle-4-design.md`,
  400 lines, 15,532 bytes, SHA-256
  `b40b592a728c614d903bbd99c7efdaa82d58ddbb0160f100616be0c37f71d38f`.
- Before dispatching the implementation worker, the controller records the
  current clean docs-only HEAD in this plan's ignored ledger on a line
  beginning `Dispatch base: ` followed by the exact 40-character SHA. That
  literal SHA is the Cycle 4 implementation/fix base and governs every
  six-path scope diff.
- Full Task 9 review base remains exact
  `19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd`.
- Use one implementation worker for all three original findings. Do not split
  gateway, Decision, and StateStore fixes among independent workers.
- After implementation and gates, run exactly one scoped re-review of the
  complete fix range. There is no second fix wave. Residual Critical or
  Important findings return to the user and keep Task 9 blocked.
- Every production change requires an assertion-based focused RED on unchanged
  relevant production. Compilation, fixture, permission, Docker, or network
  failure is not behavioral RED.
- Use only `apply_patch` for file edits. Stage only the exact paths named by
  each commit step.
- Do not amend, reset, rebase, clean, push, merge, or start Task 10.
- Do not modify executor lifecycle, timeout, cancellation, or ownership code.
  The user-authorized lifecycle Important remains withdrawn; retain the
  serial/concurrent interrupt-ignoring regressions.
- Do not change dependencies, configuration, migrations, Python, UI, Compose,
  README, profiles, approvals, schemas, repositories, or database schema.
- New validation errors are fixed and bounded. They never interpolate server
  IDs, task IDs, call IDs, tool names, arguments, assistant content, Schema,
  results, URLs, or credentials, and do not retain unsafe causes.
- Preserve valid raw assistant representation, exact string arguments,
  structured JSON arguments, missing/null argument fallback, durable task
  result, context, approval, recovery, idempotency, synchronous return, client
  `COMPLETED` event, and final-answer log isolation.
- Missing, explicit-null, or empty `tool_calls` remains valid for a final
  decision. A malformed non-null or valid non-empty value fails closed.
- A restored recovery attempt reuses the original ledger row and does not call
  `appendAssistant` again. New assistant batches may never reuse an existing
  global `tool_call_id`.
- Use authorized host execution unchanged when restricted loopback or Docker
  access is denied. Record both attempts and never alter fixtures to bypass the
  boundary.
- Append commands, exact RED/GREEN counts, commits, warnings, host reruns,
  audits, package identities, and adjudications only to ignored
  `.superpowers/sdd/task-9-report.md` and `.superpowers/sdd/progress.md`.
- Stable full-CI cache remains
  `/root/reagent/.worktrees/reagent-rag-mcp/.superpowers/sdd/hf-cache-task9`
  with native 40-byte `refs/main`, revision
  `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`, and the established exact ten
  snapshot files.
- Reuse `reagent-agent-capabilities:task9` only after inspecting exact image ID
  `sha256:c53606b5a3603a9c1e6fa49ebf540dfb6f21f2000b17a2a8f69bb72c428c8064`,
  creation `2026-07-24T23:44:49.300087564+08:00`, and size `8781245552`.
- Task 9 completes only after the scoped re-review reports all three findings
  addressed with no new Critical/Important breakage and fresh
  verification-before-completion passes. Task 10 remains blocked afterward.

---

## File Map

### MCP state validity

- Modify `src/main/java/com/reagent/mcp/OfficialMcpGateway.java`
  - invalidate session and discovery together;
  - require both before a call;
  - close and clear failed/rejected replacement state.
- Modify `src/test/java/com/reagent/mcp/McpGatewayContractTest.java`
  - cover exhausted retry followed by later recovery;
  - cover rejected replacement discovery;
  - cover failed public refresh invalidation.

### Decision snapshot

- Modify `src/main/java/com/reagent/core/Decision.java`
  - recursively detach/freeze raw assistant JSON;
  - reject malformed/non-empty tool batches on final decisions;
  - preserve exact raw/executable reconciliation for tool decisions.
- Modify `src/test/java/com/reagent/core/DecisionTest.java`
  - cover source mutation and exposed nested immutability;
  - cover invalid final tool batches and valid missing/null/empty controls.

### Durable tool-call identity

- Modify `src/main/java/com/reagent/persist/StateStore.java`
  - reject every existing ID before assistant persistence;
  - compare stored name/arguments before status/result mutation;
  - replace data-bearing ownership errors with fixed messages.
- Modify `src/test/java/com/reagent/persist/StateStoreIT.java`
  - cover identical/name/argument same-task reuse;
  - cover cross-task reuse with a fixed bounded error;
  - cover identity mismatch for all three ledger mutation operations.

No other production or test path is allowed in the Cycle 4 implementation
range.

---

### Task 1: Execute the single consolidated final-review fix wave

**Files:**

- Read:
  `docs/superpowers/specs/2026-07-25-task-9-final-review-cycle-4-design.md`
- Read:
  `.superpowers/sdd/task-9-report.md`
- Read:
  `.superpowers/sdd/task-9-brief.md`
- Read:
  `.superpowers/sdd/task-9-review-fix-brief.md`
- Read:
  `.superpowers/sdd/2026-07-25-task-9-final-review-cycle-4/progress.md`
- Modify:
  `src/main/java/com/reagent/mcp/OfficialMcpGateway.java`
- Modify:
  `src/test/java/com/reagent/mcp/McpGatewayContractTest.java`
- Modify:
  `src/main/java/com/reagent/core/Decision.java`
- Modify:
  `src/test/java/com/reagent/core/DecisionTest.java`
- Modify:
  `src/main/java/com/reagent/persist/StateStore.java`
- Modify:
  `src/test/java/com/reagent/persist/StateStoreIT.java`
- Append ignored evidence:
  `.superpowers/sdd/task-9-report.md`
- Append ignored evidence:
  `.superpowers/sdd/progress.md`

**Interfaces:**

- Consumes:
  `OfficialMcpGateway.ServerState`, `Decision.tools`,
  `Decision.finalAnswer`, `StateStore.appendAssistant`,
  `StateStore.markInProgress`, `StateStore.recordToolResult`, and
  `StateStore.markInDoubt`.
- Preserves:
  `McpGateway`, `Decision`, `LlmClient`, and `StateStore` public signatures.
- Produces:
  atomic gateway state, immutable `Decision.getAssistantMessage()`, final
  tool-batch rejection, cross-batch ID rejection, and ledger identity
  verification.
- Produces exactly three focused implementation commits and a clean
  implementation HEAD.

- [ ] **Step 1: Read every binding input and verify the clean dispatch boundary**

Read every file in the task's Read list completely. The flat report contains
the original Task 9 evidence and final-review history; the Cycle 4 ledger
contains the literal dispatch base. Do not rely on conversation summaries.

Run:

```bash
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git status --short --branch
git diff --check
git diff --cached --check
```

Expected:

```text
/root/reagent/.worktrees/reagent-rag-mcp
feature/reagent-rag-mcp-interview
HEAD equals the Cycle 4 ledger's literal "Dispatch base:" SHA
branch header only
no diff-check output
```

If any tracked product/test change exists, stop and audit it. Do not reset or
clean.

- [ ] **Step 2: Verify runtime hygiene, cache, and unchanged behavioral baselines**

Confirm no Maven/Surefire/Failsafe/pytest/uv/Docker-build process and no
running container. Confirm the stable cache's native ref is 40 bytes and its
snapshot has exactly:

```text
1_Pooling/config.json
config.json
config_sentence_transformers.json
model.safetensors
modules.json
sentence_bert_config.json
special_tokens_map.json
tokenizer.json
tokenizer_config.json
vocab.txt
```

Run the current unit baseline:

```bash
./mvnw -B \
  -Dtest=McpGatewayContractTest,DecisionTest,AgentRunnerFaultInjectionTest \
  test
```

Expected:

```text
Tests run: 59
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Run the current persistence baseline on the authorized Docker host if needed:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected:

```text
Surefire: 250 passed
StateStoreIT: 18 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Record exact commands, counts, times, warnings, and any restricted/host rerun.

- [ ] **Step 3: Add the three gateway state-invalidating regressions**

In `McpGatewayContractTest`, add static import:

```java
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
```

Add this test for an exhausted retry followed by a fresh later call:

```java
@Test
void secondTransportFailureInvalidatesStateAndLaterCallRediscovers() {
    ScriptedFactory factory = new ScriptedFactory();
    ScriptedSession first = factory.addSession();
    first.discovery.add(List.of(tool("query_metrics", objectSchema())));
    first.failCall = transportFailure("first");
    ScriptedSession second = factory.addSession();
    second.discovery.add(List.of(tool("query_metrics", objectSchema())));
    second.failCall = transportFailure("second");
    ScriptedSession third = factory.addSession();
    third.discovery.add(List.of(tool("query_metrics", objectSchema())));
    third.results.add(textResult("recovered"));
    McpReadiness readiness = new McpReadiness();
    OfficialMcpGateway gateway = new OfficialMcpGateway(
            validProperties(), new ObjectMapper(), readiness, factory);

    gateway.discover("fake-ops");
    assertThrows(
            OfficialMcpGateway.TransportFailureException.class,
            () -> gateway.call("fake-ops", "query_metrics", Map.of()));
    assertEquals(1, first.closes.get());
    assertEquals(1, second.closes.get());
    assertFalse(readiness.state("fake-ops").ready());

    assertEquals(
            new McpCallResult("recovered", false),
            assertDoesNotThrow(
                    () -> gateway.call("fake-ops", "query_metrics", Map.of())));
    assertEquals(3, factory.opens.get());
    assertEquals(1, third.calls.get());
    assertTrue(readiness.state("fake-ops").ready());
}
```

Add this replacement-discovery rejection test:

```java
@Test
void rejectedReplacementDiscoveryLeavesNoCallableCachedState() {
    ScriptedFactory factory = new ScriptedFactory();
    ScriptedSession first = factory.addSession();
    first.discovery.add(List.of(tool("query_metrics", objectSchema())));
    first.failCall = transportFailure("first");
    ScriptedSession rejected = factory.addSession();
    rejected.discovery.add(List.of());
    ScriptedSession fresh = factory.addSession();
    fresh.discovery.add(List.of(tool("query_metrics", objectSchema())));
    fresh.results.add(textResult("fresh"));
    McpReadiness readiness = new McpReadiness();
    OfficialMcpGateway gateway = new OfficialMcpGateway(
            validProperties(), new ObjectMapper(), readiness, factory);

    gateway.discover("fake-ops");
    assertThrows(
            McpContractException.class,
            () -> gateway.call("fake-ops", "query_metrics", Map.of()));
    assertEquals(1, rejected.closes.get());
    assertFalse(readiness.state("fake-ops").ready());

    assertEquals(
            new McpCallResult("fresh", false),
            assertDoesNotThrow(
                    () -> gateway.call("fake-ops", "query_metrics", Map.of())));
    assertEquals(3, factory.opens.get());
    assertEquals(1, fresh.calls.get());
    assertTrue(readiness.state("fake-ops").ready());
}
```

Add this failed-public-refresh test:

```java
@Test
void failedPublicDiscoveryRefreshInvalidatesPreviouslyValidState() {
    ScriptedFactory factory = new ScriptedFactory();
    ScriptedSession stale = factory.addSession();
    stale.discovery.add(List.of(tool("query_metrics", objectSchema())));
    stale.discovery.add(List.of());
    ScriptedSession fresh = factory.addSession();
    fresh.discovery.add(List.of(tool("query_metrics", objectSchema())));
    fresh.results.add(textResult("fresh"));
    McpReadiness readiness = new McpReadiness();
    OfficialMcpGateway gateway = new OfficialMcpGateway(
            validProperties(), new ObjectMapper(), readiness, factory);

    gateway.discover("fake-ops");
    assertThrows(McpContractException.class, () -> gateway.discover("fake-ops"));
    assertEquals(1, stale.closes.get());
    assertFalse(readiness.state("fake-ops").ready());

    assertEquals(
            new McpCallResult("fresh", false),
            assertDoesNotThrow(
                    () -> gateway.call("fake-ops", "query_metrics", Map.of())));
    assertEquals(2, factory.opens.get());
    assertEquals(1, fresh.calls.get());
    assertTrue(readiness.state("fake-ops").ready());
}
```

Add exact test helpers:

```java
private static McpTransportException transportFailure(String attempt) {
    return new McpTransportException(
            "connection reset " + attempt,
            new ConnectException("connection reset"));
}

private static OfficialMcpGateway.RawCallResult textResult(String text) {
    return new OfficialMcpGateway.RawCallResult(
            List.of(new OfficialMcpGateway.RawContent(true, text)),
            false);
}
```

The attempt string is test-only and must not enter production diagnostics.

- [ ] **Step 4: Run the genuine gateway RED**

Run:

```bash
./mvnw -B -Dtest=McpGatewayContractTest test
```

Expected on unchanged `OfficialMcpGateway`:

```text
Tests run: 31
Failures: 3
Errors: 0
Skipped: 0
BUILD FAILURE
```

The failures must prove:

```text
second transport failure leaves stale discovery with null session
rejected replacement is not closed/invalidated
failed public refresh leaves the earlier state callable
```

Different counts, compilation errors, socket errors, or fixture exhaustion are
not a valid RED. Diagnose before production edits.

- [ ] **Step 5: Make gateway state validity atomic**

In `OfficialMcpGateway`, replace `closeSession` with:

```java
private static void invalidateState(ServerState state) {
    Session session = state.session;
    state.session = null;
    state.discovery = List.of();
    if (session != null) {
        session.close();
    }
}
```

Use `invalidateState` on shutdown and on every branch that abandons a session.
Add one wrapper shared by public discovery and call-time discovery:

```java
private List<McpRemoteTool> discoverOrInvalidate(
        String serverId,
        McpProperties.Server configured,
        ServerState state) {
    try {
        return discoverWithReconnect(serverId, configured, state);
    } catch (RuntimeException exception) {
        invalidateState(state);
        readiness.recordUnavailable(
                serverId, unavailableReason(exception));
        throw normalize("MCP discovery failed", exception);
    }
}
```

Public `discover` delegates to this wrapper:

```java
return discoverOrInvalidate(serverId, configured, state);
```

At the beginning of `call`, require both state members through the same
wrapper:

```java
if (state.session == null || state.discovery.isEmpty()) {
    discoverOrInvalidate(serverId, configured, state);
}
```

In `discoverWithReconnect`:

```text
first definitive discovery failure
  -> invalidate state
  -> fail without retry

first transport discovery failure
  -> invalidate state
  -> open one replacement
  -> assign it to state before validation so invalidation can close it

replacement initialize/discovery failure
  -> invalidate state
  -> classify transport versus contract

replacement success
  -> install its immutable discovery
  -> record ready
```

In the call reconnect path, keep replacement discovery and replacement
`callTool` failure handling separate:

```java
invalidateState(state);
Session replacement;
List<McpRemoteTool> rediscovered;
try {
    replacement = openInitialized(serverId, configured);
    state.session = replacement;
    rediscovered = validatedDiscovery(serverId, configured, replacement);
} catch (RuntimeException reconnectFailure) {
    invalidateState(state);
    readiness.recordUnavailable(
            serverId, unavailableReason(reconnectFailure));
    if (isTransportFailure(reconnectFailure)) {
        throw new TransportFailureException(
                "MCP transport unavailable", reconnectFailure);
    }
    throw normalize("MCP reconnect failed", reconnectFailure);
}

Map<String, Object> currentSchema =
        schemaFor(rediscovered, toolName);
if (!previousSchema.equals(currentSchema)) {
    invalidateState(state);
    readiness.recordUnavailable(serverId, "schema drift");
    throw new McpContractException(
            "MCP Schema drift detected for " + serverId + "/" + toolName);
}
state.discovery = rediscovered;
readiness.recordReady(serverId, names(rediscovered));

try {
    return validateCallResult(
            replacement.call(toolName, safeArguments),
            configured.getMaximumResponseBytes());
} catch (RuntimeException secondFailure) {
    if (isTransportFailure(secondFailure)) {
        invalidateState(state);
        readiness.recordUnavailable(
                serverId, "transport unavailable");
        throw new TransportFailureException(
                "MCP transport unavailable", secondFailure);
    }
    throw normalize("MCP call failed", secondFailure);
}
```

The existing Schema-drift exception contains only trusted configured server and
tool identifiers and remains unchanged. Do not retry definitive result or
protocol failures.

- [ ] **Step 6: Run gateway GREEN, audit, and commit**

Run:

```bash
./mvnw -B -Dtest=McpGatewayContractTest test
```

Expected:

```text
Tests run: 31
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Audit:

```text
session and discovery clear together
every rejected replacement is closed exactly once
readiness is unavailable after failure and ready only after validation
no second reconnect was added
definitive call failures remain no-retry
public discovery still performs a current listTools refresh
```

Stage exactly and commit:

```bash
git add -- \
  src/main/java/com/reagent/mcp/OfficialMcpGateway.java \
  src/test/java/com/reagent/mcp/McpGatewayContractTest.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: invalidate rejected MCP gateway state"
```

- [ ] **Step 7: Add Decision source-mutation and final-batch regressions**

In `DecisionTest`, add static import:

```java
import static org.junit.jupiter.api.Assertions.assertTrue;
```

Add one tool-decision snapshot test using mutable source collections:

```java
@Test
@SuppressWarnings("unchecked")
void deeplyDetachesAndFreezesToolAssistantMessage() {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("path", "README.md");
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", "read_file");
    function.put("arguments", arguments);
    Map<String, Object> rawCall = new LinkedHashMap<>();
    rawCall.put("id", "call-frozen");
    rawCall.put("type", "function");
    rawCall.put("function", function);
    List<Map<String, Object>> rawCalls =
            new ArrayList<>(List.of(rawCall));
    Map<String, Object> assistant = new LinkedHashMap<>();
    assistant.put("role", "assistant");
    assistant.put("content", null);
    assistant.put("tool_calls", rawCalls);
    ToolCall executable = new ToolCall(
            "call-frozen", "read_file", "{\"path\":\"README.md\"}");

    Decision decision = Decision.tools(
            assistant, List.of(executable));
    assistant.put("content", "mutated");
    arguments.put("path", "OTHER.md");
    function.put("name", "list_dir");
    rawCall.put("id", "call-mutated");
    rawCalls.clear();

    Map<String, Object> snapshot = decision.getAssistantMessage();
    assertEquals(null, snapshot.get("content"));
    assertEquals(
            List.of(executable),
            ToolCall.parseAssistantToolCalls(snapshot.get("tool_calls")));
    assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.put("content", "blocked"));
    List<Map<String, Object>> snapshotCalls =
            (List<Map<String, Object>>) snapshot.get("tool_calls");
    assertThrows(
            UnsupportedOperationException.class,
            () -> snapshotCalls.clear());
    Map<String, Object> snapshotFunction =
            (Map<String, Object>) snapshotCalls.getFirst().get("function");
    assertThrows(
            UnsupportedOperationException.class,
            () -> snapshotFunction.put("name", "blocked"));
    Map<String, Object> snapshotArguments =
            (Map<String, Object>) snapshotFunction.get("arguments");
    assertThrows(
            UnsupportedOperationException.class,
            () -> snapshotArguments.put("path", "blocked"));
}
```

Add a final-decision snapshot test:

```java
@Test
@SuppressWarnings("unchecked")
void deeplyDetachesAndFreezesFinalAssistantMessage() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source", "model");
    Map<String, Object> assistant = new LinkedHashMap<>();
    assistant.put("role", "assistant");
    assistant.put("content", "done");
    assistant.put("metadata", metadata);

    Decision decision = Decision.finalAnswer("done", assistant);
    assistant.put("content", "mutated");
    metadata.put("source", "mutated");

    assertEquals("done", decision.getAssistantMessage().get("content"));
    Map<String, Object> snapshotMetadata =
            (Map<String, Object>) decision.getAssistantMessage().get("metadata");
    assertEquals("model", snapshotMetadata.get("source"));
    assertThrows(
            UnsupportedOperationException.class,
            () -> snapshotMetadata.put("source", "blocked"));
}
```

Add two invalid final cases:

```java
@ParameterizedTest(name = "rejects final assistant with {0}")
@MethodSource("invalidFinalToolCalls")
void rejectsInvalidOrNonEmptyFinalToolCalls(
        String ignored, Map<String, Object> assistant) {
    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> Decision.finalAnswer("done", assistant));

    assertEquals("Invalid final assistant message", error.getMessage());
}

private static Stream<Arguments> invalidFinalToolCalls() {
    return Stream.of(
            Arguments.of(
                    "malformed tool_calls",
                    assistantWithToolCalls(Map.of())),
            Arguments.of(
                    "non-empty tool_calls",
                    assistant(List.of(rawCall(
                            true,
                            "call-final",
                            true,
                            "read_file",
                            "{}")))));
}
```

Add three valid controls:

```java
@ParameterizedTest(name = "allows final assistant with {0}")
@MethodSource("validFinalToolCalls")
void allowsMissingNullOrEmptyFinalToolCalls(
        String ignored, Map<String, Object> assistant) {
    Decision decision = Decision.finalAnswer("done", assistant);

    assertTrue(decision.isFinal());
    assertEquals("done", decision.getAnswer());
}

private static Stream<Arguments> validFinalToolCalls() {
    Map<String, Object> explicitNull = assistantWithoutToolCalls();
    explicitNull.put("tool_calls", null);
    return Stream.of(
            Arguments.of("missing tool_calls", assistantWithoutToolCalls()),
            Arguments.of("null tool_calls", explicitNull),
            Arguments.of("empty tool_calls", assistant(List.of())));
}
```

- [ ] **Step 8: Run the genuine Decision RED**

Run:

```bash
./mvnw -B -Dtest=DecisionTest test
```

Expected on unchanged `Decision` with gateway already green:

```text
Tests run: 33
Failures: 4
Errors: 0
Skipped: 0
BUILD FAILURE
```

The failing invocations are:

```text
mutable tool assistant snapshot
mutable final assistant snapshot
malformed final tool_calls accepted
non-empty final tool_calls accepted
```

The missing/null/empty final controls and all existing reconciliation tests
must pass.

- [ ] **Step 9: Implement the deeply immutable Decision boundary**

In `Decision`, add:

```java
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
```

Add constants:

```java
private static final String INVALID_ASSISTANT_MESSAGE =
        "Invalid assistant message";
private static final String INVALID_FINAL_ASSISTANT_MESSAGE =
        "Invalid final assistant message";
```

Add the snapshot operations:

```java
private static Map<String, Object> snapshotAssistant(
        Map<String, Object> source) {
    if (source == null) {
        throw invalidAssistantMessage();
    }
    try {
        return immutableMap(source);
    } catch (RuntimeException exception) {
        throw invalidAssistantMessage();
    }
}

private static Map<String, Object> immutableMap(Map<?, ?> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
            throw invalidAssistantMessage();
        }
        copy.put(key, immutableJsonValue(entry.getValue()));
    }
    return Collections.unmodifiableMap(copy);
}

private static Object immutableJsonValue(Object value) {
    if (value == null
            || value instanceof String
            || value instanceof Boolean
            || value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof BigInteger
            || value instanceof BigDecimal) {
        return value;
    }
    if (value instanceof Double number) {
        if (!Double.isFinite(number)) {
            throw invalidAssistantMessage();
        }
        return number;
    }
    if (value instanceof Float number) {
        if (!Float.isFinite(number)) {
            throw invalidAssistantMessage();
        }
        return number;
    }
    if (value instanceof Map<?, ?> map) {
        return immutableMap(map);
    }
    if (value instanceof List<?> list) {
        List<Object> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            copy.add(immutableJsonValue(item));
        }
        return Collections.unmodifiableList(copy);
    }
    throw invalidAssistantMessage();
}

private static IllegalArgumentException invalidAssistantMessage() {
    return new IllegalArgumentException(INVALID_ASSISTANT_MESSAGE);
}

private static IllegalArgumentException invalidFinalAssistantMessage() {
    return new IllegalArgumentException(
            INVALID_FINAL_ASSISTANT_MESSAGE);
}
```

Do not retain caught exceptions as causes.

Replace `finalAnswer` with:

```java
public static Decision finalAnswer(
        String answer, Map<String, Object> assistantMessage) {
    Map<String, Object> snapshot;
    try {
        snapshot = snapshotAssistant(assistantMessage);
        Object rawToolCalls = snapshot.get("tool_calls");
        if (rawToolCalls != null
                && !ToolCall.parseAssistantToolCalls(rawToolCalls).isEmpty()) {
            throw invalidFinalAssistantMessage();
        }
    } catch (IllegalArgumentException exception) {
        throw invalidFinalAssistantMessage();
    }
    return new Decision(true, answer, List.of(), snapshot);
}
```

The catch deliberately converts malformed raw call errors to the final-specific
fixed message without retaining a cause.

In `tools`, snapshot before parsing and store only the snapshot:

```java
Map<String, Object> snapshot = snapshotAssistant(assistantMessage);
List<ToolCall> parsed = ToolCall.parseAssistantToolCalls(
        snapshot.get("tool_calls"));
if (!parsed.equals(executable)) {
    throw mismatchedToolCalls();
}
return new Decision(false, null, executable, snapshot);
```

Keep the existing null assistant/list mismatch behavior and exact
`Assistant tool calls do not match decision` message.

- [ ] **Step 10: Run Decision GREEN, audit, and commit**

Run:

```bash
./mvnw -B \
  -Dtest=DecisionTest,ToolCallTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest \
  test
```

Expected:

```text
Tests run: 68
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Count:

```text
DecisionTest 33
ToolCallTest 3
ContextTest 19
StreamingDecisionAssemblerTest 8
AgentRunnerFaultInjectionTest 5
```

Audit that source collections are not retained, every exposed collection is
deeply immutable, valid raw ordering/representation remains, final result
delivery is unchanged, and no raw input appears in an exception cause.

Stage exactly and commit:

```bash
git add -- \
  src/main/java/com/reagent/core/Decision.java \
  src/test/java/com/reagent/core/DecisionTest.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: freeze validated assistant decisions"
```

- [ ] **Step 11: Add cross-batch and ledger-identity integration regressions**

In `StateStoreIT`, add a three-invocation same-task reuse test:

```java
@ParameterizedTest(name = "rejects same-task reuse with {0}")
@MethodSource("sameTaskReusedCalls")
@Transactional
void rejectsSameTaskCrossBatchToolCallIdReuseWithoutMutation(
        String ignored, ToolCall original, ToolCall reused) {
    TaskEntity task = stateStore.createTask(
            "reject reused call id", "system prompt");
    TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
    stateStore.appendAssistant(token, assistantFor(original));
    long messagesBefore = messageRepository.countByTaskId(task.getId());
    long ledgerBefore = toolCallRepository.count();

    IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> stateStore.appendAssistant(token, assistantFor(reused)));

    assertEquals("Tool call id already exists", error.getMessage());
    assertEquals(
            messagesBefore,
            messageRepository.countByTaskId(task.getId()));
    assertEquals(ledgerBefore, toolCallRepository.count());
    ToolCallEntity row =
            toolCallRepository.findById(original.id()).orElseThrow();
    assertEquals(original.name(), row.getToolName());
    assertEquals(original.arguments(), row.getArguments());
    assertEquals(ToolCallStatus.PENDING, row.getStatus());
}

private static Stream<Arguments> sameTaskReusedCalls() {
    ToolCall original =
            new ToolCall("call-reused", "read_file", "{\"path\":\"A\"}");
    return Stream.of(
            Arguments.of("identical identity", original, original),
            Arguments.of(
                    "different name",
                    original,
                    new ToolCall(
                            "call-reused",
                            "list_dir",
                            "{\"path\":\"A\"}")),
            Arguments.of(
                    "different arguments",
                    original,
                    new ToolCall(
                            "call-reused",
                            "read_file",
                            "{\"path\":\"B\"}")));
}
```

Add a cross-task fixed-message control:

```java
@Test
@Transactional
void rejectsCrossTaskToolCallIdBeforeCurrentTaskMutation() {
    ToolCall call = new ToolCall(
            "call-cross-task", "read_file", "{}");
    TaskEntity owner =
            stateStore.createTask("owner", "system prompt");
    TaskRunToken ownerToken =
            stateStore.claim(owner.getId()).orElseThrow();
    stateStore.appendAssistant(ownerToken, assistantFor(call));

    TaskEntity other =
            stateStore.createTask("other", "system prompt");
    TaskRunToken otherToken =
            stateStore.claim(other.getId()).orElseThrow();
    long messagesBefore =
            messageRepository.countByTaskId(other.getId());

    IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> stateStore.appendAssistant(
                    otherToken, assistantFor(call)));

    assertEquals("Tool call id already exists", error.getMessage());
    assertEquals(
            messagesBefore,
            messageRepository.countByTaskId(other.getId()));
    assertEquals(owner.getId(),
            toolCallRepository.findById(call.id())
                    .orElseThrow()
                    .getTaskId());
}
```

Add three direct ledger-mutation mismatch invocations:

```java
@ParameterizedTest(name = "rejects ledger mismatch before {0}")
@MethodSource("ledgerIdentityMismatches")
@Transactional
void rejectsLedgerIdentityMismatchBeforeMutation(
        LedgerMutation mutation, ToolCall supplied) {
    ToolCall original = new ToolCall(
            "call-ledger-identity", "read_file", "{\"path\":\"A\"}");
    TaskEntity task = stateStore.createTask(
            "ledger identity", "system prompt");
    TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
    stateStore.appendAssistant(token, assistantFor(original));
    long messagesBefore = messageRepository.countByTaskId(task.getId());

    IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> {
                switch (mutation) {
                    case MARK_IN_PROGRESS ->
                            stateStore.markInProgress(token, supplied);
                    case RECORD_RESULT ->
                            stateStore.recordToolResult(
                                    token, supplied, "result");
                    case MARK_IN_DOUBT ->
                            stateStore.markInDoubt(
                                    token, supplied, "unknown");
                }
            });

    assertEquals(
            "Tool call identity does not match ledger",
            error.getMessage());
    ToolCallEntity row =
            toolCallRepository.findById(original.id()).orElseThrow();
    assertEquals(ToolCallStatus.PENDING, row.getStatus());
    assertEquals(0, row.getAttemptCount());
    assertEquals(null, row.getResult());
    assertEquals(
            messagesBefore,
            messageRepository.countByTaskId(task.getId()));
}

private static Stream<Arguments> ledgerIdentityMismatches() {
    return Stream.of(
            Arguments.of(
                    LedgerMutation.MARK_IN_PROGRESS,
                    new ToolCall(
                            "call-ledger-identity",
                            "list_dir",
                            "{\"path\":\"A\"}")),
            Arguments.of(
                    LedgerMutation.RECORD_RESULT,
                    new ToolCall(
                            "call-ledger-identity",
                            "read_file",
                            "{\"path\":\"B\"}")),
            Arguments.of(
                    LedgerMutation.MARK_IN_DOUBT,
                    new ToolCall(
                            "call-ledger-identity",
                            "list_dir",
                            "{\"path\":\"B\"}")));
}

private enum LedgerMutation {
    MARK_IN_PROGRESS,
    RECORD_RESULT,
    MARK_IN_DOUBT
}
```

Add the helper:

```java
private static Map<String, Object> assistantFor(ToolCall call) {
    return assistant(List.of(rawCall(
            true,
            call.id(),
            true,
            call.name(),
            call.arguments())));
}
```

- [ ] **Step 12: Run the genuine StateStore RED**

Run on the authorized Docker host if the sandbox denies loopback or Docker:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected with gateway and Decision green but unchanged `StateStore`:

```text
Surefire: 260 passed
StateStoreIT tests run: 25
StateStoreIT failures: 7
Errors: 0
Skipped: 0
BUILD FAILURE
```

The seven failures must be:

```text
three same-task reused batches accepted
cross-task error still contains data instead of the fixed message
three mismatched ledger mutations accepted
```

The existing 18 StateStoreIT invocations must pass. Environmental failures are
not RED.

- [ ] **Step 13: Enforce append uniqueness and ledger identity**

In `StateStore`, add fixed constants:

```java
private static final String TOOL_CALL_ID_ALREADY_EXISTS =
        "Tool call id already exists";
private static final String TOOL_CALL_OWNERSHIP_MISMATCH =
        "Tool call id belongs to another task";
private static final String TOOL_CALL_IDENTITY_MISMATCH =
        "Tool call identity does not match ledger";
```

In `appendAssistant`, replace `callsToCreate` with prevalidated parsed calls:

```java
for (ToolCall call : parsedCalls) {
    if (toolCallRepo.findById(call.id()).isPresent()) {
        throw new IllegalStateException(
                TOOL_CALL_ID_ALREADY_EXISTS);
    }
}

int sequence = appendMessage(
        task.getId(),
        "assistant",
        content,
        toolCallsJson,
        null,
        now);
List<ToolCallEntity> ledgerRows = parsedCalls.stream()
        .map(call -> new ToolCallEntity(
                call.id(),
                task.getId(),
                call.name(),
                call.arguments(),
                now,
                sequence))
        .toList();
toolCallRepo.saveAll(ledgerRows);
return sequence;
```

All lookups finish before `appendMessage`. A concurrent unique-key race still
rolls back the complete transaction.

Replace the data-bearing ownership error in `ownedToolCall`:

```java
if (existing.isPresent()
        && !taskId.equals(existing.orElseThrow().getTaskId())) {
    throw new IllegalStateException(
            TOOL_CALL_OWNERSHIP_MISMATCH);
}
```

Add:

```java
private Optional<ToolCallEntity> matchingOwnedToolCall(
        String taskId, ToolCall call) {
    Optional<ToolCallEntity> existing =
            ownedToolCall(taskId, call.id());
    if (existing.isPresent()) {
        ToolCallEntity row = existing.orElseThrow();
        if (!call.name().equals(row.getToolName())
                || !call.arguments().equals(row.getArguments())) {
            throw new IllegalStateException(
                    TOOL_CALL_IDENTITY_MISMATCH);
        }
    }
    return existing;
}
```

Use `matchingOwnedToolCall(task.getId(), call)` instead of
`ownedToolCall(task.getId(), call.id())` in:

```text
markInProgress
recordToolResult
markInDoubt
```

Keep the existing `orElseGet` compatibility creation path. The identity check
runs before calling `markInProgress`, `markDone`, or `markInDoubt`, and before
any result message append.

- [ ] **Step 14: Run StateStore GREEN, audit, and commit**

Run:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected:

```text
Surefire: 260 passed
StateStoreIT: 25 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Audit:

```text
all parsed IDs are absent before assistant append
same-task and cross-task reuse have one fixed message
stored name/arguments match before every mutation
recovery using the original call still reaches IN_PROGRESS and DONE
no task/call/name/argument appears in a new validation error
no schema/repository/migration change
```

Stage exactly and commit:

```bash
git add -- \
  src/main/java/com/reagent/persist/StateStore.java \
  src/test/java/com/reagent/persist/StateStoreIT.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: reject reused tool call identities"
```

- [ ] **Step 15: Run the combined affected and complete Task 9 Java gates**

Run:

```bash
./mvnw -B \
  -Dtest=McpGatewayContractTest,DecisionTest,AgentRunnerFaultInjectionTest \
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
./mvnw -B \
  -Dtest=McpGatewayContractTest,McpSdkCompatibilityTest,McpToolAdapterTest,ToolCallTest,DecisionTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest \
  test
```

Expected:

```text
Tests run: 131
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

This gate retains the serial/concurrent interrupt-ignoring regressions through
the existing coordinator/executor coverage without any lifecycle production
change.

- [ ] **Step 16: Run Python protocol and static gates unchanged**

From `services/agent-capabilities` run separately:

```bash
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

Return to the worktree root. Confirm no Python path, bytecode, or cache artifact
is tracked or staged.

- [ ] **Step 17: Run real protocol, fast, and stable-cache full CI**

Confirm no Cycle 4 diff under `services/agent-capabilities`, then inspect the
exact reusable image identity named in Global Constraints. Do not rebuild when
the exact image exists.

Run on the authorized host:

```bash
./mvnw -B -Dit.test=McpProtocolIT verify
```

Expected:

```text
Surefire: 260 passed
McpProtocolIT: 1 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Run:

```bash
./mvnw -B test
```

Expected:

```text
Tests run: 260
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Reconfirm the stable cache's exact ref, revision, and ten files, then run:

```bash
./mvnw -B \
  -Dreagent.test.hf-cache=/root/reagent/.worktrees/reagent-rag-mcp/.superpowers/sdd/hf-cache-task9 \
  -Pci verify
```

Expected:

```text
Surefire: 260 passed
Failsafe: 82 passed
Total: 342 passed
Failures: 0
Errors: 0
Skipped: 0
RagGatewayIT: passed
McpProtocolIT: passed
StateStoreIT: 25 passed
BUILD SUCCESS
```

- [ ] **Step 18: Audit dependency purity, exact scope, and security flows**

Run:

```bash
./mvnw -B dependency:tree \
  -Dincludes=io.modelcontextprotocol.sdk
```

Expected SDK lines only:

```text
io.modelcontextprotocol.sdk:mcp-core:jar:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:jar:2.0.0
BUILD SUCCESS
```

Read the literal Cycle 4 dispatch base from the plan-specific ledger and run:

```bash
git diff --check "${dispatch_base}"..HEAD
git diff --name-status "${dispatch_base}"..HEAD
git status --short --branch
```

Expected implementation paths exactly:

```text
src/main/java/com/reagent/core/Decision.java
src/main/java/com/reagent/mcp/OfficialMcpGateway.java
src/main/java/com/reagent/persist/StateStore.java
src/test/java/com/reagent/core/DecisionTest.java
src/test/java/com/reagent/mcp/McpGatewayContractTest.java
src/test/java/com/reagent/persist/StateStoreIT.java
```

Trace and confirm:

```text
failed discovery/reconnect -> session and discovery both invalid
later call -> current validated discovery before callTool
caller raw assistant -> immutable Decision snapshot
snapshot raw tools == immutable executable tools
final non-empty tools -> rejection before persistence
new assistant batch -> every call ID absent before append
recovery mutation -> stored name/arguments equal supplied call
final answer -> durable/client result but not log/span
```

Confirm no changed dependency/lock/config/migration/Python/UI/Compose/README,
no lifecycle production change, no `shutdownNow()`, no Task 10 path, and no
generated/cache/report/package path tracked or staged.

- [ ] **Step 19: Freeze the literal fix-range and full-range packages**

Read:

```bash
dispatch_base=$(sed -n 's/^Dispatch base: //p' \
  .superpowers/sdd/2026-07-25-task-9-final-review-cycle-4/progress.md)
fix_head=$(git rev-parse HEAD)
fix_short=$(git rev-parse --short HEAD)
```

Generate the exact fix-range package:

```bash
bash /root/.codex/plugins/cache/openai-curated-remote/superpowers/6.2.0/skills/subagent-driven-development/scripts/review-package \
  docs/superpowers/plans/2026-07-25-task-9-final-review-cycle-4.md \
  "${dispatch_base}" \
  "${fix_head}" \
  ".superpowers/sdd/2026-07-25-task-9-final-review-cycle-4/review-${dispatch_base:0:7}..${fix_short}.diff"
```

It must contain exactly three commits and the six implementation paths.

Generate the exact full-range package:

```bash
bash /root/.codex/plugins/cache/openai-curated-remote/superpowers/6.2.0/skills/subagent-driven-development/scripts/review-package \
  docs/superpowers/plans/2026-07-25-task-9-final-review-cycle-4.md \
  19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd \
  "${fix_head}" \
  ".superpowers/sdd/review-task-9-19c7ae4..${fix_short}.diff"
```

Provided no unplanned commit/path appears, expected full range:

```text
19 commits
45 paths
```

Measure and record for both packages:

```text
literal full base/head on line 1
commit count and ordered list
path count and diffstat
line count
byte count
SHA-256
git diff --check
ignored/untracked status
```

Do not reuse the prior `7e420fa` package identity.

- [ ] **Step 20: Finish evidence and runtime hygiene**

Append the complete three-finding adjudication, RED/GREEN outputs, three commit
SHAs, every gate, warnings, authorized-host reruns, scope/dependency/security
audits, and both package identities to the two flat ignored evidence files and
the implementation report.

Verify:

```bash
git rev-parse HEAD
git status --short --branch
git diff --check \
  19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..HEAD
ps -eo pid=,comm=,args=
docker ps --format '{{.ID}} {{.Image}} {{.Names}} {{.Status}}'
```

Expected: exact implementation HEAD, branch header only, no diff-check output,
no Maven/Java/Python/test/build process, and no running container.

Do not mark Task 9 complete. The controller-owned scoped re-review below is
still required.

---

## Controller Gate: Run the single scoped re-review

This gate is controller/reviewer work. Do not dispatch another implementer.

Generate or reuse only the exact literal fix-range package from Task 1 Step 19.
Dispatch one fresh read-only reviewer with:

- the confirmed Cycle 4 design and this plan;
- the implementation worker's complete RED/GREEN/gate report;
- the exact three-commit/six-path fix package;
- the full-range package identity;
- the lifecycle adjudication;
- the three original Important findings below.

The findings under verification are:

1. **Gateway stale/rejected state:** `OfficialMcpGateway.call` accepted non-empty
   discovery without a live session, while session close did not clear
   discovery. Exhausted retry, rejected replacement discovery, or failed
   public refresh could leave stale discovery paired with null/rejected state.
   The fix must invalidate session/discovery together, close rejected
   replacements, update readiness, and make a later logical call rediscover.

2. **Decision time-of-check/time-of-use and final tools:** `Decision.tools`
   froze the executable list but retained the mutable raw assistant map, so a
   custom client could change persistence/recovery after validation.
   `Decision.finalAnswer` also accepted a non-empty tool batch that StateStore
   could persist as unexecuted PENDING rows. The fix must deeply detach/freeze
   the validated raw JSON and reject malformed/non-empty final tools before
   persistence.

3. **Cross-batch `tool_call_id` reuse:** `StateStore.appendAssistant` silently
   skipped an existing same-task ledger row while appending a new assistant
   batch. Terminal rows could leave the latest batch permanently pending, and
   changed name/arguments could diverge from ledger identity. The fix must
   reject all existing IDs before append and verify stored name/arguments
   before ledger mutation.

The scoped reviewer must return:

```text
Finding 1: ADDRESSED or NOT ADDRESSED with file:line evidence
Finding 2: ADDRESSED or NOT ADDRESSED with file:line evidence
Finding 3: ADDRESSED or NOT ADDRESSED with file:line evidence
New Critical/Important breakage in the fix range: none or exact findings
Out-of-scope observations: none or exact non-blocking observations
Verdict: all findings addressed, no new Critical/Important breakage
```

Do not authorize another fix wave. If any finding is not addressed or new
Critical/Important breakage exists, record it, keep Task 9 blocked, and return
to the user.

If the scoped review is clean, use
`superpowers:verification-before-completion` and freshly verify:

```bash
git rev-parse HEAD
git status --short --branch
git diff --check \
  19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..HEAD
dispatch_base=$(sed -n 's/^Dispatch base: //p' \
  .superpowers/sdd/2026-07-25-task-9-final-review-cycle-4/progress.md)
final_short=$(git rev-parse --short HEAD)
fix_package=".superpowers/sdd/2026-07-25-task-9-final-review-cycle-4/review-${dispatch_base:0:7}..${final_short}.diff"
full_package=".superpowers/sdd/review-task-9-19c7ae4..${final_short}.diff"
sha256sum "${fix_package}" "${full_package}"
docker ps --format '{{.ID}} {{.Image}} {{.Names}} {{.Status}}'
```

Also confirm no relevant process, exact final test evidence, all three commit
ranges, dependency purity, lifecycle supersession, and Task 10 inactivity.
Then mark Task 9 complete. Do not start Task 10 in the same cycle.
