# ReAgent Observability, Console, and Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已经通过事故恢复测试的 Java/Python 闭环包装为可观察、可操作、可一键复现的完整应用，并在 CI 中产出机器可读验收证据。

**Architecture:** Task 12 聚合 Java/Python/MySQL/Redis/RAG/MCP readiness，贯通 W3C trace，并从权威状态生成验收报告。Task 13 以 Spring Boot 静态资源实现无 Node 控制台，只通过 REST/SSE 展示和操作已有状态。Task 14 构建两个镜像、完整 Compose、happy/reject/failover 脚本和四段 CI 门禁。

**Tech Stack:** Spring Boot Actuator/MVC/SSE、OpenTelemetry/Jaeger、Starlette/OpenTelemetry、HTML/CSS/vanilla JavaScript、Docker multi-stage builds、Docker Compose、Bash/curl、GitHub Actions、Maven、uv。

## Global Constraints

- 先通过 `2026-07-19-reagent-mcp-incident.md` Completion Gate；本计划不改变 Agent 状态机、RAG 或 MCP 业务语义。
- readiness 只在 MySQL、Redis Streams、Python、RAG active index、MCP discovery、Profile catalog 全部可用时为 UP。
- readiness/acceptance 响应不得泄露 URL、凭据、完整 Schema、完整文档、完整日志、完整参数或模型输出。
- Java 传播 W3C trace context；Python 提取并为 RAG/MCP/ticket 创建子 span。trace 属性只记录 bounded ID/count/status。
- UI 是权威 REST/SSE 状态的视图，不成为审批或恢复真相源。
- 任意告警、LLM、RAG、MCP、错误和理由文本只使用 `textContent` 或逐字段 DOM，不使用 `innerHTML`、`insertAdjacentHTML`、`document.write` 或 `eval`。
- 不引入 Node、npm、React 或 Vue；静态资源由 Spring Boot 直接提供。
- 普通启动不启用 scripted LLM、acceptance 或 chaos；`demo-smoke` 与 `demo-chaos` 必须显式选择。
- `demo-down.sh` 保留 volume；只有 `demo-reset.sh` 执行本 Compose project 的 `down -v`。
- 所有脚本有固定 project name、bounded wait、明确错误输出、可重复运行，且不依赖 jq。
- README 只声明自动测试/脚本已经证明的行为；不创建逐 Task 面试材料。

---

### Task 12: Readiness, cross-service traces, and acceptance evidence

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/reagent/health/ComponentReadiness.java`
- Create: `src/main/java/com/reagent/health/ReAgentReadiness.java`
- Create: `src/main/java/com/reagent/health/ReAgentReadinessHealthIndicator.java`
- Create: `src/main/java/com/reagent/api/ReadinessController.java`
- Create: `src/main/java/com/reagent/acceptance/PythonAcceptanceClient.java`
- Create: `src/main/java/com/reagent/acceptance/AcceptanceEvidence.java`
- Create: `src/main/java/com/reagent/acceptance/AcceptanceService.java`
- Create: `src/main/java/com/reagent/api/AcceptanceController.java`
- Create: `src/main/java/com/reagent/demo/DemoLlmProperties.java`
- Create: `src/main/java/com/reagent/demo/DemoModeGuard.java`
- Create: `src/main/java/com/reagent/demo/ScriptedIncidentLlmClient.java`
- Modify: `src/main/java/com/reagent/llm/OpenAiCompatibleClient.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-demo-smoke.yml`
- Create: `src/main/resources/application-demo-chaos.yml`
- Modify: `services/agent-capabilities/pyproject.toml`
- Create: `services/agent-capabilities/src/agent_capabilities/observability.py`
- Modify: `services/agent-capabilities/src/agent_capabilities/app.py`
- Modify: `services/agent-capabilities/src/agent_capabilities/readiness.py`
- Create: `src/test/java/com/reagent/health/ReAgentReadinessHealthIndicatorTest.java`
- Create: `src/test/java/com/reagent/demo/ScriptedIncidentLlmClientTest.java`
- Create: `src/test/java/com/reagent/acceptance/AcceptanceReportWriter.java`
- Create: `src/test/java/com/reagent/acceptance/IncidentAcceptanceIT.java`
- Create: `src/test/java/com/reagent/obs/CrossServiceTraceIT.java`
- Create: `services/agent-capabilities/tests/test_observability.py`
- Modify: `services/agent-capabilities/tests/test_acceptance.py`

**Interfaces:**

- Consumes: Java runtime state, Python readiness/acceptance, real scenario fixtures and current OTel config.
- Produces: `/actuator/health/readiness`, `/api/readiness`, `/api/acceptance/tasks/{taskId}`, scripted demo mode, safe trace spans and `target/acceptance` reports.

- [ ] **Step 12.1: Add Actuator and write aggregate readiness tests first**

Add `spring-boot-starter-actuator`. Expose only `health` and `info`; enable liveness/readiness groups and disable environment/config/heapdump endpoints.

Exact safe records:

```java
public record ComponentReadiness(
        String name,
        boolean ready,
        String version,
        String reason
) {}

public record ReAgentReadiness(
        boolean ready,
        Instant checkedAt,
        List<ComponentReadiness> components
) {}
```

Tests independently fail Java DB, Redis transport, Python HTTP, RAG active version, MCP discovery and incident profile resolution. Assert aggregate UP only when all required components are ready and no response contains `url`, `password`, `apiKey`, full schema or document content.

Run:

```bash
./mvnw -B -Dtest=ReAgentReadinessHealthIndicatorTest test
```

Expected RED.

- [ ] **Step 12.2: Implement one readiness source for Actuator, API and scripts**

`ReAgentReadinessHealthIndicator` and `GET /api/readiness` consume the same `ReAgentReadiness` service result. API always returns HTTP 200 with `ready=false` during initialization so scripts can show reasons; Actuator readiness returns DOWN/non-2xx for container health checks. Component reasons are stable codes such as `database-unavailable`, `rag-index-not-ready`, and `mcp-discovery-failed`.

Run readiness tests; expected GREEN.

- [ ] **Step 12.3: Gate the deterministic demo LLM**

Make `OpenAiCompatibleClient` conditional on `reagent.llm.mode=openai` with match-if-missing. Register `ScriptedIncidentLlmClient` only when `reagent.llm.mode=scripted`; `DemoModeGuard` rejects scripted mode unless active profile is `demo-smoke` or `demo-chaos`.

The scripted client uses `IncidentScenarioFixture` but derives final citation and ticket IDs from actual prior tool messages. In demo profiles, it parses the already persisted first user goal and derives tool-call IDs from its unique `externalAlertId` (for example `call-create-ticket-<sha256(externalAlertId)[:16]>`), so no LLM interface change is needed and a rerun cannot reuse a prior task's Python idempotency key. Tests assert production profile + scripted mode fails startup, wrong catalog/messages fail immediately, extra/missing turns fail, and two external alert IDs produce different call IDs.

Run:

```bash
./mvnw -B -Dtest=ScriptedIncidentLlmClientTest test
```

Expected GREEN.

- [ ] **Step 12.4: Add Python trace extraction and bounded spans**

Add locked dependencies `opentelemetry-exporter-otlp-proto-http` and `opentelemetry-instrumentation-asgi`. Configure one tracer provider at lifespan startup and cleanly flush/shutdown it.

Required Python span names:

```text
rag.embed
rag.index
rag.search
mcp.query_metrics
mcp.search_logs
mcp.create_ticket
ticket.insert_or_read
```

Extract `traceparent`/`tracestate` from incoming headers. Allowed attributes are service/version, KB/index/topK/hit count/chunk IDs, MCP tool, hashed or bounded tool_call_id, deduplicated flag, alert source/external ID. Tests reject values whose key matches token/key/password/payload/content/evidence/log/output and clamp any string attribute to 256 chars.

Run:

```bash
cd services/agent-capabilities
uv run pytest tests/test_observability.py -q
```

Expected RED, then GREEN.

- [ ] **Step 12.5: Complete Java trace vocabulary and prove one distributed trace**

Required Java spans/events include `agent.task`, `agent.step`, `agent.recovery`, `approval.wait`, `approval.decision`, `rag.http`, `mcp.initialize`, `mcp.list_tools`, `mcp.call_tool`, and `RECOVERY_ATTEMPT`. Ensure Worker A and Worker B logical roots still derive the same trace ID from task ID.

`CrossServiceTraceIT` starts Jaeger, Java Worker A/B and Python, runs the dangerous scenario, queries Jaeger API, and asserts one trace contains both Java/Python service names, both worker epochs, RAG search, create-ticket, ticket insert/read and recovery spans. It asserts forbidden full text is absent from exported tags.

Run:

```bash
./mvnw -B -Dit.test=CrossServiceTraceIT verify
```

Expected GREEN.

- [ ] **Step 12.6: Aggregate safe acceptance evidence**

`AcceptanceService` reads authoritative Java task/profile/tool ledger/approvals/events and the bounded Python acceptance API. It returns:

```java
public record AcceptanceEvidence(
        int contractVersion,
        String taskId,
        String profileId,
        String taskStatus,
        List<String> citationIds,
        List<String> citationSources,
        List<String> mcpTools,
        String approvalDecision,
        List<Long> workerEpochs,
        String ticketId,
        int createTicketAttempts,
        int uniqueTicketCount,
        boolean passed
) {}
```

`PythonAcceptanceClient` deserializes the shared fixture into this strict internal record and rejects unknown fields, negative counts, more than 16 ticket IDs, invalid ticket IDs or an unexpected scope:

```java
record PythonAcceptanceResponse(
        int contractVersion,
        String scope,
        Map<String, Integer> toolAttempts,
        int createTicketAttempts,
        int uniqueTicketCount,
        List<String> ticketIds,
        String faultGateState
) {}
```

`PythonAcceptanceClient` obtains the task's persisted `create_ticket` call ID from the Java ledger and calls `/internal/acceptance?idempotencyKey={urlEncodedToolCallId}`; reject paths query the REJECTED call ID and therefore receive zero attempts rather than global counts from older scenarios. If a no-hit or pre-ticket failure has no `create_ticket` ledger row, Java returns zero attempts/unique tickets without issuing an unscoped Python query. `GET /api/acceptance/tasks/{taskId}` is enabled only in demo-smoke/demo-chaos/test. It never exposes messages, arguments, evidence or full output.

- [ ] **Step 12.7: Generate machine-readable acceptance reports in tests**

`IncidentAcceptanceIT` runs happy, reject, dangerous crash, no-hit and Schema-drift cases. `AcceptanceReportWriter` overwrites `target/acceptance/incident-evidence.json` and `.md` at test start and records only the fields above plus test name/duration.

Run:

```bash
./mvnw -B -Dit.test=IncidentAcceptanceIT verify
test -s target/acceptance/incident-evidence.json
test -s target/acceptance/incident-evidence.md
! rg -ni 'api.?key|password|authorization|arguments_snapshot|idempotency_key|mysql://|redis://|private.?key' target/acceptance
! rg -n 'SECRET_SENTINEL|ARGUMENT_SENTINEL|FULL_LOG_SENTINEL' target/acceptance
```

Expected GREEN and no sensitive match. Do not scan the generic word `evidence`, because it is part of the safe report title and field vocabulary; instead seed the three sentinel values into hidden task/tool/log inputs and prove their exact absence.

- [ ] **Step 12.8: Verify and commit Task 12**

```bash
./mvnw -B test
./mvnw -B -Pci verify
cd services/agent-capabilities
uv lock --check
uv run ruff check .
uv run pyright
uv run pytest -m "not quality" -q
git diff --check
```

Commit:

```bash
git add pom.xml src/main src/test services/agent-capabilities
git commit -m "feat: add readiness traces and acceptance evidence"
```

---

### Task 13: Thin static incident console and browser flow

**Files:**

- Create: `src/main/java/com/reagent/api/TaskView.java`
- Create: `src/main/java/com/reagent/api/IncidentSummaryView.java`
- Modify: `src/main/java/com/reagent/api/TaskController.java`
- Modify: `src/main/java/com/reagent/api/IncidentController.java`
- Modify: `src/main/java/com/reagent/api/ApprovalController.java`
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/styles.css`
- Create: `src/main/resources/static/app.js`
- Create: `src/main/resources/static/favicon.svg`
- Create: `src/test/java/com/reagent/api/DemoApiContractTest.java`
- Create: `src/test/java/com/reagent/ui/DemoConsoleContractTest.java`
- Create: `src/test/java/com/reagent/ui/DemoConsoleFlowIT.java`

**Interfaces:**

- Consumes: incident API, task status/SSE, approval API, readiness and acceptance.
- Produces: safe no-build web console, browser replay cursor, happy/reject/reconnect HTTP flow evidence.

- [ ] **Step 13.1: Invoke the frontend design skill and write static/API contract tests**

Before editing static assets, invoke `frontend-design` and preserve the approved visual direction: compact operations incident console, neutral dark navy/graphite surfaces, cyan evidence, amber write risk, green verified state, red crash/fence, monospace IDs, no generic card grid detached from the incident timeline.

`DemoApiContractTest` covers task view, incident accepted response, approvals, readiness, acceptance and SSE `cursor` query fallback. `Last-Event-ID` header wins over query cursor.

`DemoConsoleContractTest` asserts landmark IDs, event names, approve/reject actions, `EventSource`, the two exact `localStorage` keys, absence of `sessionStorage`, `textContent`, and zero unsafe DOM APIs.

Run:

```bash
./mvnw -B -Dtest=DemoApiContractTest,DemoConsoleContractTest test
```

Expected RED: bounded task view and static files are absent.

- [ ] **Step 13.2: Add bounded task/API views and replay cursor**

Replace controller-internal maps with immutable views while preserving existing JSON fields:

```java
public record IncidentSummaryView(
        String source,
        String externalAlertId,
        String service,
        String severity,
        Instant startedAt
) {}

public record TaskView(
        String taskId,
        String status,
        String goal,
        String result,
        String profile,
        int recoveryCount,
        String ownerId,
        long leaseEpoch,
        Instant createdAt,
        Instant updatedAt,
        IncidentSummaryView incident
) {}
```

`TaskController` looks up `IncidentIntakeRepository.findByTaskId(taskId)` and maps only the five bounded incident fields above; coding tasks return `incident=null`. Exclude system prompt, profile snapshot, tool arguments and messages. This safe summary lets a fresh browser process restore the alert header using only its stored task ID.

Extend SSE with `@RequestParam(required=false) String cursor`; normalize header/query to one opaque cursor, header first. Do not change event persistence or live transport semantics.

- [ ] **Step 13.3: Build semantic HTML and responsive CSS**

`index.html` contains one linear incident workspace:

- service/RAG/MCP/MySQL/Redis readiness strip;
- fixed checkout alert trigger with source, external ID, severity and service;
- task/worker/epoch summary;
- chronological investigation timeline;
- RAG citation cards;
- metrics/log result sections;
- visually distinct create-ticket approval panel;
- crash/takeover/dedup events;
- final diagnosis/ticket and Jaeger/acceptance links;
- accessible labels, keyboard focus, reduced-motion support and live status region.

Below 900px it becomes one column without horizontal scrolling. Use only local CSS/system fonts and repo-native `favicon.svg`; no CDN.

- [ ] **Step 13.4: Implement safe state/event rendering**

Use a single helper:

```javascript
function textElement(tag, className, value) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  node.textContent = value == null ? "" : String(value).slice(0, 4000);
  return node;
}
```

The client must:

- trigger `POST /api/incidents` with the fixed alert;
- store only task ID and last durable event ID in `localStorage` under versioned keys (`reagent.demo.v1.taskId` and `reagent.demo.v1.cursor`); never store alert text, messages, tool arguments, approval reasons, citations, logs, ticket content or credentials;
- open EventSource with `?cursor=` after browser restart;
- validate task IDs with `/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/` and cursors with `/^[A-Za-z0-9][A-Za-z0-9:._-]{0,127}$/`; discard malformed values and pass valid cursors through `URLSearchParams`, never string concatenation;
- deduplicate only durable event IDs, never TOKEN text by content;
- parse only known event shapes and render unknown data as bounded plain text;
- re-fetch task/approvals/readiness on reconnect, WAITING and terminal events;
- disable decision controls during requests and show 409 conflicts;
- distinguish REJECTED, FENCED, recovery and deduplicated events;
- never render arbitrary HTML from any payload.

- [ ] **Step 13.5: Prove the console-facing flow over HTTP**

`DemoConsoleFlowIT` starts the app with scripted mode and real Python infrastructure, fetches all static resources, posts the alert, consumes SSE to WAITING, checks citations/metrics/logs, approves, reconnects from last event ID and asserts COMPLETED with ticket. A second test rejects and asserts no ticket. A third creates a fresh client state with only the two persisted IDs, re-fetches `TaskView`/approvals/readiness, asserts the incident header is restored, reconnects from the cursor and observes no duplicate durable IDs.

Run:

```bash
./mvnw -B -Dit.test=DemoConsoleFlowIT verify
```

Expected GREEN.

- [ ] **Step 13.6: Verify DOM safety and commit Task 13**

```bash
! rg -n "innerHTML|insertAdjacentHTML|document\.write|eval\(" src/main/resources/static
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
```

Commit:

```bash
git add src/main/java/com/reagent/api src/main/resources/static \
  src/test/java/com/reagent/api src/test/java/com/reagent/ui
git commit -m "feat: add incident response console"
```

---

### Task 14: Images, Compose, demo scripts, CI, README, and acceptance docs

**Files:**

- Create: `Dockerfile`
- Create: `.dockerignore`
- Modify: `services/agent-capabilities/Dockerfile`
- Modify: `services/agent-capabilities/.dockerignore`
- Modify: `docker-compose.yml`
- Create: `deploy/mysql/init/001-create-schemas.sql`
- Create: `.env.example`
- Create: `scripts/lib/demo-common.sh`
- Create: `scripts/demo-up.sh`
- Create: `scripts/demo-alert.sh`
- Create: `scripts/demo-smoke.sh`
- Create: `scripts/demo-reject.sh`
- Create: `scripts/demo-failover.sh`
- Create: `scripts/demo-down.sh`
- Create: `scripts/demo-reset.sh`
- Create: `scripts/verify-all.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `.gitignore`
- Modify: `README.md`
- Create: `docs/acceptance/README.md`
- Create: `src/test/java/com/reagent/packaging/ComposeContractTest.java`
- Create: `src/test/java/com/reagent/packaging/ShellScriptContractTest.java`

**Interfaces:**

- Consumes: Java jar, Python locked service, safe readiness/acceptance APIs and chaos gate.
- Produces: reproducible images, complete stack, normal/happy/reject/failover commands, four CI gates and final verified documentation.

- [ ] **Step 14.1: Write packaging and shell safety tests first**

`ComposeContractTest` asserts services `mysql`, `redis`, `jaeger`, `agent-capabilities`, `reagent-worker-a`, and failover-profile `reagent-worker-b`; no global `container_name`; health conditions; Redis 8; MySQL 8; internal network; named volumes; no real key; normal profile excludes chaos. The only normal host mappings are Java UI/API `8080` and Jaeger UI `16686`; MySQL, Redis, Python and OTLP stay internal.

`ShellScriptContractTest` asserts every script uses `set -Eeuo pipefail`, fixed compose project/file, bounded wait, no `docker system prune`, no broad `rm -rf`, no `down -v` outside reset, and failover resolves Worker A via `docker compose ps -q reagent-worker-a` before kill.

Run:

```bash
./mvnw -B -Dtest=ComposeContractTest,ShellScriptContractTest test
```

Expected RED.

- [ ] **Step 14.2: Build deterministic non-root images**

Java Dockerfile uses Maven/JDK 21 build stage and JRE 21 runtime stage, copies only the built jar, creates `/var/reagent/workspaces`, runs non-root and defines an HTTP readiness healthcheck. Python image installs exactly `uv.lock`, copies corpus, runs Alembic through lifespan, runs non-root and healthchecks `/internal/readiness`.

`.dockerignore` files exclude `.git`, worktrees, targets, reports, caches, secrets and local env while retaining Maven wrapper/uv lock/corpus.

- [ ] **Step 14.3: Compose the two-service application and persistent stores**

MySQL init creates two schema-scoped demo accounts; neither account receives privileges on the other service's schema:

```sql
CREATE DATABASE IF NOT EXISTS reagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS fake_ops CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'reagent_app'@'%' IDENTIFIED BY 'reagent-local-only';
CREATE USER IF NOT EXISTS 'fake_ops_app'@'%' IDENTIFIED BY 'fake-ops-local-only';
GRANT ALL PRIVILEGES ON reagent.* TO 'reagent_app'@'%';
GRANT ALL PRIVILEGES ON fake_ops.* TO 'fake_ops_app'@'%';
FLUSH PRIVILEGES;
```

These are visibly local-only demo credentials in `.env.example`, not production secrets. Compose points Java only at `reagent_app`/`reagent` and Python only at `fake_ops_app`/`fake_ops`. Fast `ComposeContractTest` parses the rendered Compose/init SQL and asserts separate credentials plus no cross-schema grant; Step 14.5 performs the real login checks and requires a cross-schema `SELECT` to fail for each account. Redis uses `redis:8` with a named data volume. Jaeger maps only UI `16686`; OTLP remains internal. Python depends on MySQL/Redis health; Java depends on Python/MySQL/Redis health.

Worker A and B share Java DB, Redis Streams and workspace volume, use different worker IDs and identical trusted Python URL/server ID. Worker B exists only in Compose profile `failover`. `demo-smoke` enables scripted LLM and acceptance; `demo-chaos` additionally enables the Python/Java deterministic fault gates.

- [ ] **Step 14.4: Implement bounded reusable script helpers**

`scripts/lib/demo-common.sh` defines project name `reagent-demo`, compose command, `wait_http`, `wait_json_field`, and stable JSON string extraction without jq. All waits use a deadline and print compose status/log tails on failure.

Generate a fresh, bounded ID for every invocation; never fall back to the shared fixture ID:

```bash
new_external_alert_id() {
  local scenario="${1:?scenario is required}"
  local stamp nonce value
  case "$scenario" in
    alert|smoke|reject|failover) ;;
    *) echo "invalid scenario: $scenario" >&2; return 2 ;;
  esac
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  nonce="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
  value="ALERT-CHECKOUT-${scenario^^}-${stamp}-${nonce}"
  [[ "$value" =~ ^ALERT-CHECKOUT-(ALERT|SMOKE|REJECT|FAILOVER)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]]
  printf '%s\n' "$value"
}
```

`json_field` uses the host Python 3 standard library to parse one named top-level field from stdin; it never uses regex to parse JSON. `demo-alert.sh` accepts the generated ID as its only optional argument, validates the same regex, obtains the request JSON from `$COMPOSE exec -T agent-capabilities python -m agent_capabilities.fake_ops.alerts "$external_alert_id"`, posts it, and requires a nonblank task ID plus `deduplicated=false`.

Script behavior:

| Script | Exact outcome |
|---|---|
| `demo-up.sh` | build/start normal stack, initialize RAG, wait aggregate readiness |
| `demo-alert.sh` | generate or accept one valid run-scoped alert ID, post it, require `deduplicated=false`, and print alert/task IDs |
| `demo-smoke.sh` | generate a unique `SMOKE` ID, start demo-smoke, trigger, wait approval, approve, assert task-scoped COMPLETED/unique ticket 1 |
| `demo-reject.sh` | generate a unique `REJECT` ID, trigger, reject, assert task-scoped COMPLETED/create-ticket attempts 0 |
| `demo-failover.sh` | generate a unique `FAILOVER` ID, start demo-chaos + Worker B, arm gate, approve, wait committed, kill only Worker A, assert task-scoped higher epoch/attempts≥2/unique=1 |
| `demo-down.sh` | stop stack without deleting volumes |
| `demo-reset.sh` | explicit project-scoped `down -v --remove-orphans` |
| `verify-all.sh` | Java fast/integration, Python fast/integration/quality, compose smoke/failover |

Every script is rerunnable without deleting volumes: it generates and validates its own `externalAlertId`, requires the intake response to be fresh, and reads key-scoped acceptance evidence. A caller-supplied ID is allowed only for `demo-alert.sh` and must pass the same pattern; scenario scripts never reuse it.

- [ ] **Step 14.5: Prove all three demo paths**

Run:

```bash
bash scripts/demo-reset.sh
bash scripts/demo-smoke.sh
bash scripts/demo-reject.sh
bash scripts/demo-failover.sh
bash scripts/demo-down.sh
```

Expected: each scenario prints its task ID, final status and evidence summary; failover prints Worker A/B epochs, attempt count at least two and unique ticket count one; down leaves named volumes.

Before the scenario assertions, execute one permitted same-schema query and one forbidden cross-schema query as each application account from the MySQL container. Both permitted queries must succeed and both cross-schema queries must fail.

- [ ] **Step 14.6: Split CI into four non-skipping gates**

`.github/workflows/ci.yml` jobs:

1. `java-fast`: `./mvnw -B test`;
2. `python-fast`: `uv sync --locked --all-groups`, ruff, pyright, non-integration pytest;
3. `integration-quality`: Java `-Pci verify`, Python integration and quality tests with Docker/model cache;
4. `compose-smoke-failover`: run smoke, reject and failover scripts.

Upload Surefire/Failsafe reports, Python JUnit XML, `rag-quality.json`, `target/acceptance/*`, compose logs and Jaeger trace export on success/failure. Missing Docker or required service is a failed job, not a skip.

- [ ] **Step 14.7: Update README only from verified commands**

README must include:

- product boundary: incident investigation Agent, not monitoring platform or general workflow engine;
- architecture and Java/Python ownership;
- prerequisites and first MiniLM download/cache cost;
- normal startup, deterministic happy/reject/failover commands;
- alert source and monitored checkout signals;
- approval/idempotency/fencing semantics and exactly-once limitation;
- test matrix and artifact locations;
- security/non-goals and explicit reset behavior;
- verified limitations: synthetic data/Fake ticket/no real production action.

`docs/acceptance/README.md` explains how each generated report is produced and how to rerun it. It contains no manually asserted pass count and no interview scripts.

- [ ] **Step 14.8: Final verification and commit**

```bash
bash scripts/verify-all.sh
! rg -n "innerHTML|insertAdjacentHTML|document\.write|eval\(" src/main/resources/static
! rg -n "docs/interview|question-bank|面试卡|口述稿" README.md docs/acceptance scripts
git diff --check
git status --short
```

Expected: all verification succeeds; status contains only intended Task 14 paths.

Commit:

```bash
git add Dockerfile .dockerignore docker-compose.yml deploy .env.example scripts \
  services/agent-capabilities/Dockerfile services/agent-capabilities/.dockerignore \
  .github/workflows/ci.yml .gitignore README.md docs/acceptance \
  src/test/java/com/reagent/packaging
git commit -m "build: package reproducible incident demos"
```

## Plan Completion Gate

- [ ] Aggregate readiness is DOWN for every missing required dependency and UP only for the full stack.
- [ ] One Jaeger trace includes Java Worker A/B and Python RAG/MCP/ticket spans without sensitive content.
- [ ] Acceptance JSON/Markdown are generated from asserted runtime facts and contain no secrets/full payloads.
- [ ] UI triggers alert, renders citations/tools/approval/recovery/ticket, survives SSE reconnect and uses no unsafe DOM sink.
- [ ] Normal startup does not enable scripted LLM or chaos.
- [ ] Happy, reject and true committed-before-response failover scripts all pass.
- [ ] `demo-down.sh` preserves data; only explicit reset deletes this project's volumes.
- [ ] Four CI jobs run with no silent infrastructure skip and upload evidence artifacts.
- [ ] README claims match executable evidence and contain no per-Task interview materials.
