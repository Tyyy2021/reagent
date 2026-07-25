# Task 9 Final-Review Cycle 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two remaining Task 9 Important findings by strictly reconciling raw assistant tool calls before decision, persistence, or restored execution and by removing complete final answers from telemetry logs.

**Architecture:** `ToolCall` owns one strict raw-protocol parser that returns an ordered immutable call list. `Decision`, `Context`, and `StateStore` consume that parser at their respective trust boundaries while preserving valid raw assistant JSON and exact durable/model/client results. `AgentRunner` keeps final-answer delivery unchanged but emits only fixed completion metadata to logs.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Jackson 2, JUnit 5, Mockito, Logback test appenders, Maven Wrapper, MySQL 8 and Redis 8 through Testcontainers, Python 3.12/uv, official MCP Java SDK 2.0.0.

## Global Constraints

- Work only in `/root/reagent/.worktrees/reagent-rag-mcp` on branch
  `feature/reagent-rag-mcp-interview`.
- Approved design baseline is exact
  `2a302b252c5fd511c91c01b9acfe26d4f6f2a101`.
- Before Task 1 dispatch, the controller must record the current clean,
  docs-only HEAD in this plan's ignored ledger as
  `Dispatch base: <40-character SHA>`. That frozen SHA is the Cycle 3
  implementation base and governs every exact product/test scope diff.
- Binding specification:
  `docs/superpowers/specs/2026-07-25-task-9-final-review-cycle-3-design.md`,
  257 lines, SHA-256
  `f8237b4eb21b4d8d6bd381112108efed186845051b89b6889adf52c7b2a32332`.
- Do not amend, reset, rebase, clean, push, merge, or start Task 10.
- Do not modify executor lifecycle, timeout, cancellation, or ownership code.
  The user-authorized lifecycle Important remains withdrawn; retain its serial
  and concurrent regressions.
- No test, production, configuration, dependency, migration, Python, UI,
  Compose, profile, approval, README, or Task 10 change outside the paths
  listed in this plan.
- Every production change requires an assertion-based focused RED on unchanged
  production. Compilation, fixture, permission, Docker, or network failure is
  not behavioral RED.
- Raw call `id` and `function.name` must be actual strings. Never coerce them
  with `String.valueOf` or `JsonNode.asText`.
- Existing identity syntax remains exact:
  `^[A-Za-z0-9_-]{1,255}$` for IDs and
  `^[A-Za-z0-9_-]{1,64}$` for names.
- Reject missing, null, numeric, boolean, collection, object, duplicate, or
  mismatched identity with fixed generic messages that do not echo input.
- String arguments remain exact. Structured JSON arguments remain supported
  as compact JSON. Missing/null arguments keep the existing `"{}"` fallback.
- Preserve full valid assistant JSON, durable task result, ledger/model
  context, synchronous return, and authenticated client-facing `COMPLETED`
  event. Do not duplicate final-answer content in logs or spans.
- Use only `apply_patch` for file edits. Stage only explicit paths.
- Append command/evidence records only to ignored
  `.superpowers/sdd/task-9-report.md` and `.superpowers/sdd/progress.md`.
- Use authorized host execution unchanged when loopback or Docker access is
  denied by the restricted sandbox; record both attempts and do not alter
  fixtures to bypass the boundary.
- Stable full-CI cache remains
  `/root/reagent/.worktrees/reagent-rag-mcp/.superpowers/sdd/hf-cache-task9`
  with native 40-byte `refs/main`, exact ten snapshot files, revision
  `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`, and established checksum
  `1df98b726e1527bd8f513366368c1f3b8b3425922d1458069f00fa7420023b6a`.
- Task 9 completes only after a fresh no-history full-range review returns
  Critical 0 and Important 0.

---

## File Map

### Core raw-call boundary

- Modify `src/main/java/com/reagent/core/ToolCall.java`
  - add the single raw assistant-call parser and argument normalization;
  - retain the existing compact-constructor identity checks.
- Modify `src/main/java/com/reagent/core/Decision.java`
  - reconcile raw ordered calls with the executable call list;
  - copy the executable list immutably.
- Modify `src/main/java/com/reagent/core/Context.java`
  - parse restored assistant calls through the shared boundary;
  - require actual string tool-result correlation IDs.
- Create `src/test/java/com/reagent/core/DecisionTest.java`
  - cover raw type rejection, duplicate IDs, batch mismatch, structured
    arguments, and immutable executable calls.
- Modify `src/test/java/com/reagent/core/ContextTest.java`
  - cover missing/null/numeric/boolean/collection/object restored identity and
    result correlation.

### Persistence defense in depth

- Modify `src/main/java/com/reagent/persist/StateStore.java`
  - validate the complete raw batch before message or ledger mutation;
  - create ledger rows only from parsed `ToolCall` values.
- Modify `src/test/java/com/reagent/persist/StateStoreIT.java`
  - prove malformed identity creates neither an assistant row nor a ledger row;
  - preserve structured-argument persistence/recovery.

### Completion log isolation

- Modify `src/main/java/com/reagent/core/AgentRunner.java`
  - replace raw final-answer logging with fixed task completion metadata.
- Modify `src/test/java/com/reagent/core/AgentRunnerFaultInjectionTest.java`
  - capture logs and prove exact durable/client delivery with no log sentinel.

No other production or test path is allowed in Cycle 3.

---

### Task 1: Verify the clean dispatch boundary

**Files:**

- Read:
  `docs/superpowers/specs/2026-07-25-task-9-final-review-cycle-3-design.md`
- Read:
  `.superpowers/sdd/task-9-report.md`
- Read:
  `.superpowers/sdd/task-9-brief.md`
- Read:
  `.superpowers/sdd/task-9-resume-brief.md`
- Read:
  `.superpowers/sdd/task-9-review-fix-brief.md`
- Read:
  `.superpowers/sdd/2026-07-25-task-9-final-review-cycle-3/progress.md`
- Modify evidence only:
  `.superpowers/sdd/task-9-report.md`
- Modify evidence only:
  `.superpowers/sdd/progress.md`

**Interfaces:**

- Consumes: the exact clean docs-only dispatch base frozen in this plan's
  ignored ledger after the approved design and implementation plan.
- Produces: verified 17-test behavioral baseline and unchanged product scope.

- [ ] **Step 1: Read all binding inputs**

Read the complete files listed above, including the reviewer verdict and
user-authorized lifecycle adjudication. Do not use only summaries.

- [ ] **Step 2: Verify exact branch, HEAD, and clean state**

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
the exact 40-character SHA recorded by the ledger's "Dispatch base:" line
branch header only
no diff-check output
```

If any tracked product/test change exists, stop and audit it. Do not reset or
clean.

- [ ] **Step 3: Verify runtime hygiene and stable cache**

Run:

```bash
ps -eo pid=,comm=,args= | awk \
  '$2 ~ /^(java|python|python3|uv|docker)$/ \
   && $0 ~ /(org\.codehaus\.plexus|surefire|failsafe|pytest|uv run|docker build)/ \
   {print}'
docker ps --format '{{.ID}} {{.Image}} {{.Names}}'
wc -c .superpowers/sdd/hf-cache-task9/models--sentence-transformers--all-MiniLM-L6-v2/refs/main
find -L \
  .superpowers/sdd/hf-cache-task9/models--sentence-transformers--all-MiniLM-L6-v2/snapshots/1110a243fdf4706b3f48f1d95db1a4f5529b4d41 \
  -type f -printf '%P\n' | sort
```

Expected: no build/test process, no running container, a 40-byte ref, and the
exact ten files listed in Task 7 Step 4.

- [ ] **Step 4: Run the unchanged focused baseline**

Run:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest \
  test
```

Expected:

```text
Tests run: 17
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 5: Record the preflight**

Append exact HEAD, status, cache/hygiene checks, command, counts, elapsed time,
and any known warning to both ignored evidence files. Do not commit them.

---

### Task 2: Add strict Decision and restored-context behavior tests

**Files:**

- Create:
  `src/test/java/com/reagent/core/DecisionTest.java`
- Modify:
  `src/test/java/com/reagent/core/ContextTest.java`
- Test:
  `src/test/java/com/reagent/core/ToolCallTest.java`
- Test:
  `src/test/java/com/reagent/llm/StreamingDecisionAssemblerTest.java`

**Interfaces:**

- Consumes:
  `Decision.tools(Map<String, Object>, List<ToolCall>)` and
  `Context.pendingToolCalls()`.
- Produces: an assertion RED defining strict raw identity, ordered batch
  equality, duplicate rejection, immutable executable calls, and restored
  result-ID type safety.

- [ ] **Step 1: Create raw-call test helpers**

Create `DecisionTest` with helpers that can represent missing and null map
fields without `Map.of`:

```java
private static Map<String, Object> rawCall(
        boolean includeId,
        Object id,
        boolean includeName,
        Object name,
        Object arguments) {
    Map<String, Object> function = new LinkedHashMap<>();
    if (includeName) {
        function.put("name", name);
    }
    function.put("arguments", arguments);

    Map<String, Object> call = new LinkedHashMap<>();
    if (includeId) {
        call.put("id", id);
    }
    call.put("type", "function");
    call.put("function", function);
    return call;
}

private static Map<String, Object> assistant(List<Map<String, Object>> calls) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "assistant");
    message.put("content", null);
    message.put("tool_calls", calls);
    return message;
}
```

- [ ] **Step 2: Add twelve non-string identity and five raw-shape cases**

Add a `@ParameterizedTest` backed by a `Stream<Arguments>` for:

```text
missing, null, numeric, boolean, list, and map id
missing, null, numeric, boolean, list, and map name
```

Each case calls the existing `Decision.tools` with a syntactically valid
executable call and asserts:

```java
IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> Decision.tools(rawAssistant, executableCalls));
assertEquals("Invalid assistant tool calls", error.getMessage());
```

No assertion or message may include the rejected value.

In the same parameterized test group, add these five malformed raw assistant
shapes:

```text
missing tool_calls
tool_calls is a map rather than a list
list item is not a map
call has no function
function is not a map
```

Each must throw exact `Invalid assistant tool calls`.

- [ ] **Step 3: Add five raw/executable mismatch cases**

Add a second parameterized test covering exact ordered mismatch in:

```text
id
name
normalized arguments
call count
two-call order
```

Use only valid `ToolCall` values so the failure is specifically raw/executable
reconciliation:

```java
IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> Decision.tools(rawAssistant, executableCalls));
assertEquals(
        "Assistant tool calls do not match decision",
        error.getMessage());
```

- [ ] **Step 4: Add duplicate and structured-argument cases**

Add one test whose raw and executable lists both contain the same duplicate ID.
It must throw exact `Invalid assistant tool calls`.

Add two passing controls for missing and explicit-null raw arguments; each must
reconcile to executable `"{}"`.

Add one structured-argument/immutability test:

```java
Map<String, Object> raw = assistant(List.of(rawCall(
        true,
        "call-structured",
        true,
        "read_file",
        Map.of("path", "README.md"))));
List<ToolCall> executable = new ArrayList<>(List.of(
        new ToolCall(
                "call-structured",
                "read_file",
                "{\"path\":\"README.md\"}")));

Decision decision = Decision.tools(raw, executable);
executable.clear();

assertEquals(
        List.of(new ToolCall(
                "call-structured",
                "read_file",
                "{\"path\":\"README.md\"}")),
        decision.getToolCalls());
assertThrows(
        UnsupportedOperationException.class,
        () -> decision.getToolCalls().add(
                new ToolCall("call-other", "read_file", "{}")));
```

On unchanged production this test fails because `Decision` retains the mutable
input list.

- [ ] **Step 5: Add restored identity and correlation cases**

In `ContextTest`, add a parameterized test using the same twelve
missing/null/numeric/boolean/list/map ID/name cases. Build the raw assistant map directly,
append it to `Context`, and assert `pendingToolCalls()` throws exact
`Invalid assistant tool calls`.

Add four more restored assistant cases for non-list `tool_calls`, a non-map
list item, missing `function`, and non-map `function`. Missing `tool_calls`
itself remains a valid non-tool assistant message and is not selected as a
pending batch.

Add one separate test:

```java
Context context = new Context("system");
context.addAssistant(assistantWithCall(
        "123",
        "read_file",
        "{}"));
context.messages().add(Map.of(
        "role", "tool",
        "tool_call_id", 123,
        "content", "done"));

IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        context::pendingToolCalls);
assertEquals("Invalid tool result call id", error.getMessage());
```

- [ ] **Step 6: Run the genuine unit RED**

Run:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,DecisionTest,ContextTest,StreamingDecisionAssemblerTest \
  test
```

Expected on unchanged production:

```text
Tests run: 56
Failures: 41
Errors: 0
Skipped: 0
BUILD FAILURE
```

The 41 failures must be the twelve Decision identity-type cases, five
Decision raw-shape cases, five mismatch cases, duplicate-ID case,
mutable-list case, twelve restored identity cases, four restored raw-shape
cases, and one numeric result-correlation case. The two argument-fallback
controls plus existing regex rejection, message snapshot, fragmented
streaming, and valid boundary controls must pass.

Compilation failure, different failure categories, or fixture failure is not a
valid RED. Diagnose before production edits.

- [ ] **Step 7: Confirm test-only scope and record RED**

Run:

```bash
git status --short
git diff --check
git diff --name-only
```

Expected changed paths only:

```text
src/test/java/com/reagent/core/ContextTest.java
src/test/java/com/reagent/core/DecisionTest.java
```

Append exact RED evidence to the ignored report and ledger.

---

### Task 3: Implement the shared parser and core consumers

**Files:**

- Modify:
  `src/main/java/com/reagent/core/ToolCall.java`
- Modify:
  `src/main/java/com/reagent/core/Decision.java`
- Modify:
  `src/main/java/com/reagent/core/Context.java`
- Test:
  `src/test/java/com/reagent/core/DecisionTest.java`
- Test:
  `src/test/java/com/reagent/core/ContextTest.java`

**Interfaces:**

- Produces:
  `ToolCall.parseAssistantToolCalls(Object rawToolCalls) -> List<ToolCall>`.
- Produces fixed errors:
  `Invalid assistant tool calls`,
  `Assistant tool calls do not match decision`, and
  `Invalid tool result call id`.
- Preserves existing `ToolCall(String id, String name, String arguments)`.

- [ ] **Step 1: Add the single raw protocol parser**

In `ToolCall`, add Jackson/list/map/set imports, one private fixed error
constant, one private mapper, and this public operation:

```java
public static List<ToolCall> parseAssistantToolCalls(Object rawToolCalls) {
    if (!(rawToolCalls instanceof List<?> rawList)) {
        throw invalidAssistantToolCalls();
    }

    List<ToolCall> parsed = new ArrayList<>(rawList.size());
    Set<String> ids = new HashSet<>();
    for (Object rawCall : rawList) {
        if (!(rawCall instanceof Map<?, ?> call)
                || !(call.get("id") instanceof String id)
                || !(call.get("function") instanceof Map<?, ?> function)
                || !(function.get("name") instanceof String name)) {
            throw invalidAssistantToolCalls();
        }

        ToolCall parsedCall = new ToolCall(
                id,
                name,
                normalizeArguments(function.get("arguments")));
        if (!ids.add(parsedCall.id())) {
            throw invalidAssistantToolCalls();
        }
        parsed.add(parsedCall);
    }
    return List.copyOf(parsed);
}

private static String normalizeArguments(Object arguments) {
    if (arguments == null) {
        return "{}";
    }
    if (arguments instanceof String stringArguments) {
        return stringArguments;
    }
    try {
        return PROTOCOL_MAPPER.writeValueAsString(arguments);
    } catch (JsonProcessingException exception) {
        throw invalidAssistantToolCalls();
    }
}

private static IllegalArgumentException invalidAssistantToolCalls() {
    return new IllegalArgumentException("Invalid assistant tool calls");
}
```

Use a private `ObjectMapper PROTOCOL_MAPPER = new ObjectMapper()`. Do not retain
the `JsonProcessingException` as a cause because provider data can appear in
its message.

- [ ] **Step 2: Reconcile Decision raw and executable calls**

Replace `Decision.tools` with:

```java
public static Decision tools(
        Map<String, Object> assistantMessage,
        List<ToolCall> toolCalls) {
    if (assistantMessage == null || toolCalls == null) {
        throw mismatchedToolCalls();
    }
    List<ToolCall> executable;
    try {
        executable = List.copyOf(toolCalls);
    } catch (NullPointerException exception) {
        throw mismatchedToolCalls();
    }
    List<ToolCall> parsed = ToolCall.parseAssistantToolCalls(
            assistantMessage.get("tool_calls"));
    if (!parsed.equals(executable)) {
        throw mismatchedToolCalls();
    }
    return new Decision(false, null, executable, assistantMessage);
}

private static IllegalArgumentException mismatchedToolCalls() {
    return new IllegalArgumentException(
            "Assistant tool calls do not match decision");
}
```

Do not retain the caught `NullPointerException` as a cause.

- [ ] **Step 3: Replace Context coercion**

In `Context.pendingToolCalls`, validate result IDs and parse the full raw batch:

```java
Set<String> answered = new HashSet<>();
for (int i = idx + 1; i < messages.size(); i++) {
    Map<String, Object> message = messages.get(i);
    if ("tool".equals(message.get("role"))) {
        Object rawId = message.get("tool_call_id");
        if (!(rawId instanceof String id)) {
            throw new IllegalArgumentException(
                    "Invalid tool result call id");
        }
        answered.add(id);
    }
}

return ToolCall.parseAssistantToolCalls(
                messages.get(idx).get("tool_calls"))
        .stream()
        .filter(call -> !answered.contains(call.id()))
        .toList();
```

Remove only the superseded manual call loop. Preserve last-assistant selection
and answered-call filtering.

- [ ] **Step 4: Run the exact unit GREEN**

Run the same command from Task 2:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,DecisionTest,ContextTest,StreamingDecisionAssemblerTest \
  test
```

Expected:

```text
Tests run: 56
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 5: Audit core behavior and messages**

Read the complete five-file diff. Confirm:

```text
no String.valueOf/asText identity coercion remains in these boundaries
raw assistant map is still stored on Decision
executable call list is immutable
valid unknown names and exact max lengths still pass
structured arguments are compact JSON
no exception message/cause includes raw input
```

- [ ] **Step 6: Commit the core boundary**

Stage exactly:

```bash
git add -- \
  src/main/java/com/reagent/core/ToolCall.java \
  src/main/java/com/reagent/core/Decision.java \
  src/main/java/com/reagent/core/Context.java \
  src/test/java/com/reagent/core/DecisionTest.java \
  src/test/java/com/reagent/core/ContextTest.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: validate raw assistant tool call batches"
```

Verify the post-commit worktree is clean before Task 4.

---

### Task 4: Prove and implement StateStore defense in depth

**Files:**

- Modify test first:
  `src/test/java/com/reagent/persist/StateStoreIT.java`
- Modify after RED:
  `src/main/java/com/reagent/persist/StateStore.java`

**Interfaces:**

- Consumes:
  `ToolCall.parseAssistantToolCalls(Object)`.
- Preserves:
  `StateStore.appendAssistant(TaskRunToken, Map<String, Object>) -> int`.
- Produces: all-or-nothing validated assistant and pending-ledger persistence.

- [ ] **Step 1: Add repository observation and raw-map helpers**

Autowire `ToolCallRepository` in `StateStoreIT`. Add these local helpers; do
not call the new parser from the fixture:

```java
private static Map<String, Object> rawCall(
        boolean includeId,
        Object id,
        boolean includeName,
        Object name,
        Object arguments) {
    Map<String, Object> function = new LinkedHashMap<>();
    if (includeName) {
        function.put("name", name);
    }
    function.put("arguments", arguments);

    Map<String, Object> call = new LinkedHashMap<>();
    if (includeId) {
        call.put("id", id);
    }
    call.put("type", "function");
    call.put("function", function);
    return call;
}

private static Map<String, Object> assistant(
        Object rawToolCalls) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "assistant");
    message.put("content", null);
    message.put("tool_calls", rawToolCalls);
    return message;
}
```

- [ ] **Step 2: Add sixteen malformed persistence cases**

Add a transactional parameterized test for
missing/null/numeric/boolean/list/map ID and
missing/null/numeric/boolean/list/map name. For each invocation:

```java
TaskEntity task = stateStore.createTask(
        "reject malformed assistant " + caseName,
        "system prompt");
TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
long messagesBefore = messageRepository.countByTaskId(task.getId());
long ledgerBefore = toolCallRepository.count();

IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> stateStore.appendAssistant(token, rawAssistant));

assertEquals("Invalid assistant tool calls", error.getMessage());
assertEquals(
        messagesBefore,
        messageRepository.countByTaskId(task.getId()));
assertEquals(ledgerBefore, toolCallRepository.count());
```

Use a unique valid string call ID for every invalid-name case. Keep the test
transaction rollback-enabled.

Add four structural cases: explicit non-list `tool_calls`, non-map list item,
missing `function`, and non-map `function`. StateStore must still allow a
normal final assistant message with no `tool_calls` field.

- [ ] **Step 3: Add structured-argument persistence control**

Add one integration test that persists a valid raw call with:

```java
"arguments", Map.of("path", "README.md")
```

After `appendAssistant`, flush and clear, then assert:

```java
assertEquals(
        "{\"path\":\"README.md\"}",
        toolCallRepository.findById(callId).orElseThrow().getArguments());
assertEquals(
        List.of(new ToolCall(
                callId,
                "read_file",
                "{\"path\":\"README.md\"}")),
        stateStore.loadContext(task.getId()).pendingToolCalls());
```

- [ ] **Step 4: Run the genuine persistence RED**

Run on the authorized Docker host if the sandbox denies the Docker socket:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected with Task 3 core code but unchanged `StateStore`:

```text
Surefire tests: 249 passed
StateStoreIT tests run: 18
StateStoreIT failures: 16
Errors: 0
Skipped: 0
BUILD FAILURE
```

The existing reconstruction test and structured-argument control must pass.
The twelve identity failures must be missing the expected
`IllegalArgumentException`; the four structural failures must show either no
rejection or the wrong incidental exception. Together they prove that current
persistence lacks one strict whole-batch boundary.

- [ ] **Step 5: Parse the complete batch before persistence**

In `StateStore.appendAssistant`, replace the manual raw-map loop with:

```java
Object rawToolCalls = assistantMessage.get("tool_calls");
List<ToolCall> parsedCalls = rawToolCalls == null
        ? List.of()
        : ToolCall.parseAssistantToolCalls(rawToolCalls);
String toolCallsJson = rawToolCalls == null
        ? null
        : toJson(rawToolCalls);

Map<String, ToolCall> callsToCreate = new LinkedHashMap<>();
for (ToolCall call : parsedCalls) {
    if (ownedToolCall(task.getId(), call.id()).isEmpty()) {
        callsToCreate.put(call.id(), call);
    }
}
```

Create `ToolCallEntity` rows from each `ToolCall`'s exact `id`, `name`, and
`arguments`:

```java
int sequence = appendMessage(
        task.getId(),
        "assistant",
        content,
        toolCallsJson,
        null,
        now);
List<ToolCallEntity> ledgerRows = callsToCreate.values().stream()
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

Remove the now-unused `asArguments` helper and `PendingToolCall` record. Keep
ownership prevalidation for the whole batch before `appendMessage`.

- [ ] **Step 6: Run the exact persistence GREEN**

Rerun:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected:

```text
Surefire tests: 249 passed
StateStoreIT tests: 18 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 7: Commit the persistence boundary**

Stage exactly:

```bash
git add -- \
  src/main/java/com/reagent/persist/StateStore.java \
  src/test/java/com/reagent/persist/StateStoreIT.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: validate tool calls before persistence"
```

Verify clean status and record RED/GREEN counts and real MySQL/Docker evidence.

---

### Task 5: Prove and remove final-answer log leakage

**Files:**

- Modify test first:
  `src/test/java/com/reagent/core/AgentRunnerFaultInjectionTest.java`
- Modify after RED:
  `src/main/java/com/reagent/core/AgentRunner.java`

**Interfaces:**

- Consumes:
  `AgentRunner.run(String goal, String profileId)`.
- Preserves:
  `StateStore.completeTask`, synchronous `RunResult`, and client-facing
  `TaskEvent.Type.COMPLETED`.
- Produces: fixed task completion log metadata with no answer-derived content.

- [ ] **Step 1: Add Logback capture helpers**

Import and add the established helpers used by
`ToolBatchCoordinatorTest`:

```java
private static ListAppender<ILoggingEvent> captureLogs(Class<?> type) {
    Logger logger = (Logger) LoggerFactory.getLogger(type);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
}

private static void detachLogs(
        Class<?> type,
        ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    appender.stop();
}

private static String capturedLogText(
        ListAppender<ILoggingEvent> appender) {
    StringBuilder captured = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
        captured.append(event.getFormattedMessage());
        if (event.getThrowableProxy() != null) {
            captured.append(
                    ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
    }
    return captured.toString();
}
```

- [ ] **Step 2: Add the final-answer sentinel test**

Add:

```java
@Test
void finalAnswerStaysDurableAndClientVisibleButNotLogged(
        @TempDir Path workspace) {
    String answer =
            "https://secret.example body=FINAL_BODY_SENTINEL "
            + "credential=FINAL_CREDENTIAL_SENTINEL "
            + "log=FINAL_LOG_LINE_SENTINEL";
    Fixture fixture = fixture(
            workspace,
            Decision.finalAnswer(
                    answer,
                    Map.of(
                            "role", "assistant",
                            "content", answer)));
    ListAppender<ILoggingEvent> logs = captureLogs(AgentRunner.class);
    try {
        AgentRunner.RunResult result =
                fixture.runner(FaultInjector.none()).run("goal", "coding");

        assertEquals(answer, result.result());
        verify(fixture.stateStore).completeTask(fixture.token, answer);
        verify(fixture.transport).publish(
                fixture.token,
                TaskEvent.Type.COMPLETED,
                Map.of("result", answer));

        String captured = capturedLogText(logs);
        assertFalse(captured.contains("FINAL_BODY_SENTINEL"));
        assertFalse(captured.contains("FINAL_CREDENTIAL_SENTINEL"));
        assertFalse(captured.contains("FINAL_LOG_LINE_SENTINEL"));
    } finally {
        detachLogs(AgentRunner.class, logs);
    }
}
```

- [ ] **Step 3: Run the genuine log RED**

Run:

```bash
./mvnw -B -Dtest=AgentRunnerFaultInjectionTest test
```

Expected on unchanged `AgentRunner`:

```text
Tests run: 5
Failures: 1
Errors: 0
Skipped: 0
BUILD FAILURE
```

The only failure must be the captured log containing the sentinel. Exact
durable, synchronous, and completion-event controls must pass.

- [ ] **Step 4: Replace only the raw completion log**

In `AgentRunner`, change:

```java
log.info("====== 任务完成 ======\n{}", decision.getAnswer());
```

to fixed metadata:

```java
log.info("任务 {} 完成", taskId);
```

Do not change `completeTask`, `COMPLETED`, or return-value lines.

- [ ] **Step 5: Run the exact log GREEN**

Rerun:

```bash
./mvnw -B -Dtest=AgentRunnerFaultInjectionTest test
```

Expected:

```text
Tests run: 5
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 6: Commit the log boundary**

Stage exactly:

```bash
git add -- \
  src/main/java/com/reagent/core/AgentRunner.java \
  src/test/java/com/reagent/core/AgentRunnerFaultInjectionTest.java
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: keep final answers out of logs"
```

Verify clean status and append exact RED/GREEN evidence.

---

### Task 6: Run aggregate focused, integration, protocol, and static gates

**Files:**

- Test only; no product change is expected.
- Append evidence:
  `.superpowers/sdd/task-9-report.md`
- Append evidence:
  `.superpowers/sdd/progress.md`

**Interfaces:**

- Consumes: the three Cycle 3 implementation commits.
- Produces: focused proof for all Task 9 Java, persistence, Python protocol,
  and static contracts.

- [ ] **Step 1: Run the affected aggregate Java gate**

Run:

```bash
./mvnw -B \
  -Dtest=ToolCallTest,DecisionTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest \
  test
```

Expected:

```text
Tests run: 85
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

This must retain both interrupt-ignoring lifecycle tests without production
lifecycle changes.

- [ ] **Step 2: Run the complete Task 9 Java focus**

Run:

```bash
./mvnw -B \
  -Dtest=McpGatewayContractTest,ToolCallTest,DecisionTest,ContextTest,StreamingDecisionAssemblerTest,AgentRunnerFaultInjectionTest,ToolBatchCoordinatorTest,ToolExecutorTracingTest \
  test
```

Expected:

```text
Tests run: 113
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 3: Re-run the persistence integration**

Run on the Docker-capable host:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected:

```text
Surefire tests: 250 passed
StateStoreIT: 18 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 4: Run Python MCP protocol and static gates**

From `services/agent-capabilities`:

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

Return to the worktree root. No Python file may have changed.

- [ ] **Step 5: Record every focused gate**

Record exact counts, elapsed times, authorized-host reruns, warnings, and clean
status. Any unexpected failure starts systematic debugging; do not alter
unrelated production.

---

### Task 7: Run real protocol, fast, and stable-cache full CI

**Files:**

- Test only; no product change is expected.
- Inspect:
  `services/agent-capabilities`
- Inspect:
  `.superpowers/sdd/hf-cache-task9`

**Interfaces:**

- Consumes: clean Cycle 3 implementation HEAD.
- Produces: fresh real Java SDK protocol and 325-test full-regression evidence.

- [ ] **Step 1: Prove the Python image source is unchanged**

Run:

```bash
dispatch_base=$(sed -n 's/^Dispatch base: //p' \
  .superpowers/sdd/2026-07-25-task-9-final-review-cycle-3/progress.md)
git diff --name-only "${dispatch_base}"..HEAD -- \
  services/agent-capabilities
docker image inspect reagent-agent-capabilities:task9
```

Expected:

```text
no Git path output
image ID sha256:c53606b5a3603a9c1e6fa49ebf540dfb6f21f2000b17a2a8f69bb72c428c8064
created 2026-07-24T23:44:49.300087564+08:00
size 8781245552 bytes
```

If the exact image is absent, rebuild the unchanged source with:

```bash
docker build \
  -t reagent-agent-capabilities:task9 \
  services/agent-capabilities
```

Record the resulting immutable identity. Do not claim a stale or different
tag without inspection.

- [ ] **Step 2: Run the real protocol gate**

Run:

```bash
./mvnw -B -Dit.test=McpProtocolIT verify
```

Expected:

```text
Surefire tests: 250 passed
McpProtocolIT: 1 passed
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 3: Run the fresh fast gate**

Run:

```bash
./mvnw -B test
```

Expected:

```text
Tests run: 250
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 4: Verify the stable cache before full CI**

Confirm the native ref is 40 bytes and the resolved snapshot contains exactly:

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

Do not recreate or substitute the model, revision, corpus, queries, or
threshold.

- [ ] **Step 5: Run full CI**

Run:

```bash
./mvnw -B \
  -Dreagent.test.hf-cache=/root/reagent/.worktrees/reagent-rag-mcp/.superpowers/sdd/hf-cache-task9 \
  -Pci verify
```

Expected:

```text
Surefire tests: 250 passed
Failsafe tests: 75 passed
Total: 325 passed
Failures: 0
Errors: 0
Skipped: 0
RagGatewayIT: passed
McpProtocolIT: passed
BUILD SUCCESS
```

- [ ] **Step 6: Confirm post-gate runtime hygiene**

Verify no Maven/Java/Python/test/build process and no running Docker container
remains. Do not remove ignored evidence or the verified cache.

---

### Task 8: Audit scope, security flows, and immutable review inputs

**Files:**

- Inspect all nine Cycle 3 product/test paths.
- Append evidence:
  `.superpowers/sdd/task-9-report.md`
- Append evidence:
  `.superpowers/sdd/progress.md`
- Create ignored package:
  `.superpowers/sdd/review-task-9-19c7ae4..${review_head}.diff`

**Interfaces:**

- Consumes: fresh green implementation HEAD.
- Produces: exact clean full-range review package and auditable completion
  state.

- [ ] **Step 1: Verify dependency purity**

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

- [ ] **Step 2: Verify exact Cycle 3 scope**

Run:

```bash
dispatch_base=$(sed -n 's/^Dispatch base: //p' \
  .superpowers/sdd/2026-07-25-task-9-final-review-cycle-3/progress.md)
git diff --check \
  "${dispatch_base}"..HEAD
git diff --name-status \
  "${dispatch_base}"..HEAD
git status --short --branch
```

Expected implementation range paths exactly:

```text
src/main/java/com/reagent/core/AgentRunner.java
src/main/java/com/reagent/core/Context.java
src/main/java/com/reagent/core/Decision.java
src/main/java/com/reagent/core/ToolCall.java
src/main/java/com/reagent/persist/StateStore.java
src/test/java/com/reagent/core/AgentRunnerFaultInjectionTest.java
src/test/java/com/reagent/core/ContextTest.java
src/test/java/com/reagent/core/DecisionTest.java
src/test/java/com/reagent/persist/StateStoreIT.java
```

The worktree and index must be clean because each implementation task already
has its own exact commit.

- [ ] **Step 3: Read the full production and test diff**

Trace these flows end to end:

```text
streaming delta -> Decision.tools -> StateStore.appendAssistant
non-streaming JSON -> Decision.tools -> StateStore.appendAssistant
persisted JSON -> Context.pendingToolCalls -> coordinator/executor
final answer -> durable/client result, but not log/span
```

Confirm actual-string checks occur before persistence/execution, batch
identity/arguments/order/count agree, duplicate IDs fail, error messages do
not echo data, and no `shutdownNow()` or lifecycle production change appears.

- [ ] **Step 4: Scan prohibited scope and generated artifacts**

Confirm no changed dependency/lock/config/migration/Python/UI/Compose/README,
no Task 10 path, no `.pyc`, generated bytecode, cache, report, or review package
is tracked or staged.

- [ ] **Step 5: Freeze the immutable full-range package**

Use the provided script:

```bash
review_head=$(git rev-parse --short HEAD)
review_path=".superpowers/sdd/review-task-9-19c7ae4..${review_head}.diff"
bash /root/.codex/plugins/cache/openai-curated-remote/superpowers/6.2.0/skills/subagent-driven-development/scripts/review-package \
  docs/superpowers/plans/2026-07-25-task-9-final-review-cycle-3.md \
  19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd \
  HEAD \
  "${review_path}"
```

The task-specific variables resolve and embed the actual final HEAD. Record:

```text
exact base/head
commit count and list
path count and diffstat
line count
byte count
SHA-256
full-range git diff --check
```

- [ ] **Step 6: Update durable evidence**

Append all RED/GREEN, commit, gate, audit, package, and lifecycle-supersession
facts. Do not stage ignored evidence.

---

### Task 9: Obtain fresh independent full-range review

**Files:**

- Read-only review:
  `.superpowers/sdd/task-9-brief.md`
- Read-only review:
  `.superpowers/sdd/task-9-resume-brief.md`
- Read-only review:
  `.superpowers/sdd/task-9-review-fix-brief.md`
- Read-only review:
  `.superpowers/sdd/task-9-final-review-cycle-2-brief.md`
- Read-only review:
  `docs/superpowers/specs/2026-07-25-task-9-final-review-fixes-design.md`
- Read-only review:
  `docs/superpowers/specs/2026-07-25-task-9-final-review-cycle-3-design.md`
- Read-only review:
  `docs/superpowers/plans/2026-07-25-task-9-final-review-fixes.md`
- Read-only review:
  `docs/superpowers/plans/2026-07-25-task-9-final-review-cycle-3.md`
- Read-only review:
  `.superpowers/sdd/task-9-report.md`
- Read-only review:
  final immutable package.

**Interfaces:**

- Consumes: exact full Task 9 base-to-final-HEAD package and all adjudications.
- Produces: Spec and Code Quality verdict with Critical/Important/Minor counts.

- [ ] **Step 1: Invoke the requesting-code-review skill**

Dispatch a fresh no-history read-only reviewer as required by that skill. The
reviewer must read every listed input and the complete immutable package before
issuing a verdict.

- [ ] **Step 2: Bind the lifecycle adjudication**

Tell the reviewer that the explicit user-authorized lifecycle supersession is
current authority. It must independently verify the JDK/test evidence, but
must not mechanically enforce stale `shutdownNow()` language from the earlier
committed design/plan.

- [ ] **Step 3: Require exact review output**

Require:

```text
package/range/hash/clean verification
Strengths
Critical findings with file:line
Important findings with file:line
Minor findings with file:line
Spec Compliance: PASS or FAIL
Code Quality: APPROVED, NEEDS CHANGES, or REJECTED
MAY COMPLETE only when Critical=0 and Important=0
```

- [ ] **Step 4: Adjudicate every finding**

Use `superpowers:receiving-code-review` before any response to findings.
Technically verify each call path. Fix genuine Critical/Important findings
through a new approved RED/GREEN cycle; record Minor findings for the final
whole-branch review unless they mask correctness or security.

- [ ] **Step 5: Run verification-before-completion**

Only after reviewer Critical 0 / Important 0, run fresh read-only:

```bash
git rev-parse HEAD
git status --short --branch
git diff --check \
  19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..HEAD
docker ps
```

Confirm no process/container residue, exact package hash, and complete evidence.
Then mark Task 9 complete. Do not start Task 10 in the same cycle.
