# ReAgent Demo, Acceptance, and CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the proven runtime/RAG/MCP workflow into a readable static console and a one-command local stack, with downloadable CI evidence and a fully automated Worker-crash demonstration.

**Architecture:** First expose readiness and deterministic acceptance reporting around the already-tested workflow. Then add a no-build static Spring Boot console that renders REST/SSE state safely. Finally containerize both executables, compose MySQL/Redis/Jaeger/Fake MCP/ReAgent, add normal/smoke/failover scripts, split CI into unit/integration/compose jobs, and update README only after the commands pass.

**Tech Stack:** Java 21, Spring Boot Actuator/MVC/SSE, OpenTelemetry/Jaeger, static HTML/CSS/vanilla JavaScript, Docker multi-stage builds, Docker Compose, Bash/curl, GitHub Actions, Maven Surefire/Failsafe.

## Global Constraints

- Start only after the Runtime, RAG, and MCP/Approval completion gates pass.
- Do not redesign the backend contracts or add React/Vue/Node/npm.
- The console is an observability/control surface over authoritative REST state. SSE is replayable presentation, not the approval/security state source.
- No untrusted LLM, MCP, RAG, goal, error, or reason text may be assigned to `innerHTML`.
- Normal startup requires only Docker/Compose and one previously configured LLM key. Smoke/failover modes use deterministic scripted LLM and require no key.
- A startup script reports success only after MySQL, Redis 8, Fake MCP discovery, RAG index, and ReAgent readiness all pass.
- `demo-down.sh` retains volumes. Only explicit `demo-reset.sh` removes this compose project's volumes.
- `demo-failover.sh` kills only the resolved Worker A container in this compose project after the Fake MCP confirms its ticket transaction committed.
- Scripts must be rerunnable, use bounded waits, print actionable failures, and avoid dependencies on `jq`.
- Final docs must state the first model dependency build can be large and that downstream idempotency is required for unique external effects.
- Commit after each task with the exact message shown.

---

## Task 1: Add readiness, deterministic demo LLM, traces, and acceptance reports

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/java/com/reagent/llm/OpenAiCompatibleClient.java`
- Create: `src/main/java/com/reagent/demo/ScriptedIncidentLlmClient.java`
- Create: `src/main/java/com/reagent/demo/DemoLlmProperties.java`
- Create: `src/main/java/com/reagent/demo/DemoModeGuard.java`
- Create: `src/main/java/com/reagent/health/ReAgentReadinessHealthIndicator.java`
- Create: `src/main/java/com/reagent/health/ReAgentReadinessView.java`
- Create: `src/main/java/com/reagent/api/ReadinessController.java`
- Create: `src/main/java/com/reagent/acceptance/AcceptanceEvidence.java`
- Create: `src/test/java/com/reagent/acceptance/AcceptanceReportWriter.java`
- Create: `src/test/java/com/reagent/acceptance/IncidentAcceptanceIT.java`
- Create: `src/test/java/com/reagent/demo/ScriptedIncidentLlmClientTest.java`
- Create: `src/test/java/com/reagent/health/ReAgentReadinessHealthIndicatorTest.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-demo-smoke.yml`
- Create: `src/main/resources/application-demo-chaos.yml`

**Consumes:** completed incident workflow, knowledge/MCP readiness, scripted decision contract, event and trace system.

**Produces:** Actuator readiness gate, safe readiness API, opt-in deterministic demo LLM, acceptance evidence file, completed recovery observability vocabulary.

### Step 1.1: Add Actuator without exposing secrets

- [ ] Add `spring-boot-starter-actuator`.
- [ ] Configure only health/info endpoints externally; enable readiness/liveness groups and never expose env/configprops/heapdump.
- [ ] In `ReAgentReadinessHealthIndicatorTest`, assert DOWN with individual reasons when database, Redis, RAG active index, required MCP discovery, or profile catalog is unavailable; assert UP only when all are ready.
- [ ] Assert health details include versions/counts/server IDs but not URLs, API keys, full tool schemas, document text, or arguments.
- [ ] Run:

```bash
./mvnw -B -Dtest=ReAgentReadinessHealthIndicatorTest test
```

Expected red: readiness component missing.

- [ ] Implement aggregate health from narrow readiness interfaces; expected green.

### Step 1.2: Add an explicitly gated scripted demo LLM

- [ ] Make `OpenAiCompatibleClient` conditional on `reagent.llm.mode=openai` with match-if-missing.
- [ ] Implement `ScriptedIncidentLlmClient` only for `reagent.llm.mode=scripted` and require active profile `demo-smoke` or `demo-chaos`; `DemoModeGuard` refuses scripted mode in any other profile.
- [ ] Use the same four-turn sequence as `IncidentScenarioFixture`, but derive tool-call IDs from task/turn deterministically and validate actual messages/tools before returning each decision.
- [ ] The scripted final answer must include the actual first returned chunk ID and actual ticket ID parsed from prior tool messages, not hard-coded fake values.
- [ ] In tests, assert extra turn, missing evidence, wrong catalog, or production-profile scripted mode fails loudly.
- [ ] Run:

```bash
./mvnw -B -Dtest=ScriptedIncidentLlmClientTest test
```

Expected: green after minimal implementation.

### Step 1.3: Finish event/span vocabulary

- [ ] Add/verify `RECOVERY_ATTEMPT` event and spans `agent.recovery`, `approval.wait`, `approval.decision`, `rag.embed`, `rag.search`, `mcp.initialize`, `mcp.list_tools`, `mcp.call_tool`.
- [ ] Add trace keys for profile, lease epoch, MCP server, hit count/chunk IDs, approval status, recovery attempt, idempotency replay/deduplicated.
- [ ] Extend Trace tests to reject sensitive/full-content attributes and verify Worker A/Worker B recovery spans share the logical task trace ID.
- [ ] Publish recovery attempt with token before a recovered step. REST status remains authoritative if event publication gaps.

### Step 1.4: Write the acceptance report test first

- [ ] `IncidentAcceptanceIT` reuses the real full-stack test fixture and runs approve, reject, dangerous crash, schema drift, and no-hit cases as named tests.
- [ ] `AcceptanceReportWriter` writes `target/acceptance/incident-evidence.json` and `.md` after successful assertions with:
  - test case and pass status;
  - task/profile IDs;
  - citation IDs/sources;
  - MCP tools called;
  - approval decision;
  - Worker epochs/recovery point;
  - ticket ID/attempt count/unique count;
  - elapsed duration;
  - no model key or full sensitive content.
- [ ] Delete/overwrite only those two target files at test start; never append stale prior-run results.
- [ ] Run:

```bash
./mvnw -B -Dit.test=IncidentAcceptanceIT verify
```

Expected red until report writer/readiness/demo wiring is complete.

### Step 1.5: Expose a bounded readiness view

- [ ] `GET /api/readiness` returns the same safe component status used by health checks, suitable for the startup script/UI.
- [ ] Return HTTP 200 with `ready=false` while initializing so scripts can display component reasons; Actuator readiness endpoint still returns non-2xx when DOWN for container healthcheck.
- [ ] Add controller test for safe fields and no secret/URL/full schema.

### Step 1.6: Verify and commit

- [ ] Run:

```bash
./mvnw -B test
./mvnw -B -Pci verify
test -s target/acceptance/incident-evidence.md
! rg -n "api-key|DEEPSEEK|arguments_snapshot|document text" target/acceptance
git diff --check
```

Expected: all tests green, report files non-empty, sensitive search empty.

- [ ] Commit:

```bash
git add pom.xml src/main/java src/main/resources src/test/java
git commit -m "feat: add readiness and acceptance evidence"
```

---

## Task 2: Build the thin static incident console

**Files:**

- Create: `src/main/java/com/reagent/api/KnowledgeController.java`
- Modify: `src/main/java/com/reagent/api/TaskController.java`
- Create: `src/main/java/com/reagent/api/TaskView.java`
- Modify: `src/main/java/com/reagent/api/ApprovalController.java`
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/styles.css`
- Create: `src/main/resources/static/app.js`
- Create: `src/main/resources/static/favicon.svg`
- Create: `src/test/java/com/reagent/api/DemoApiContractTest.java`
- Create: `src/test/java/com/reagent/ui/DemoConsoleContractTest.java`
- Create: `src/test/java/com/reagent/ui/DemoConsoleFlowIT.java`

**Consumes:** task/status/SSE, approval APIs, knowledge source service, readiness view.

**Produces:** no-build console, safe citation endpoint, replay cursor support for browser restarts, API/UI contract evidence.

### Step 2.1: Specify the page/API contract first

- [ ] `DemoApiContractTest` covers:
  - task creation with optional profile and default coding compatibility;
  - task view contains task/profile/status/result but no profile snapshot/system prompt;
  - approval list/decision shapes;
  - safe chunk summary lookup and 404 for unknown/path-like IDs;
  - SSE accepts existing `Last-Event-ID` and browser-friendly `?cursor=` fallback, header taking precedence;
  - readiness view.
- [ ] `DemoConsoleContractTest` loads the three static resources and asserts required landmark IDs/classes, all event names, approve/reject handlers, `EventSource`, `textContent`, and zero production use of `.innerHTML`, `insertAdjacentHTML`, `document.write`, or `eval`.
- [ ] Run:

```bash
./mvnw -B -Dtest=DemoApiContractTest,DemoConsoleContractTest test
```

Expected red: resources/API additions missing.

### Step 2.2: Add bounded REST views and browser replay cursor

- [ ] Replace ad-hoc task status map internally with immutable `TaskView`; preserve existing JSON field names and add profile ID, recovery count, timestamps, and owner/epoch only where safe for demo.
- [ ] Add `GET /api/knowledge/sources/{chunkId}` backed by `KnowledgeSourceService`; return title/section/source/bounded excerpt/checksum/index version, no arbitrary classpath/file reads.
- [ ] Extend stream endpoint with optional `cursor` query parameter. Normalize header/query to one opaque cursor; header wins.
- [ ] Approval response never includes full unbounded arguments/citations; return clamped snapshots appropriate for the demo card.
- [ ] Rerun API contract; expected green.

### Step 2.3: Implement semantic static HTML without external assets

- [ ] `index.html` contains:
  - header with ReAgent Runtime/RAG/MCP status chips;
  - preset checkout incident goal textarea and `incident-ops` selected;
  - start/reset controls;
  - ordered timeline region with live connection state;
  - citation cards region;
  - MCP call/result cards region;
  - visually distinct write approval panel with approve/reject buttons/reason;
  - recovery/worker/epoch panel;
  - final diagnosis/ticket region;
  - accessible labels, focus order, status live region, and no CDN/script dependency.
- [ ] Use one intentional visual direction: compact operations console, high-contrast neutral surface, amber write-risk, green verified evidence, monospace IDs, responsive single-column below 900px. Do not add a generic dashboard grid unrelated to the flow.
- [ ] `favicon.svg` is a simple repo-native vector mark; no generated bitmap is required.

### Step 2.4: Implement safe event/state rendering

- [ ] In `app.js`, centralize element creation:

```javascript
function textElement(tag, className, value) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  node.textContent = value == null ? "" : String(value);
  return node;
}
```

- [ ] Parse known event types only; render unknown events as bounded plain text.
- [ ] Track `event.lastEventId`; after WAITING/decision create a new EventSource with `?cursor={lastEventId}`. Deduplicate durable IDs in a Set and never deduplicate live TOKEN by content.
- [ ] On each WAITING/terminal/reconnect/error, re-fetch task and approvals as authoritative correction.
- [ ] Detect `search_knowledge` tool result JSON and create per-hit citation buttons that fetch safe source summaries.
- [ ] Detect MCP tool cards by namespaced name; label `create_ticket` as write/approval required.
- [ ] Disable decision buttons while request is in flight; handle same-decision 200 and conflict 409 visibly.
- [ ] Keep goal/result/error/argument/result payloads bounded in the DOM and use `textContent` everywhere.
- [ ] Rerun static contract; expected green.

### Step 2.5: Prove the console-facing HTTP flow

- [ ] `DemoConsoleFlowIT` starts the app on a random port with scripted demo mode and real infrastructure/Fake MCP.
- [ ] Use HTTP calls (not direct services) to create preset task, consume SSE until WAITING, fetch approval/citation, approve, reconnect with cursor, and assert COMPLETED task/final ticket.
- [ ] Assert replay after the cursor does not repeat prior durable IDs and final REST state agrees with events.
- [ ] This test need not run JavaScript; backend/static safety is covered by API and static contracts, while compose smoke exercises the served page/resources.
- [ ] Run:

```bash
./mvnw -B -Dit.test=DemoConsoleFlowIT verify
```

Expected: green.

### Step 2.6: Verify and commit

- [ ] Run:

```bash
rg -n "innerHTML|insertAdjacentHTML|document\.write|eval\(" src/main/resources/static
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
```

Expected: unsafe search is empty; all tests green.

- [ ] Commit:

```bash
git add src/main/java/com/reagent/api src/main/resources/static src/test/java/com/reagent/api src/test/java/com/reagent/ui
git commit -m "feat: add incident response demo console"
```

---

## Task 3: Add one-command Docker demos, CI jobs, and final documentation

**Files:**

- Create: `Dockerfile`
- Create: `.dockerignore`
- Modify: `docker-compose.yml`
- Create: `.env.example`
- Create: `scripts/lib/demo-common.sh`
- Create: `scripts/demo-up.sh`
- Create: `scripts/demo-down.sh`
- Create: `scripts/demo-reset.sh`
- Create: `scripts/demo-smoke.sh`
- Create: `scripts/demo-failover.sh`
- Create: `scripts/verify-all.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `.gitignore`
- Modify: `README.md`
- Create: `docs/acceptance/README.md`
- Create: `src/test/java/com/reagent/packaging/ComposeContractTest.java`
- Create: `src/test/java/com/reagent/packaging/ShellScriptContractTest.java`

**Consumes:** both executable jars, health/readiness endpoints, static console, scripted/chaos profiles, full acceptance tests.

**Produces:** reproducible container images, Redis 8 full stack, safe scripts, normal/smoke/failover demos, three-job CI, final evidence/documentation.

### Step 3.1: Write packaging/script contract tests first

- [ ] `ComposeContractTest` parses compose YAML as text/structured YAML through Jackson if already available and asserts:
  - services MySQL 8, Redis 8, Jaeger, Fake MCP, Worker A, optional Worker B;
  - no hard-coded global `container_name`;
  - healthchecks and dependency health conditions;
  - only Worker A maps 8080 normally; Worker B maps 8081 only in failover profile;
  - shared MySQL/Redis/workspace volumes;
  - required environment values use variables/defaults and no real key;
  - normal profile does not enable `demo-chaos`.
- [ ] `ShellScriptContractTest` asserts scripts use `set -Eeuo pipefail`, bounded wait functions, explicit compose project/file, no `docker system prune`, no broad `rm -rf`, no implicit `down -v` outside reset, and failover resolves a labeled Worker A container before kill.
- [ ] Run:

```bash
./mvnw -B -Dtest=ComposeContractTest,ShellScriptContractTest test
```

Expected red: files missing/current compose violates contract.

### Step 3.2: Build both jars in one multi-stage Dockerfile

- [ ] Builder stage copies Maven wrapper/POM first for cache, resolves dependencies, then copies sources and runs `./mvnw -B -DskipTests package`.
- [ ] Define runtime target `reagent` copying normal jar and target `fake-mcp` copying classifier jar; use a Java 21 JRE, non-root user, `/var/reagent/workspaces`, and exec-form entrypoint.
- [ ] Add a small JVM container memory policy through environment, not hard-coded host assumptions.
- [ ] `.dockerignore` excludes `.git`, `.env`, target, IDE files, planning scratch, and local workspaces but includes Maven wrapper/source/resources.
- [ ] Run:

```bash
docker build --target reagent -t reagent:test .
docker build --target fake-mcp -t reagent-fake-mcp:test .
```

Expected: both images build; no secret copied from `.env`.

### Step 3.3: Compose the full normal and failover stack

- [ ] Update compose:
  - MySQL `mysql:8.0` with reagent database/health/volume;
  - Redis `redis:8.0-alpine` with ping health/volume if persistence enabled;
  - Jaeger with OTLP/health/UI ports;
  - Fake MCP image target, MySQL dependency, `/mcp`, actuator health, profile `fake-mcp,${FAKE_MCP_EXTRA_PROFILE:-default}`;
  - Worker A normal app with MySQL/Redis/Fake MCP/Jaeger URLs, Redis stream transport, shared workspace, port 8080, readiness healthcheck;
  - Worker B same image/config with distinct worker ID, compose profile `failover`, port 8081, no duplicate knowledge initialization race due Redis lock.
- [ ] Set Redis image to 8 everywhere, including comments/docs. Remove old manual Maven instructions from compose comments.
- [ ] Do not mount source code or host Docker socket into app containers.
- [ ] Rerun compose contract; expected green.

### Step 3.4: Implement safe shared shell helpers

- [ ] `demo-common.sh` defines project `reagent-demo`, explicit compose file/root, colored-but-readable logging, command checks, `compose()`, `wait_service_health(service, seconds)`, `wait_http(url, seconds)`, and bounded task polling.
- [ ] Resolve container IDs only with:

```bash
docker compose -p reagent-demo -f docker-compose.yml ps -q reagent-worker-a
```

and verify exactly one non-empty ID plus compose project/service labels before kill/reset operations.
- [ ] JSON parsing uses deterministic compact API responses plus narrowly scoped `sed` extraction helpers that reject missing/multiple fields; do not require `jq` or Python.
- [ ] All scripts trap errors and print `docker compose ps` plus bounded relevant logs, never secrets.

### Step 3.5: Implement normal up/down/reset experience

- [ ] `.env.example` contains the sentinel `DEEPSEEK_API_KEY=replace-me`, optional ports, LLM mode/model/base URL, and no real credential.
- [ ] `demo-up.sh`:
  - validates Docker/Compose and `.env` key for openai mode;
  - builds and starts normal services;
  - waits all healthchecks, `/api/readiness ready=true`, MCP tool count 3, RAG active index/chunk count;
  - reports console `http://localhost:8080`, API/readiness, and Jaeger only after success.
- [ ] `demo-down.sh` stops the explicit project without `-v`.
- [ ] `demo-reset.sh` asks for an exact confirmation unless `--yes`, validates project labels, then removes only this project's containers/volumes and restarts through `demo-up.sh` if requested.
- [ ] Make scripts executable and rerun shell contract.

### Step 3.6: Implement deterministic compose smoke

- [ ] `demo-smoke.sh` sets scripted mode and `demo-smoke` profile, requires no key, starts the full stack, POSTs preset incident task, waits WAITING, confirms ticket count zero, approves, waits COMPLETED, and asserts:
  - final result contains `runbooks-checkout-db-pool` citation;
  - final result contains `OPS-` ticket ID;
  - Fake MCP unique ticket count is one;
  - task status/profile are COMPLETED/incident-ops.
- [ ] Write a readable `target/acceptance/compose-smoke.md` with URLs/IDs/counts and no secret.
- [ ] Use bounded total timeout and print diagnostics on failure.
- [ ] Run:

```bash
./scripts/demo-smoke.sh
```

Expected: PASS summary and one unique ticket.

### Step 3.7: Implement automated dangerous-window failover

- [ ] `demo-failover.sh` sets scripted mode, profiles `demo-chaos` and compose `failover`, enables short lease/scan values, and starts Workers A/B plus Fake MCP.
- [ ] Arm Fake MCP's post-ticket-commit response gate, submit/approve task through Worker A, and poll the read-only fault endpoint until `reached=true` and unique ticket count one.
- [ ] Resolve/validate Worker A container ID and execute only:

```bash
docker compose -p reagent-demo -f docker-compose.yml kill reagent-worker-a
```

- [ ] Release/allow timeout of the Fake MCP gate, wait for Worker B to claim a higher epoch and task COMPLETED, then assert same ticket ID, attempt count two, unique count one, and a recovery event/span reference.
- [ ] Write `target/acceptance/compose-failover.md`; print a concise proof table.
- [ ] Run:

```bash
./scripts/demo-failover.sh
```

Expected: Worker B takeover PASS, `uniqueTicketCount=1`, no manual action.

### Step 3.8: Split CI into three evidence jobs

- [ ] Replace the single job with:
  - `unit`: JDK 21, `./mvnw -B clean test`, upload Surefire reports;
  - `integration`: JDK 21, `docker info`, pre-pull MySQL/Redis/sandbox images, `./mvnw -B -Pci verify`, upload Surefire/Failsafe and `target/acceptance` even on failure;
  - `compose-smoke`: after integration, `./scripts/demo-smoke.sh` then `./scripts/demo-failover.sh`, upload compose summaries/log bundle, always run scoped `demo-down.sh` cleanup.
- [ ] Cache Maven only; do not cache `.env`, DB volumes, tickets, or model output containing user data.
- [ ] Ensure no paid key secret is referenced by unit/integration/compose jobs.
- [ ] Add workflow concurrency cancellation for superseded branch runs without interrupting main pushes.

### Step 3.9: Update final documentation from verified output

- [ ] README sections, in this order:
  - what ReAgent proves and final architecture diagram (text/Mermaid is sufficient);
  - current vs final capability table;
  - prerequisites and first-time `.env` configuration;
  - one-command normal demo and expected URLs;
  - five-minute interview script showing citation → metrics/logs → approval → ticket;
  - automated failover demo and its one-ticket meaning;
  - test commands/layers and CI artifact locations;
  - real LLM smoke vs deterministic CI distinction;
  - trust/safety boundaries and honest exactly-once limitation;
  - model dependency size/first build expectation;
  - troubleshooting readiness failures.
- [ ] `docs/acceptance/README.md` maps each approved criterion to its test/script and report file; do not hard-code a test count that will drift.
- [ ] Keep `.env` ignored. Existing `target/` ignore already covers local acceptance output; CI uploads it before job cleanup.

### Step 3.10: Run the complete release gate and commit

- [ ] Run:

```bash
./scripts/verify-all.sh
rg -n "TODO|TBD|FIXME|placeholder|disabledWithoutDocker" pom.xml src scripts .github README.md docs/acceptance
rg -n "innerHTML|insertAdjacentHTML|document\.write|eval\(" src/main/resources/static
git diff --check
git status --short
```

`verify-all.sh` must execute `clean test`, `-Pci verify`, compose smoke, and compose failover in that order and stop on the first failure.

Expected: all gates pass; placeholder/unsafe searches are empty except deliberate negative assertions inside tests; status lists only intended changes.

- [ ] Commit:

```bash
git add Dockerfile .dockerignore docker-compose.yml .env.example scripts .github/workflows/ci.yml .gitignore README.md docs/acceptance src/test/java/com/reagent/packaging
git commit -m "build: ship one-command interview demo evidence"
```

## Plan 4 Completion Gate

- [ ] Readiness stays DOWN until DB, Redis, RAG, MCP, and profile catalog are genuinely ready.
- [ ] Normal OpenAI-compatible mode and explicitly gated scripted modes both work.
- [ ] Acceptance JSON/Markdown and Surefire/Failsafe reports are generated without secrets.
- [ ] Static console shows timeline, citations, MCP calls, approval, recovery, and final ticket.
- [ ] Static resources contain no unsafe HTML injection API.
- [ ] `demo-up.sh` gives a full healthy stack after one-time key configuration.
- [ ] `demo-smoke.sh` completes the full fake flow with one ticket.
- [ ] `demo-failover.sh` automatically kills only Worker A after remote commit and proves Worker B completes with one unique ticket.
- [ ] CI has unit, integration, and compose-smoke jobs with downloadable evidence.
- [ ] README matches commands actually run and states the downstream idempotency boundary honestly.
- [ ] Use `superpowers:requesting-code-review`, address all material findings, rerun `superpowers:verification-before-completion`, and only then claim the overall feature complete.
