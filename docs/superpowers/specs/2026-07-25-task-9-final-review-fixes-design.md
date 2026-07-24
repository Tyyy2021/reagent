# Task 9 final-review fixes design

Date: 2026-07-25

## Status

Approved for implementation.

## Context

Task 9 adds the official Java MCP 2.0.0 gateway, trusted discovery and frozen
tool contracts, exact Streamable HTTP routing, bounded payloads, and recovery
semantics for an idempotent remote ticket call.

The full-range independent review of
`19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd..00d9705f48f176744caa7d303d3e7d3cef413016`
closed four of the six prior Important findings but left two Important gaps:

1. Model-controlled tool-call identity and raw failure messages can still enter
   events, logs, or spans.
2. The per-call deadline cancels a future, but try-with-resources then waits for
   the executor to terminate, so an interrupt-ignoring tool can defeat the
   observable deadline.

Task 10 remains blocked until both gaps are fixed, all required gates pass, and
an independent full-range re-review reports zero Critical and zero Important
findings.

## Goals

- Reject unsafe or unbounded tool-call identity before persistence or
  execution.
- Keep observability surfaces bounded and free of arbitrary model or upstream
  exception text.
- Preserve valid unknown-tool self-correction and exact tool-call correlation.
- Make the frozen per-call deadline an actual return boundary even when a tool
  ignores interruption.
- Preserve Task 9 outcome, recovery, fencing, persistence, and model-context
  semantics.

## Non-goals

- Redesigning the event contract, persistence schema, LLM protocol, approval
  flow, or tool APIs.
- Removing full tool observations from the durable ledger or model context.
- Hard-killing in-process Java code. Process/container sandboxes remain the
  hard-kill boundary.
- Introducing a shared executor service or new dependency.
- Starting Task 10 or changing UI, Compose, profiles, migrations, or defaults.

## Considered approaches

### A. Validate identity centrally and use trusted observability projections

Validate every `ToolCall` at construction:

- `id` must match `^[A-Za-z0-9_-]{1,255}$`.
- `name` must match `^[A-Za-z0-9_-]{1,64}$`.
- `arguments` retain their existing validation and size boundary at the
  adapter/gateway layer.

Known-tool telemetry uses the frozen `ToolSnapshot` identity. A valid but
unknown tool name remains available to the durable/model-visible error so the
model can self-correct, while observability uses the fixed classification
`unknown`. Valid call IDs remain unchanged, preserving event and persistence
correlation. Runtime failures publish a fixed bounded `FAILED` classification
instead of `RuntimeException.getMessage()`.

This is the selected approach. It protects persistence and execution as well
as observability, uses existing trusted configuration rules, and preserves
normal protocol behavior.

### B. Sanitize only events, logs, and spans

This would be a smaller diff, but unsafe or oversized identity would still
reach persistence and execution. It would not satisfy the review requirement
to enforce the boundary before those stages.

### C. Hash all call IDs and hide every tool name in observability

This would bound output, but it would unnecessarily break exact event-to-ledger
correlation for valid calls and reduce operational usefulness. Hashing is not
needed once the accepted identity syntax and length are enforced.

## Identity and observability design

`ToolCall` is the single boundary shared by streaming decisions, non-streaming
decisions, and restored pending calls. Its compact constructor will enforce the
two identity patterns above and reject null, blank, illegal-character, and
oversized values with a bounded generic `IllegalArgumentException`. The
exception must not echo the rejected value.

The allowed syntax deliberately accepts normal OpenAI-compatible opaque IDs
and configured tool names while excluding whitespace, control characters,
URLs, credentials, response fragments, and arbitrary Unicode text. An unknown
name that satisfies the syntax remains a normal definitive unknown-tool
observation; it does not fail the task merely because it is absent from the
frozen catalog.

`ToolExecutor` resolves the frozen snapshot before creating its span:

- known calls use the frozen snapshot name for span name, attributes, and logs;
- unknown calls use the fixed name `unknown`;
- the validated call ID may remain unchanged as the bounded call attribute;
- no arguments, result text, URL, exception message, or cause chain is added.

`DefaultToolBatchCoordinator` uses the same projection for `TOOL_CALL` and
`TOOL_RESULT` metadata. Valid call IDs remain exact. Known tool names come from
the frozen catalog; unknown names become `unknown`. Existing outcome,
`inDoubt`, `reconciled`, and `recoveryRequired` fields remain unchanged.

`AgentRunner` retains the established `FAILED` event type and `error` field but
sets the value to the fixed classification `task_execution_failed`. The
durable failure result and synchronous return remain unchanged so this focused
security repair does not redesign task-result semantics. Logs and spans remain
generic and do not record the raw exception.

## Deadline and executor lifecycle design

Each `executeConcurrently` invocation continues to own a virtual-thread
per-task executor. The serial and concurrent branches must no longer use
try-with-resources because JDK 21 `ExecutorService.close()` waits until all
submitted work terminates.

Instead, each branch will:

1. create the executor explicitly;
2. submit and await calls using the existing frozen, submission-time absolute
   deadlines;
3. preserve existing timeout and outcome classification;
4. cancel the affected future or all siblings on timeout/fatal propagation as
   currently required;
5. call `shutdownNow()` in `finally` without awaiting termination.

This makes the batch method return after classification even if a task consumes
the interrupt and keeps running. Such a task may continue briefly in a daemon
virtual thread; this is unavoidable for in-process Java code and does not
change the established rule that subprocess/container execution owns hard
termination. An idempotent MCP timeout remains
`REMOTE_OUTCOME_UNKNOWN`; read-only MCP and local timeouts remain definitive.
Crash and fence signals still propagate and request sibling cancellation.

## Test design and TDD order

No production code may change before the corresponding focused test is added
and observed failing for the expected behavior.

### Cycle 1: safe metadata and failure events

Focused tests will prove:

- accepted boundary IDs of 255 characters and names of 64 characters remain
  exact;
- null, blank, illegal-character, 256-character IDs, and 65-character names
  are rejected with a bounded message that excludes the input sentinel;
- a valid unknown tool still produces a definitive model-visible error, while
  its event/span/log identity is `unknown`;
- known-tool events/spans use the frozen trusted name;
- a runtime exception whose message contains an unmistakable secret/URL/body
  sentinel does not place that sentinel in a `FAILED` event, log, span, or
  thrown validation message;
- durable/model-visible tool results remain byte-for-byte unchanged.

The initial focused run must fail assertions against unchanged production, not
fail compilation or fixture setup. After the minimal production change, the
same command must pass before starting Cycle 2.

### Cycle 2: interrupt-ignoring deadline

Focused tests will run the batch on a separately controlled thread and use a
tool that:

- signals that execution started;
- catches and records interruption;
- deliberately remains blocked until the test releases a latch.

Both the single/serial branch and the multi-call/concurrent branch must return
within a bounded test deadline before that release. The test then releases the
worker in `finally` and joins it for hygiene. It also verifies the existing
outcome classification and cancellation signal. Unchanged production must fail
because executor close waits; the minimal lifecycle change must make the exact
same tests pass.

Existing fatal crash/fence coverage must use a bounded wait for observed
sibling cancellation rather than relying on executor close to synchronize the
assertion.

## Verification and review

After both focused cycles are green:

1. Run the affected Java focused tests.
2. Run the complete Task 9 Java focus set and Python MCP protocol tests.
3. Run Ruff and Pyright.
4. Build a fresh capability image.
5. Run the real `McpProtocolIT`.
6. Run the fast Maven suite.
7. Run full CI with the existing verified Task 9 Hugging Face cache, including
   real `RagGatewayIT` and `McpProtocolIT`.
8. Confirm the official MCP dependency tree, clean diff checks, exact scope,
   and no leftover processes or containers.
9. Commit the implementation separately from this design document.
10. Freeze a new exact full-range review package and require a fresh
    independent verdict with zero Critical and zero Important findings before
    closing Task 9.

Minor findings, if any, remain recorded for the final whole-branch review under
the user's task-level review policy.
