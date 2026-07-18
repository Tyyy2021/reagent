# ReAgent Runtime Evidence and Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the existing runtime's lease/recovery design into executable MySQL/Redis/dual-worker evidence and establish the fenced profile/tool-batch contracts required by RAG, MCP, and durable approval.

**Architecture:** Introduce Flyway and a clean Surefire/Failsafe split, then replace naked epochs and unfenced writes with `TaskRunToken` guarded by a locked task row. Persist a frozen task profile/tool catalog and extract `ToolBatchCoordinator` from `AgentRunner`, using scripted fakes and deterministic fault hooks to prove the unchanged Agent loop before business features are added.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Data JPA/Redis, MySQL 8 Testcontainers, Redis 8 Testcontainers, Flyway, Maven Surefire/Failsafe, JUnit 5, Mockito, OpenTelemetry test exporter.

## Global Constraints

- Read the roadmap and approved specification before Task 1.
- Do not add RAG, MCP, approval tables, UI, compose app services, or README claims in this plan.
- Keep `./mvnw test` Docker-free. All container tests must end with `IT`.
- Keep existing Coding Agent behavior and existing 54-test semantics, except the four Docker sandbox tests move unchanged to Failsafe.
- Every `StateStore` method invoked from a running Agent after claim must accept `TaskRunToken`; control-plane reads/requests must be explicitly named and documented as exceptions.
- Use an injected UTC `Clock`; do not add new `Instant.now()` calls to persistence or lease code.
- A fenced worker stops silently from the task's perspective. Never call `failTask` from the `FencedExecutionException` branch.
- Commit after each task using the exact commit message shown.

---

## Task 1: Separate test lifecycles and prove current MySQL/Redis behavior

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__baseline_runtime.sql`
- Create: `src/test/resources/application-test.yml`
- Create: `src/test/java/com/reagent/testsupport/InfrastructureIT.java`
- Rename: `src/test/java/com/reagent/sandbox/DockerSandboxTest.java` → `src/test/java/com/reagent/sandbox/DockerSandboxIT.java`
- Create: `src/test/java/com/reagent/persist/SchemaMigrationIT.java`
- Create: `src/test/java/com/reagent/persist/StateStoreIT.java`
- Create: `src/test/java/com/reagent/stream/RedisStreamTransportIT.java`

**Consumes:** Existing JPA entities, `StateStore.loadContext`, `TaskEventBus`, `RedisStreamTransport`, Docker sandbox tests.

**Produces:** Flyway V1 schema, a reusable real-infrastructure test base, Docker-free unit lifecycle, real MySQL reconstruction evidence, Redis 8 Streams regression evidence.

### Step 1.1: Write the lifecycle guard test

- [ ] Rename the Docker test file/class to `DockerSandboxIT` and remove Docker-unavailable assumptions so an explicitly requested integration lifecycle fails when its required daemon/image is absent.
- [ ] Use the two Maven lifecycle commands in this task as the red/green guard; do not add an Invoker test for Maven itself.
- [ ] Run:

```bash
./mvnw -B test
```

Expected red result at this point: Surefire still executes `DockerSandboxIT` only if explicitly included; by default it should report the existing non-Docker tests and no Docker assumption lines. If the default pattern still includes it, configure Surefire excludes before proceeding.

### Step 1.2: Configure Maven dependencies and Failsafe

- [ ] Add Boot-managed dependencies `flyway-core`, `flyway-mysql`, `org.testcontainers:testcontainers`, `org.testcontainers:junit-jupiter`, and `org.testcontainers:mysql`.
- [ ] Configure `maven-failsafe-plugin` with `integration-test` and `verify` goals and includes `**/*IT.java`.
- [ ] Add a `ci` Maven profile that sets `reagent.ci=true`; do not make container tests conditional on this property.
- [ ] Keep Surefire's default `*Test` convention and explicitly exclude `**/*IT.java` as a readable guard.
- [ ] Run:

```bash
./mvnw -B -DskipTests dependency:tree
```

Expected: dependency resolution succeeds with one Flyway line, Testcontainers test-scope lines, and no MCP/LangChain4j dependency.

### Step 1.3: Write the fresh and legacy schema tests first

- [ ] Create `SchemaMigrationIT` with one MySQL 8 container and two uniquely named databases inside it: one fresh and one legacy-shaped.
- [ ] Test `createsFreshRuntimeSchemaAtVersionOne`: run Flyway against an empty database, assert V1 success and tables `task`, `message`, `tool_call`, `event`, `flyway_schema_history`.
- [ ] Test `baselinesExistingRuntimeSchemaAtVersionOne`: create the current four tables without Flyway history, configure `baselineOnMigrate(true)` and baseline version `1`, migrate, then assert the history row is a baseline and no table data was removed.
- [ ] Run:

```bash
./mvnw -B -Dit.test=SchemaMigrationIT verify
```

Expected red result: Flyway reports no migration or missing runtime tables because V1 does not exist yet.

### Step 1.4: Add the exact V1 baseline

- [ ] Create V1 with the exact current entity-compatible DDL below. Do not add `IF NOT EXISTS`; an unexpected partially created schema must fail visibly.

```sql
CREATE TABLE task (
    id VARCHAR(255) NOT NULL,
    goal MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL,
    result MEDIUMTEXT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    recovery_count INT NOT NULL DEFAULT 0,
    owner_id VARCHAR(64) NULL,
    lease_expires_at DATETIME(6) NULL,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    control_signal VARCHAR(16) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(255) NULL,
    seq INT NOT NULL,
    role VARCHAR(255) NULL,
    content MEDIUMTEXT NULL,
    tool_calls_json MEDIUMTEXT NULL,
    tool_call_id VARCHAR(255) NULL,
    created_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_msg_task_seq UNIQUE (task_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tool_call (
    id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NULL,
    tool_name VARCHAR(255) NULL,
    arguments MEDIUMTEXT NULL,
    result MEDIUMTEXT NULL,
    status VARCHAR(32) NULL,
    created_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(255) NULL,
    type VARCHAR(32) NULL,
    data MEDIUMTEXT NULL,
    created_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_event_task_id (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] Change runtime JPA from `ddl-auto: update` to `ddl-auto: validate`; enable Flyway with `baseline-on-migrate: true` and baseline version `1`.
- [ ] Rerun `SchemaMigrationIT`; expected green.

### Step 1.5: Add shared MySQL/Redis 8 infrastructure

- [ ] Create `InfrastructureIT` as an abstract `@SpringBootTest` base with static `MySQLContainer<?>` using `mysql:8.0` and `GenericContainer<?>` using `redis:8.0-alpine` exposed on `6379`.
- [ ] Use `@DynamicPropertySource` to provide datasource and Redis host/port; set recovery/tracing off and use `application-test.yml`.
- [ ] Do not annotate containers with `disabledWithoutDocker=true`; Docker absence must throw during Failsafe.
- [ ] In `application-test.yml`, set `spring.jpa.hibernate.ddl-auto=validate`, Flyway enabled, in-process streaming by default, tracing off, and a temporary workspace root supplied by each test.

### Step 1.6: Write and satisfy real StateStore reconstruction evidence

- [ ] In `StateStoreIT`, create a task, append an assistant tool call, mark it in progress, record a tool result, clear the persistence context, and call `loadContext`.
- [ ] Assert exact role order `system,user,assistant,tool`, the same tool-call ID, `DONE` ledger state, and dense message sequence `0,1,2,3`.
- [ ] This test initially uses the current StateStore signatures; Task 2 will update it to `TaskRunToken`.
- [ ] Run:

```bash
./mvnw -B -Dit.test=StateStoreIT verify
```

Expected: green against real MySQL, not H2 or mocks.

### Step 1.7: Write and satisfy Redis 8 Streams regression evidence

- [ ] In `RedisStreamTransportIT`, construct/activate Redis transport and cover:
  - persisted `STEP` replay followed by live `TOOL_RESULT` without loss or duplicate;
  - `TOKEN` is delivered live but absent from MySQL event replay;
  - terminal event sets a positive TTL on `reagent:stream:{taskId}`;
  - stream-expired fallback replays durable events from MySQL.
- [ ] Use latches with bounded `await` calls; do not use fixed sleeps to infer delivery.
- [ ] Run:

```bash
./mvnw -B -Dit.test=RedisStreamTransportIT verify
```

Expected: all Redis 8 cases green.

### Step 1.8: Verify both lifecycles and commit

- [ ] Run:

```bash
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
```

Expected: fast tests have no Docker skips; Failsafe runs Docker sandbox plus MySQL/Redis tests with no skips.

- [ ] Commit:

```bash
git add pom.xml src/main/resources src/test/resources src/test/java/com/reagent/testsupport src/test/java/com/reagent/persist src/test/java/com/reagent/stream src/test/java/com/reagent/sandbox
git commit -m "test: add real runtime infrastructure evidence"
```

---

## Task 2: Fence every runtime write with a task run token

**Files:**

- Create: `src/main/java/com/reagent/core/TaskRunToken.java`
- Create: `src/main/java/com/reagent/core/FencedExecutionException.java`
- Create: `src/main/java/com/reagent/persist/TaskLeaseGuard.java`
- Create: `src/main/java/com/reagent/config/TimeConfiguration.java`
- Modify: `src/main/java/com/reagent/persist/TaskRepository.java`
- Modify: `src/main/java/com/reagent/persist/TaskEntity.java`
- Modify: `src/main/java/com/reagent/persist/MessageEntity.java`
- Modify: `src/main/java/com/reagent/persist/ToolCallEntity.java`
- Modify: `src/main/java/com/reagent/persist/EventEntity.java`
- Modify: `src/main/java/com/reagent/persist/StateStore.java`
- Modify: `src/main/java/com/reagent/persist/MessageRepository.java`
- Modify: `src/main/java/com/reagent/tool/ToolContext.java`
- Modify: `src/main/java/com/reagent/core/TaskControl.java`
- Modify: `src/main/java/com/reagent/core/LeaseHeartbeat.java`
- Modify: `src/main/java/com/reagent/core/FailoverScanner.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/stream/EventStore.java`
- Modify: `src/main/java/com/reagent/stream/JpaEventStore.java`
- Modify: `src/main/java/com/reagent/stream/StreamTransport.java`
- Modify: `src/main/java/com/reagent/stream/TaskEventBus.java`
- Modify: `src/main/java/com/reagent/stream/RedisStreamTransport.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Create: `src/test/java/com/reagent/testsupport/MutableClock.java`
- Create: `src/test/java/com/reagent/core/TaskRunTokenTest.java`
- Create: `src/test/java/com/reagent/persist/StateStoreFencingIT.java`
- Create: `src/test/java/com/reagent/core/LeaseFailoverIT.java`
- Modify: `src/test/java/com/reagent/persist/StateStoreIT.java`
- Modify: `src/test/java/com/reagent/stream/TaskEventBusReplayTest.java`
- Modify: `src/test/java/com/reagent/stream/RedisStreamTransportIT.java`
- Modify: `src/test/java/com/reagent/tool/ToolContextTest.java`

**Consumes:** V1 schema and real-infrastructure support from Task 1.

**Produces:** `TaskRunToken`, locked ownership guard, deterministic time, owner-null recovery query, full stale-write rejection, fenced durable events.

### Step 2.1: Write token and clock unit tests

- [ ] Create `MutableClock` extending `Clock` with an atomic `Instant`, `advance(Duration)`, UTC zone, and no sleeping.
- [ ] Add `TaskRunTokenTest` and assert blank IDs and negative epochs are rejected by record construction.
- [ ] Run:

```bash
./mvnw -B -Dtest=TaskRunTokenTest test
```

Expected red: token class missing.

- [ ] Add `TaskRunToken` exactly as declared in the roadmap and `TimeConfiguration`:

```java
@Configuration
public class TimeConfiguration {
    @Bean
    Clock reagentClock() {
        return Clock.systemUTC();
    }
}
```

- [ ] Rerun the focused test; expected green.

### Step 2.2: Write the concurrent claim and recoverable-query tests

- [ ] In `LeaseFailoverIT`, create two `StateStore` instances over shared repositories with distinct `WorkerIdentity` values and the same `MutableClock`.
- [ ] Test `onlyOneWorkerWinsConcurrentClaim` with a barrier that releases both claim calls together; assert exactly one non-empty `Optional<TaskRunToken>` and one epoch increment.
- [ ] Test `findRecoverableIncludesOwnerNullAndExpiredButNotLiveLease` using three RUNNING tasks.
- [ ] Test `newClaimIncrementsEpochAndFencesOldToken` by advancing the clock past TTL.
- [ ] Run:

```bash
./mvnw -B -Dit.test=LeaseFailoverIT verify
```

Expected red: `StateStore.claim` returns `long`, time cannot be controlled, and owner-null tasks are not scanned.

### Step 2.3: Add row-lock ownership guard and new claim API

- [ ] Add to `TaskRepository`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select t from TaskEntity t where t.id = :id")
Optional<TaskEntity> findByIdForUpdate(@Param("id") String id);

@Query("""
       select t from TaskEntity t
        where t.status = com.reagent.persist.TaskStatus.RUNNING
          and (t.ownerId is null or t.leaseExpiresAt < :now)
        order by t.updatedAt asc
       """)
List<TaskEntity> findRecoverable(@Param("now") Instant now, Limit limit);
```

- [ ] Implement `TaskLeaseGuard.lockOwned(token, allowedStatuses)` in one transaction scope. Check task ID, epoch, worker owner, and allowed state; throw `FencedExecutionException(token, actualOwner, actualEpoch, actualStatus)` on mismatch.
- [ ] Change `StateStore.claim` to return `Optional<TaskRunToken>`, use `clock.instant()`, and change `renew` to accept the record.
- [ ] Rename `findExpired` to `findRecoverable`; update `FailoverScanner` and its log language.
- [ ] Extend `ToolContext` to retain an optional run token while preserving the existing two-argument test/tool constructor. Add `ToolContext(TaskRunToken, Path)` for AgentRunner, derive matching task ID, and make `forCall` preserve the token while binding the call ID.
- [ ] Update `ToolContextTest` to reject a token/task mismatch and prove token preservation across `forCall`.
- [ ] Rerun `LeaseFailoverIT`; expected green.

### Step 2.4: Write the stale-mutator matrix before changing StateStore

- [ ] In `StateStoreFencingIT`, let Worker A claim epoch 1, advance clock, let Worker B claim epoch 2, then parameterize stale Worker A attempts over:
  - `incrementRecoveryCount`;
  - `appendAssistant`;
  - `markInProgress`;
  - `recordToolResult`;
  - `markInDoubt`;
  - runtime `clearControlSignal`;
  - runtime `pauseTask`;
  - runtime `cancelTask`;
  - `completeTask`;
  - `failTask`.
- [ ] For every case assert `FencedExecutionException`, unchanged task state, unchanged message count, and unchanged ledger row.
- [ ] Add `currentTokenWritesDenseMessagesUnderConcurrentEventTraffic` to prove task-row locking keeps message sequence dense.
- [ ] Run:

```bash
./mvnw -B -Dit.test=StateStoreFencingIT verify
```

Expected red: current mutators accept bare task IDs and stale writes succeed.

### Step 2.5: Convert every run mutator

- [ ] Inject `Clock` into `StateStore`; pass `clock.instant()` into entity factories/mutators instead of letting entities call `Instant.now()`.
- [ ] Change run mutators to this shape:

```java
@Transactional
public int appendAssistant(TaskRunToken token, Map<String, Object> assistantMessage) {
    TaskEntity task = leaseGuard.lockOwned(token, EnumSet.of(TaskStatus.RUNNING));
    int seq = messageRepo.nextSequenceForLockedTask(task.getId());
    // save assistant and all PENDING ledger rows in this transaction
    return seq;
}
```

- [ ] Replace `countByTaskId` with `select coalesce(max(seq), -1) + 1` called only while the task row is locked. Keep `(task_id,seq)` unique as the fail-fast invariant.
- [ ] Pass token through recovery counter, assistant, ledger, result, doubt, control consumption, pause/cancel, complete/fail. Return values should not encode fencing; fencing is the typed exception.
- [ ] Update `AgentRunner`, `TaskControl.begin`, and heartbeat to pass the record rather than naked epoch where run ownership is relevant.
- [ ] Catch `FencedExecutionException` before the generic runtime exception in `AgentRunner`; log and stop without publishing FAILED.
- [ ] Rerun `StateStoreFencingIT` and `StateStoreIT`; expected green.

### Step 2.6: Fence durable run events

- [ ] Add `EventStore.appendFenced(TaskRunToken, type, data)` and keep existing `append(taskId,...)` only for explicitly named control-plane publication.
- [ ] In `JpaEventStore.appendFenced`, lock the task row. Require equal `leaseEpoch`; if owner is non-null it must equal token worker. This permits the winning epoch to publish a terminal/wait event immediately after releasing its lease, while an older epoch is rejected.
- [ ] Add `StreamTransport.publish(TaskRunToken, type, data)`; update both transport implementations to use fenced append for non-TOKEN events and use token only as live metadata for TOKEN.
- [ ] Convert every AgentRunner run event to the token overload.
- [ ] Extend `StateStoreFencingIT` with `oldEpochCannotAppendDurableEvent`; assert event row count does not change.
- [ ] Update replay unit fakes with both append methods and rerun:

```bash
./mvnw -B -Dtest=TaskEventBusReplayTest test
./mvnw -B -Dit.test=StateStoreFencingIT,RedisStreamTransportIT verify
```

Expected: all green; old epoch event write fails before insert.

### Step 2.7: Remove uncontrolled time and run regressions

- [ ] Run:

```bash
rg -n "Instant\.now\(\)" src/main/java/com/reagent/core src/main/java/com/reagent/persist src/main/java/com/reagent/stream
```

Expected: no persistence/lease/entity/event timestamp path uses `Instant.now()`; inject the same Clock into event transports/factories and pass `clock.instant()` explicitly.

- [ ] Run:

```bash
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
```

Expected: all unit/integration tests green, no Docker skips under Failsafe.

- [ ] Commit:

```bash
git add src/main/java src/test/java
git commit -m "feat: fence all runtime persistence writes"
```

---

## Task 3: Persist task profiles and freeze the executable tool catalog

**Files:**

- Create: `src/main/resources/db/migration/V2__runtime_profiles_and_batches.sql`
- Create: `src/main/java/com/reagent/tool/ApprovalPolicy.java`
- Modify: `src/main/java/com/reagent/tool/Tool.java`
- Modify: `src/main/java/com/reagent/tool/ToolRegistry.java`
- Modify: `src/main/java/com/reagent/tool/ToolExecutor.java`
- Create: `src/main/java/com/reagent/profile/AgentProfileDefinition.java`
- Create: `src/main/java/com/reagent/profile/AgentProfileProperties.java`
- Create: `src/main/java/com/reagent/profile/AgentProfileRegistry.java`
- Create: `src/main/java/com/reagent/profile/ToolSnapshot.java`
- Create: `src/main/java/com/reagent/profile/TaskProfileSnapshot.java`
- Create: `src/main/java/com/reagent/profile/TaskToolCatalog.java`
- Create: `src/main/java/com/reagent/profile/ToolCatalogResolver.java`
- Create: `src/main/java/com/reagent/profile/SchemaHasher.java`
- Modify: `src/main/java/com/reagent/persist/TaskEntity.java`
- Modify: `src/main/java/com/reagent/persist/ToolCallEntity.java`
- Modify: `src/main/java/com/reagent/persist/StateStore.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/api/TaskController.java`
- Create: `src/main/java/com/reagent/api/ApiExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/profile/ToolCatalogResolverTest.java`
- Create: `src/test/java/com/reagent/profile/AgentProfileRegistryTest.java`
- Create: `src/test/java/com/reagent/profile/TaskProfilePersistenceIT.java`
- Create: `src/test/java/com/reagent/api/TaskControllerTest.java`
- Modify: `src/test/java/com/reagent/persist/SchemaMigrationIT.java`
- Modify: `src/test/java/com/reagent/tool/ToolIdempotencyTest.java`
- Modify: `src/test/java/com/reagent/tool/ToolExecutorTracingTest.java`

**Consumes:** `TaskRunToken`, Flyway baseline, global Tool beans.

**Produces:** two-axis tool metadata, stable canonical schema hashes, persisted task profile snapshots, allowlisted and drift-checked execution catalog, backward-compatible profile API.

### Step 3.1: Write two-axis and duplicate-name tests

- [ ] Extend `ToolIdempotencyTest` to assert a plain local Tool defaults to `SIDE_EFFECTFUL` for replay and `NONE` for approval.
- [ ] Add `ToolCatalogResolverTest.rejectsDuplicateRegistryNames` and `rejectsIllegalOrMissingToolNames`.
- [ ] Run:

```bash
./mvnw -B -Dtest=ToolIdempotencyTest,ToolCatalogResolverTest test
```

Expected red: `ApprovalPolicy` and catalog types do not exist; registry silently overwrites duplicates.

- [ ] Add `ApprovalPolicy`; add to `Tool`:

```java
default ApprovalPolicy approvalPolicy() {
    return ApprovalPolicy.NONE;
}
```

- [ ] Make `ToolRegistry` reject duplicates and illegal function names matching `^[A-Za-z0-9_-]{1,64}$`.
- [ ] Rerun focused tests; expected green.

### Step 3.2: Write canonical snapshot and drift tests

- [ ] Test that logically identical schemas with different map insertion order produce the same SHA-256.
- [ ] Test that changing description does not change `schemaHash`; the old task continues using its persisted description. Changing JSON Schema must change `schemaHash` and make old-task resolution fail.
- [ ] Test catalog allowlist: a tool absent from snapshots is missing from both `toOpenAiSpec` and execution lookup.
- [ ] Run the catalog tests; expected red.
- [ ] Implement `SchemaHasher` by recursively sorting object keys, preserving array order, serializing with the application `ObjectMapper`, and hashing UTF-8 bytes with SHA-256 hex.
- [ ] Implement the roadmap's `ToolSnapshot`, `TaskProfileSnapshot`, and `TaskToolCatalog` records with defensive immutable copies.
- [ ] Implement `ToolCatalogResolver.snapshot(definition)` and `resolve(persistedSnapshot)`; the latter binds runtime Tool instances only after exact name/schema-hash match.
- [ ] Change `ToolExecutor.execute` and `executeConcurrently` to receive `TaskToolCatalog` and look up through it, never global registry.
- [ ] Rerun focused tests and existing executor tracing test; expected green.

### Step 3.3: Write V2 migration upgrade evidence

- [ ] Extend `SchemaMigrationIT` so both fresh and legacy databases finish at V2 with equal column metadata.
- [ ] Assert `task.profile_id`, `task.profile_snapshot`, and `tool_call.assistant_message_seq` exist and both status columns are `VARCHAR(32)`.
- [ ] Run `SchemaMigrationIT`; expected red.
- [ ] Create V2:

```sql
ALTER TABLE task
    ADD COLUMN profile_id VARCHAR(64) NULL,
    ADD COLUMN profile_snapshot MEDIUMTEXT NULL,
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

ALTER TABLE tool_call
    ADD COLUMN assistant_message_seq INT NULL,
    MODIFY COLUMN status VARCHAR(32) NULL;

CREATE INDEX idx_tool_call_task_batch
    ON tool_call (task_id, assistant_message_seq);
```

- [ ] In the migration, backfill legacy task rows to `profile_id='coding'`; leave `profile_snapshot` null only for legacy rows and make `StateStore` materialize a coding snapshot on first recovery before calling the LLM.
- [ ] Rerun migration tests; expected green.

### Step 3.4: Persist and reload the exact task snapshot

- [ ] Write `TaskProfilePersistenceIT`:
  - create a `coding` task and assert profile ID/snapshot are committed with the opening messages;
  - clear persistence context and reload the snapshot byte-for-byte semantically;
  - resolve it after a simulated process restart with identical Tools;
  - change one tool schema and assert `ToolSchemaDriftException` before an LLM or tool call;
  - assert every assistant ledger row records the assistant message sequence.
- [ ] Run the IT; expected red.
- [ ] Update entities and StateStore:

```java
public TaskEntity createTask(String goal, TaskProfileSnapshot snapshot)
public TaskProfileSnapshot loadProfile(String taskId)
public int appendAssistant(TaskRunToken token, Map<String, Object> assistantMessage)
```

- [ ] Serialize snapshot as bounded JSON; reject snapshots over the configured size before writing. Register all tool ledger rows with the returned assistant message sequence in the same transaction.
- [ ] Implement legacy null-snapshot materialization under task-row lock.
- [ ] Rerun the IT; expected green.

### Step 3.5: Add profile registry and compatible API overloads

- [ ] Define `AgentProfileProperties` with `default-id`, profile version, system prompt, tool names, optional knowledge base/index, and MCP server IDs.
- [ ] Configure `coding` with the existing five local tools and the existing system prompt. Do not enable `incident-ops` until RAG/MCP dependencies are registered.
- [ ] `AgentProfileRegistry.snapshot(profileId)` resolves omitted/blank to default and rejects unknown profiles before task creation.
- [ ] Add AgentRunner overloads:

```java
public RunResult run(String goal) { return run(goal, "coding"); }
public RunResult run(String goal, String profileId)
public String submit(String goal) { return submit(goal, "coding"); }
public String submit(String goal, String profileId)
```

- [ ] Change `TaskController.TaskRequest` to `record TaskRequest(String goal, String profile)` and pass it through both sync/async paths.
- [ ] Preserve `POST /api/tasks` and its `?sync=true` behavior exactly; omitted/blank profile resolves to `coding`, while an explicit unknown profile is a 4xx validation error before any task row is created.
- [ ] Add `ApiExceptionHandler` now for invalid/unknown profile and missing task responses; the approval plan extends the same advice with 409 approval conflicts.
- [ ] In `TaskControllerTest`, assert omitted profile invokes coding, explicit unknown profile returns a client error, and status includes the persisted profile ID without changing existing fields.
- [ ] Run:

```bash
./mvnw -B -Dtest=AgentProfileRegistryTest,TaskControllerTest test
./mvnw -B -Dit.test=TaskProfilePersistenceIT,SchemaMigrationIT verify
```

Expected: green.

### Step 3.6: Verify no global tool bypass and commit

- [ ] Run:

```bash
rg -n "registry\.toOpenAiSpec|registry\.get\(" src/main/java/com/reagent
```

Expected: only catalog construction/resolution code may touch global registry; AgentRunner and ToolExecutor do not.

- [ ] Run:

```bash
./mvnw -B test
./mvnw -B -Pci verify
git diff --check
```

Expected: all green.

- [ ] Commit:

```bash
git add src/main/resources src/main/java src/test/java
git commit -m "feat: freeze task profiles and tool catalogs"
```

---

## Task 4: Extract the tool batch and prove AgentRunner recovery

**Files:**

- Create: `src/main/java/com/reagent/core/BatchDisposition.java`
- Create: `src/main/java/com/reagent/core/ToolBatchCoordinator.java`
- Create: `src/main/java/com/reagent/core/DefaultToolBatchCoordinator.java`
- Create: `src/main/java/com/reagent/core/FaultPoint.java`
- Create: `src/main/java/com/reagent/core/FaultContext.java`
- Create: `src/main/java/com/reagent/core/FaultInjector.java`
- Create: `src/main/java/com/reagent/core/InjectedWorkerCrashException.java`
- Create: `src/main/java/com/reagent/config/FaultConfiguration.java`
- Modify: `src/main/java/com/reagent/core/AgentRunner.java`
- Modify: `src/main/java/com/reagent/core/Context.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Create: `src/test/java/com/reagent/testsupport/ScriptedLlmClient.java`
- Create: `src/test/java/com/reagent/testsupport/RecordingTool.java`
- Create: `src/test/java/com/reagent/core/ToolBatchCoordinatorTest.java`
- Create: `src/test/java/com/reagent/core/AgentRunnerIT.java`
- Create: `src/test/java/com/reagent/core/AgentRunnerRecoveryIT.java`

**Consumes:** token-fenced StateStore, frozen catalog, real MySQL/Redis support.

**Produces:** small approval-ready tool-batch seam, deterministic Fake LLM, no-op production fault hooks, AgentRunner happy/recovery/max-attempt evidence.

### Step 4.1: Characterize current batch behavior before extraction

- [ ] Create `ToolBatchCoordinatorTest` around the current behavior using mocks/fakes and cover:
  - PENDING call marks `IN_PROGRESS`, executes once, records DONE and tool message;
  - IN_PROGRESS READ_ONLY/IDEMPOTENT replays;
  - IN_PROGRESS SIDE_EFFECTFUL with no journal records IN_DOUBT without execution;
  - journal-reconciled side effect records DONE without execution;
  - multiple results persist in original tool-call order;
  - DONE/IN_DOUBT terminal calls are not executed. The MCP/approval plan adds the REJECTED case when that enum is introduced.
- [ ] Run:

```bash
./mvnw -B -Dtest=ToolBatchCoordinatorTest test
```

Expected red: coordinator types do not exist.

### Step 4.2: Extract without changing semantics

- [ ] Move `AgentRunner.executeTools`, `canSafelyReplay`, reconciliation message, and in-doubt message into `DefaultToolBatchCoordinator`.
- [ ] Inject StateStore, ToolExecutor, StreamTransport, TaskControl, FaultInjector, and any journal helper it needs.
- [ ] Implement the roadmap interface and return `EXECUTED` for all current paths.
- [ ] Pass `TaskRunToken` and `TaskToolCatalog` to every write, event, classification, and execution call.
- [ ] Reduce AgentRunner's batch call to:

```java
BatchDisposition disposition = toolBatchCoordinator.process(token, toolCtx, ctx, catalog, calls);
if (disposition == BatchDisposition.WAITING_APPROVAL) {
    return "任务正在等待审批。";
}
```

- [ ] Rerun coordinator tests and all existing tool tests; expected green.

### Step 4.3: Add deterministic fault hooks

- [ ] Add the five `FaultPoint` values from the roadmap and an immutable `FaultContext` containing task ID, worker ID, epoch, optional tool-call ID, and optional batch sequence.
- [ ] Provide the default no-op bean with `@Bean` plus `@ConditionalOnMissingBean(FaultInjector.class)`.
- [ ] Call hooks at exact committed boundaries. In this plan wire:
  - assistant commit hook from AgentRunner;
  - after ledger `IN_PROGRESS` from coordinator;
  - after tool-result commit from coordinator.
  The approval and remote-side-effect hooks are wired by the MCP plan.
- [ ] Treat `InjectedWorkerCrashException` like process disappearance: leave task RUNNING and rethrow/return without FAILED. The production no-op path can never produce it.
- [ ] Add unit tests asserting each wired hook fires after the named database state, not before.

### Step 4.4: Build a strict scripted Fake LLM

- [ ] Implement `ScriptedLlmClient` as a queue of expected turns. Each turn asserts the exact allowed tool-name set and an optional predicate over persisted messages, then returns a predetermined `Decision`.
- [ ] Fail immediately on extra/missing turns or unexpected schemas; record call count for recovery assertions.
- [ ] Implement `RecordingTool` with configurable idempotency/approval, call counter, result, and optional latch.
- [ ] Unit-test the fake itself so later scenario failures point to the workflow, not a permissive fake.

### Step 4.5: Prove AgentRunner on real persistence

- [ ] In `AgentRunnerIT`, construct the runner with real MySQL repositories, real StateStore, the in-process stream transport, temporary workspace, ScriptedLlmClient, and one RecordingTool. Redis transport remains covered by its dedicated IT.
- [ ] Test `runsToolThenCompletesAndRebuildsContext`: first turn calls a tool, second returns final; assert COMPLETED, exact four/five-message history, one tool execution, ordered durable events, and catalog only exposes the allowlisted tool.
- [ ] Test `unknownToolCannotExecuteThroughRecovery`: persist a call absent from the snapshot and assert schema/catalog failure before execution.
- [ ] Run:

```bash
./mvnw -B -Dit.test=AgentRunnerIT verify
```

Expected green after minimal wiring.

### Step 4.6: Prove crash recovery and stale-worker stop

- [ ] In `AgentRunnerRecoveryIT`, inject a one-shot crash after assistant persistence. Assert task remains RUNNING with assistant/PENDING ledger committed and no tool result.
- [ ] Advance mutable clock, recover through Worker B, assert the same persisted tool-call ID executes and task completes.
- [ ] Release Worker A and make it attempt its next durable write; assert `FencedExecutionException` and no FAILED event/state.
- [ ] Add `stopsAtMaxRecoveryAttempts` with fixed clock and exact count; assert no paid/network dependency.
- [ ] Run:

```bash
./mvnw -B -Dit.test=AgentRunnerRecoveryIT,LeaseFailoverIT,StateStoreFencingIT verify
```

Expected: green without fixed sleeps.

### Step 4.7: Final plan verification and commit

- [ ] Run:

```bash
./mvnw -B clean test
./mvnw -B -Pci verify
rg -n "private void executeTools|long myEpoch|registry\.toOpenAiSpec" src/main/java/com/reagent
git diff --check
```

Expected: all tests green; search has no legacy batch method, naked AgentRunner epoch, or direct global tool list.

- [ ] Commit:

```bash
git add src/main/java src/test/java
git commit -m "refactor: extract recoverable tool batch coordinator"
```

## Plan 1 Completion Gate

- [ ] Fresh and legacy MySQL schema paths pass through Flyway.
- [ ] Redis 8 Streams replay/live/TTL tests pass.
- [ ] Dual-worker claim has exactly one winner.
- [ ] Every stale runtime mutator and durable event is rejected.
- [ ] Coding profile remains the omitted-profile default.
- [ ] LLM-visible tools and executable tools come from one frozen catalog.
- [ ] AgentRunner happy path and recovery path pass using scripted LLM and real persistence.
- [ ] `./mvnw test` has no Docker skips; `./mvnw -Pci verify` has no container skips.
- [ ] Proceed only then to `2026-07-18-reagent-rag.md`.
