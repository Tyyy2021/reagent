# Task 9 final-review cycle 3 design

Date: 2026-07-25

## Status

The controller approved the recommended approach in conversation. This written
specification is pending the controller's final review before implementation
planning.

## Context

Task 9 is implemented through clean commit
`9deaaaeb4cc871c895d890fe5d0a220c83f5cffb`. Fresh focused, protocol, fast,
and full-CI gates are green. A no-history reviewer read the complete immutable
`19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..9deaaaeb4cc871c895d890fe5d0a220c83f5cffb`
package and returned Critical 0, Important 2, Minor 0:

1. raw assistant tool-call identity is not strict at every persistence and
   recovery seam; and
2. `AgentRunner` writes the complete final model answer to an INFO log.

The controller traced both findings through current production code and
accepted them. Task 9 remains open and Task 10 remains blocked.

The same review independently confirmed the prior lifecycle adjudication.
On the binding OpenJDK 21.0.11 runtime, cancellation completes the submitted
`ThreadBoundFuture` and removes its still-running virtual thread from the
executor's tracked set before `ExecutorService.close()`. The approved serial
and concurrent interrupt-ignoring tests pass without an executor-ownership
change. The user therefore withdrew that lifecycle Important and explicitly
authorized retaining the regression tests without adding `shutdownNow()`.
This cycle does not reopen or modify that decision.

## Goals

- Require raw tool-call IDs and names to be actual JSON strings, not values
  coerced with `String.valueOf` or `JsonNode.asText`.
- Validate the complete ordered raw tool-call batch before assistant-message
  persistence, ledger creation, restored execution, or new execution.
- Prove that the raw assistant batch being persisted is the same batch exposed
  as the executable `List<ToolCall>`.
- Preserve the existing valid string identity syntax, exact correlation IDs,
  structured-arguments compatibility, durable assistant message, ledger data,
  model context, approval, recovery, and outcome semantics.
- Remove complete final-answer content from logs while preserving the exact
  durable result, synchronous return value, and established client-facing
  completion event.
- Keep every new validation and log message fixed and bounded; rejected or
  sensitive input must not be echoed.

## Non-goals

- No executor-lifecycle, timeout, cancellation, or thread-ownership change.
- No persistence schema or migration change.
- No change to MCP discovery, Schema validation, retry classification,
  protocol routing, profiles, approvals, UI, Compose, Python, or Task 10.
- No canonical rewriting of the assistant message that would discard the
  provider's valid raw representation.
- No removal or truncation of the final answer in durable task state,
  synchronous results, or the established completion event.
- No new dependency and no unrelated refactoring.

## Considered approaches

### A. One strict protocol parser with fail-closed consumers

Create one parsing operation at the `ToolCall` boundary for a raw assistant
`tool_calls` value. It requires the list, call, function, ID, and name shapes
used by the protocol; IDs and names must be actual strings and still pass the
existing bounded ASCII rules. It returns an ordered immutable
`List<ToolCall>`.

`Decision.tools`, `StateStore.appendAssistant`, and
`Context.pendingToolCalls` all use that operation. Each keeps its existing
responsibility, but none reinterprets identity independently.

This is the selected approach. It creates one auditable trust boundary and
prevents streaming, non-streaming, persistence, and recovery behavior from
drifting apart.

### B. Duplicate strict type checks in each consumer

This would avoid a shared parser but repeat map/list/type handling in at least
three places. The current defect exists partly because those paths already
interpret the same payload differently. Local checks would be a smaller
individual edit but a weaker long-term boundary.

### C. Rebuild a canonical assistant message from `List<ToolCall>`

This would make persistence and execution agree by construction, but it would
replace the valid raw assistant message rather than validate it. That conflicts
with exact conversation replay and could alter provider-specific structured
argument representation. It is rejected.

## Strict raw-call boundary

`ToolCall` remains the value object that owns ID and name syntax. It gains a
single raw assistant-call parsing operation with these rules:

1. `tool_calls` must be a list when a tools decision or persisted tool batch is
   present.
2. Every list item must be a map with an `id` field and a `function` map.
3. `id` and `function.name` must each be actual `String` instances.
   Missing, null, numeric, boolean, collection, or object values are rejected;
   none are coerced.
4. The existing `ToolCall` constructor applies exact
   `^[A-Za-z0-9_-]{1,255}$` and `^[A-Za-z0-9_-]{1,64}$` validation.
5. String arguments remain byte-for-byte unchanged.
6. Structured JSON arguments remain supported and are serialized to the same
   compact JSON form used by the current non-streaming client. Missing or null
   arguments retain the existing empty-object fallback, `"{}"`.
7. The parser preserves call order and returns an immutable list.
8. Shape, identity, serialization, and mismatch errors use fixed generic
   messages that never include the rejected value or raw assistant payload.

The parser is not a Schema or 65,536-byte gateway replacement. Existing
adapter/gateway argument validation remains authoritative at the remote-call
boundary.

## Decision consistency

`Decision.tools(assistantMessage, toolCalls)` parses the raw assistant batch
before returning a persistable decision. It requires exact ordered equality
between the parsed calls and the supplied executable list, including ID, name,
and normalized arguments.

This preserves the existing public factory while preventing a custom or
incorrect `LlmClient` from constructing a decision whose durable replay would
execute different calls from the live path. The executable list is copied to
an immutable list after validation.

The streaming assembler already retains only textual ID/name fragments and
builds the raw assistant map from the validated accumulator. The
non-streaming client may still accept structured JSON arguments, but numeric
or otherwise non-string ID/name nodes are rejected when the raw map and
executable list are reconciled.

## Persistence boundary

`StateStore.appendAssistant` validates and parses the whole raw call batch
before `appendMessage`, `saveAll`, or any ledger mutation. It creates pending
ledger rows only from the returned validated calls; it no longer extracts IDs
or names with `String.valueOf`.

The raw valid `tool_calls` JSON is still persisted for exact conversation
replay. Validation does not replace it with a reconstructed message.

The StateStore check is intentional defense in depth. Normal execution first
passes through `Decision.tools`, but tests, recovery utilities, and other
callers can invoke StateStore directly. A malformed direct call must also fail
with zero new assistant rows and zero new ledger rows.

## Recovery boundary

`Context.pendingToolCalls` parses the complete last assistant tool batch
through the same strict operation before filtering already-answered IDs. It
does not skip malformed entries or coerce restored identity.

Persisted tool-result correlation IDs must also be actual strings. A malformed
restored result ID fails closed with a bounded generic exception instead of
silently changing which call appears pending.

This makes corrupted or historical malformed context fail before
`ToolBatchCoordinator`, `markInProgress`, or tool execution.

## Completion logging

`AgentRunner` no longer interpolates `decision.getAnswer()` into its completion
log. It emits fixed metadata only, such as the bounded internal task ID and
completion classification.

The following existing behavior remains exact:

- `StateStore.completeTask` receives the full final answer;
- the synchronous `RunResult` contains the full final answer; and
- the established client-facing `COMPLETED` event continues to deliver the
  full answer.

For this approved repair, that authenticated client-delivery event is part of
the task result contract rather than a telemetry sink. Logs and spans must not
duplicate its content.

No new preview, hash, length, substring, exception, or answer-derived field is
added to logs or spans.

## Error handling

- Raw shape or type errors throw a generic `IllegalArgumentException`.
- Existing invalid ID/name errors remain generic and unchanged.
- A mismatch between raw and executable batches throws a generic
  `IllegalArgumentException`.
- Restored malformed state fails before execution; it is handled by the
  existing task-failure/fencing path without leaking the rejected value in
  events, logs, or spans.
- The repair does not catch fatal crash or fencing signals and does not change
  durable failure or recovery classification.

## Strict TDD sequence

No production code changes before each focused behavior is observed RED for
the expected reason.

### Cycle 1: strict raw identity and batch consistency

Test-only changes first prove all of the following on unchanged production:

- missing, null, numeric, and collection-valued raw IDs are rejected;
- missing, null, numeric, and collection-valued raw names are rejected;
- an executable list whose ID, name, arguments, order, or count differs from
  the raw assistant batch is rejected before a `Decision` can be returned;
- StateStore rejects malformed identity before creating an assistant message
  or ledger row;
- restored malformed calls and result correlation IDs fail before execution;
- valid maximum-length identity, valid unknown-tool identity, fragmented
  streaming calls, string arguments, and structured JSON arguments remain
  accepted.

After a genuine assertion RED, implement only the shared parser and the three
consumer changes. Rerun the exact focused command to GREEN before Cycle 2.

### Cycle 2: final-answer log isolation

Add a final answer containing unmistakable URL/body/credential/log-line
sentinels. On unchanged production, captured `AgentRunner` logs must expose the
sentinel and produce the expected RED.

Replace only the raw completion log with fixed metadata. The same test must
then prove:

- captured logs and spans exclude every sentinel;
- `completeTask`, the synchronous result, and the established completion event
  retain the exact answer; and
- no task, event, or recovery classification changes.

## Verification and completion

After both focused cycles are green:

1. Run the complete affected Java focused set, including persistence/recovery
   integration where needed.
2. Run the complete Task 9 Java focus set and Python MCP protocol tests.
3. Run Ruff and Pyright.
4. Reuse the current-source capability image only after proving no Python
   source changed; otherwise rebuild it.
5. Run the real Java SDK `McpProtocolIT`.
6. Run the fast Maven suite.
7. Run stable-cache full CI including real `RagGatewayIT` and
   `McpProtocolIT`.
8. Audit dependency purity, exact changed scope, raw-content flows, cache,
   generated files, processes, and containers.
9. Commit the implementation separately from this design and its subsequent
   plan.
10. Freeze a new immutable full-range package and require a fresh no-history,
    read-only review with Critical 0 and Important 0 before completing Task 9.

Task 10 remains blocked throughout this cycle and is not started by Task 9
completion.
