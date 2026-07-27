# Task 9 Final-Review Cycle 4 Design

## Status and authority

This design closes the three Important findings from the fresh no-history
review of the exact Task 9 range:

```text
base: 19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd
head: 7e420fa99514591ab1889b2c21b12a26c47cea29
package SHA-256:
ba9f5ca97c6174899621736b3c16c4f1c59c5c7a0ade5d4a7789790666ba6349
```

The review returned Critical 0, Important 3, Minor 0 and MUST NOT COMPLETE.
The three findings were independently traced through the current code and
accepted as genuine:

1. an MCP session can be abandoned while its old discovery remains
   authoritative;
2. a `Decision` can retain mutable raw JSON or represent a final answer with a
   non-empty tool batch;
3. a task can persist a later assistant batch that reuses an earlier
   `tool_call_id`.

The user approved the minimal-invariant repair approach. This cycle is the
single consolidated fix wave required by the final-review process.

The earlier lifecycle finding remains explicitly withdrawn. Nothing in this
cycle changes executor lifecycle, timeout, cancellation, ownership, or the
retained serial/concurrent regressions. Task 10 remains blocked and must not
start.

## Goals

- Treat an MCP session and its validated discovery as one validity unit.
- Ensure failed discovery or reconnect cannot authorize later calls through a
  null, rejected, or stale session.
- Deeply detach and freeze every `Decision` assistant-message snapshot.
- Reject a final decision that carries a non-empty tool-call batch before any
  persistence.
- Reject every cross-batch reuse of a `tool_call_id` before assistant-message
  or ledger mutation.
- Verify ledger identity again before changing an existing call's state.
- Preserve all valid protocol, persistence, recovery, idempotency, result,
  logging, and client-delivery behavior.
- Obtain assertion-based RED for each production correction before changing
  the relevant production path.

## Non-goals

- No new MCP retry, retry count, transport, endpoint, or SDK behavior.
- No change to trusted local tool policy or model-visible Schema.
- No persistence schema, migration, repository-key, or approval change.
- No canonical rewriting that discards valid provider assistant JSON.
- No equality requirement between final `answer` and assistant `content`.
- No rejection of an absent, explicit-null, or empty `tool_calls` value on an
  otherwise valid final assistant message.
- No new dependency, configuration, Python, UI, Compose, README, profile, or
  Task 10 change.
- No broad gateway state-machine rewrite and no unrelated refactoring.
- No executor-lifecycle, timeout, cancellation, or ownership change.

## Selected architecture

The repair strengthens three existing ownership boundaries without replacing
their public interfaces:

```text
OfficialMcpGateway.ServerState
  session + validated discovery
  -> valid together or invalid together

Decision factory
  caller-owned raw JSON
  -> deeply detached immutable snapshot
  -> tools/final invariant

StateStore.appendAssistant
  parsed current batch
  -> no ID may already exist
  -> append assistant + create ledger rows atomically
```

One implementation worker receives all three findings so the shared
assistant/ledger/recovery invariants are changed coherently. The worker may
use separate focused commits, but this remains one fix wave and one final
scoped re-review.

## MCP session and discovery validity

### Current failure

`OfficialMcpGateway.call` currently treats a non-empty discovery as sufficient
to call `state.session`. `closeSession` clears only the session. After an
exhausted second transport call, the state can therefore contain:

```text
session = null
discovery = previously validated non-empty list
```

The next call skips discovery, dereferences the null session, and normalizes
the resulting failure as a definitive contract error rather than opening and
validating a fresh session.

A replacement whose `listTools` response fails validation can also remain
installed while the old discovery remains cached. A failed public discovery
refresh has the same stale-authorization risk.

### Invariant

For each server, callable state is valid only when both are present:

```text
live initialized session
validated immutable discovery produced by that session
```

Abandoning either invalidates both. Readiness is observational and never
authorizes a stale snapshot.

### State operations

Introduce one private invalidation operation that:

1. detaches the current session from state;
2. clears discovery to `List.of()`;
3. closes the detached session exactly once if present.

All failure branches that abandon or reject a session use this operation.
Gateway shutdown may use the same operation before clearing the state map.

`call` must require both a non-null session and non-empty validated discovery.
If either is missing, it performs current discovery before resolving the tool
Schema or calling the tool.

### Public discovery

Every public `discover(serverId)` remains a current `listTools` refresh.

- Success atomically installs the discovery produced by the current session
  and records readiness.
- A definitive discovery/Schema failure invalidates the session and discovery,
  records unavailable readiness, and fails without retry.
- A transport failure invalidates state, opens exactly one replacement,
  initializes and validates it, then atomically installs its discovery.
- Any replacement failure invalidates and closes the replacement before it is
  exposed to a later call.

### Call reconnect

The existing at-most-once reconnect policy remains:

1. A definitive first call failure is returned without reconnect.
2. A transport first failure invalidates the old state.
3. A replacement is opened, initialized, and rediscovered.
4. Replacement discovery failure invalidates and closes the replacement.
5. Schema drift invalidates the replacement and fails definitively.
6. Successful matching discovery is installed and readiness becomes ready.
7. The call is retried once.
8. A second transport failure invalidates state and reports transport
   unavailable.
9. A definitive result/protocol failure from the successfully validated
   replacement is not retried. The validated state may remain available.

No failure path may leave a rejected replacement paired with an older
discovery.

### Gateway regression coverage

Focused contract tests must prove:

- after first- and second-attempt transport failures, the failed sessions are
  closed, state is unavailable, and a later logical call opens, discovers,
  and succeeds through a fresh third session;
- a replacement whose discovery is contract-invalid is closed and leaves no
  callable cached state; a later call must use a fresh valid session;
- a failed public discovery refresh invalidates the formerly valid snapshot
  and session; a later call must rediscover;
- readiness is unavailable after each failed transition and ready only after a
  later validated discovery;
- existing one-reconnect success, Schema drift, definitive no-retry, trace,
  bound, and shutdown tests remain green.

## Immutable Decision snapshot

### Current failure

`Decision.tools` validates the raw assistant map against an immutable
executable list, but stores the caller's map reference. A custom or incorrect
`LlmClient` can mutate that map after validation. Persistence then sees the
mutated batch while live execution still sees the earlier list.

`Decision.finalAnswer` accepts any map. A non-empty raw `tool_calls` list can
therefore create PENDING ledger rows immediately before the task is marked
complete without executing those calls.

### Snapshot boundary

Both public factories create a deeply detached, immutable JSON snapshot before
constructing a `Decision`.

The copier:

- preserves insertion order for maps and order for lists;
- accepts null, `String`, `Boolean`, finite JSON numbers, maps with actual
  string keys, and lists;
- recursively copies every map and list;
- exposes unmodifiable maps and lists at every level;
- rejects unsupported values, invalid keys, non-finite numbers, and copy
  failures with one fixed bounded message;
- never retains the rejected value or a provider exception whose message could
  expose input.

The snapshot preserves valid provider representation. It does not rebuild a
canonical assistant message from `List<ToolCall>`.

`getAssistantMessage()` returns this immutable snapshot. Callers cannot mutate
the top-level map, nested call maps, function maps, argument maps, or lists.

### Tools decision

`Decision.tools`:

1. snapshots the raw assistant message;
2. defensively copies the supplied executable list;
3. parses `tool_calls` from the snapshot;
4. requires exact ordered equality of ID, name, and normalized arguments;
5. stores only the immutable snapshot and immutable executable list.

Mutating any caller-owned source collection after the factory returns cannot
change persistence, context, or recovery.

### Final decision

`Decision.finalAnswer` snapshots the raw assistant message and examines
`tool_calls` before returning.

- Missing, explicit-null, or an empty valid list is allowed.
- A malformed non-null value is rejected.
- A valid non-empty batch is rejected.
- Rejection occurs at the factory, before `AgentRunner` can call
  `StateStore.appendAssistant`.

Errors are fixed and bounded. The final answer's durable value, synchronous
return, authenticated `COMPLETED` payload, and log isolation remain unchanged.

### Decision regression coverage

Focused tests must prove:

- mutating the original top-level assistant map after `Decision.tools` returns
  does not alter the decision snapshot;
- mutating original nested tool/function/arguments maps or call lists does not
  alter the snapshot or executable calls;
- every exposed snapshot level is unmodifiable;
- persistence-facing raw calls and executable calls remain equal after source
  mutation;
- a final decision rejects a valid non-empty tool batch and malformed
  non-null `tool_calls`;
- final decisions still accept missing, explicit-null, and empty tool-call
  values;
- normal final-answer durability, synchronous return, `COMPLETED` publication,
  and log-sentinel regressions stay green.

## Cross-batch tool-call identity

### Current failure

`StateStore.appendAssistant` currently omits a same-task existing call from
`callsToCreate` but still appends the new assistant message.

If the old row is terminal, the coordinator skips it without producing a tool
result for the new assistant message, leaving the latest batch pending. If the
new call changes its name or arguments, live execution can also diverge from
the immutable ledger identity.

Recovery replay does not require another assistant append. It restores the
original assistant batch and reuses the existing ledger row, so rejecting a
new batch with an old ID does not break stable idempotency keys.

### Append invariant

After strict raw-batch parsing and before JSON serialization, message append,
or ledger mutation, `StateStore.appendAssistant` checks every parsed ID.

Any existing ledger row—same task or another task—causes the complete new
assistant batch to fail closed. The error is fixed and bounded and does not
include task IDs, call IDs, names, arguments, or other raw input.

Only a batch whose IDs are all absent may append its assistant message and
create PENDING rows.

### Ledger mutation invariant

`markInProgress`, `recordToolResult`, and `markInDoubt` retain their recovery
semantics:

- if no row exists and the existing compatibility path permits creation, the
  supplied immutable call creates it;
- if a row exists for another task, the operation fails before mutation;
- if a row exists for this task, its stored name and arguments must exactly
  equal the supplied call before status, result, or message mutation.

An identity mismatch uses a fixed bounded error and leaves the row and message
history unchanged.

### Persistence regression coverage

Real MySQL integration tests must prove:

- an identical same-task ID reused in a later assistant batch is rejected with
  no new assistant message and no ledger mutation;
- the same ID reused with a different name is rejected with no mutation;
- the same ID reused with different arguments is rejected with no mutation;
- cross-task reuse remains rejected before any current-task message mutation;
- direct ledger-state operations reject stored name or argument mismatch
  before changing status or appending a result;
- the original recovery path reuses the same ID, name, and arguments and still
  completes normally;
- malformed-batch, structured-argument, reconstruction, in-doubt, and
  definitive-result regressions remain green.

## Errors and observability

New validation messages are stable constants. They do not interpolate:

```text
server IDs
tool_call_id
tool names
arguments
assistant content
Schema or discovery payloads
result text
URLs or credentials
```

No new raw content is added to logs, spans, client failure events, or exception
causes. Existing safe readiness reasons remain bounded to contract or transport
unavailability.

## TDD and commit structure

One implementation worker handles the complete finding list. Within that one
wave, it uses three focused RED/GREEN sequences:

1. gateway state invalidation and later-call recovery;
2. immutable Decision snapshots and final-decision tool guard;
3. cross-batch ID rejection and ledger identity verification.

Each RED must be an assertion failure on unchanged production. Compilation,
fixture, permission, Docker, or network failure is not behavioral RED.

Separate focused commits are preferred so each invariant has an auditable
range. Each commit stages only its production and covering test paths. No
amend, reset, rebase, clean, push, merge, or Task 10 action is allowed.

## Verification

After the three focused GREENs:

- run the combined affected Java gate;
- run the complete Task 9 Java focus;
- run StateStore integration against real MySQL and Redis;
- run Python MCP protocol, Ruff, and Pyright unchanged;
- inspect and reuse the exact verified Python image only if its source remains
  unchanged;
- run real Java MCP protocol;
- run the fresh fast gate;
- run stable-cache full CI;
- verify exact changed paths, dependency purity, clean index/worktree, no
  lifecycle change, and no prohibited/generated artifacts;
- verify no Maven, Java, Python, test, build process, or running container
  remains.

All expected counts are frozen in the implementation plan after the exact new
test methods and parameterized invocation counts are defined.

## Final review

Regenerate the ignored full-range package from exact base
`19c7ae4c8907f8ac16d0e7b1e23f45c6655f01cd` to the literal full final HEAD.
The package must embed both SHAs and record its fresh line count, byte count,
commit/path counts, diffstat, and SHA-256.

The single scoped re-review receives:

- all three original Important findings;
- the complete consolidated fix report;
- the exact fix-range package;
- focused RED/GREEN and full-gate evidence;
- the current lifecycle adjudication.

There is no second fix wave in this cycle. Residual Critical or Important
findings keep Task 9 blocked and return to the user. Task 9 may complete only
when the scoped review reports every original finding addressed, no new
Critical/Important breakage, the worktree is clean, and verification-before-
completion passes. Task 10 remains blocked even after Task 9 completion.
