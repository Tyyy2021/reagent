# ReAgent Python RAG and Java Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成可信告警接入、单 Python 能力服务骨架、确定性 RAG 索引与 Java `search_knowledge` 工具，使每个 incident task 冻结并使用可验证的知识索引版本。

**Architecture:** Task 5 在 Java 同一事务内创建 task 与 `incident_intake`，并建立两语言共享 JSON 契约及可启动 Python ASGI 服务。Task 6 由 Python 完成 Markdown 切片、MiniLM embedding、Redis 8 HNSW、两阶段 active 切换和质量评测。Task 7 由 Java 在创建 Profile 快照时读取 active version，并通过受限 HTTP Gateway 将冻结版本传给 Python。

**Tech Stack:** Java 21、Spring Boot 3.3.5、Flyway、JUnit 5、Testcontainers；Python 3.12、uv、Starlette、Pydantic 2、redis-py、sentence-transformers、pytest、ruff、pyright；Redis 8、MySQL 8。

## Global Constraints

- 先满足 `2026-07-19-reagent-java-python-hybrid-roadmap.md` 的 Task 4 Gate。
- Task 5–7 不引入 MCP、审批、工单、UI 或最终 Compose 编排。
- `POST /api/incidents` 只接受受信 source，单字段和总 payload 均有硬上限；告警文本只能进入 user goal，不能覆盖 system prompt、Profile 或工具策略。
- `incident_intake` 与 task 创建必须位于同一事务；唯一冲突时 loser 事务中的临时 task 必须回滚。
- RAG 语料固定为仓库内英文 Markdown；不支持上传、PDF、OCR、网页、reranker 或外部 embedding API。
- 单元测试只用 `FakeEmbeddingPort`；真实 MiniLM 只在 quality marker 中运行。
- chunk ID、checksum、manifest 和 index version 必须确定性生成；同一输入重复初始化不能新增 chunk。
- Python 只写 `rag:incident:*` 与 `idx:rag:incident:*`；Java 不直接执行 FT.CREATE/FT.SEARCH。
- Java 对 RAG 响应执行 contract version、index version、字段、hit 数量、score、excerpt 和总字节数校验。
- 每个 Task 完成时只提交本 Task 文件，不创建逐 Task 面试材料。

---

### Task 5: Incident intake, shared contracts, and Python service skeleton

**Files:**

- Modify: `pom.xml`
- Create: `contracts/incident-intake-v1.example.json`
- Create: `contracts/rag-search-v1.request.json`
- Create: `contracts/rag-search-v1.response.json`
- Create: `src/main/resources/db/migration/V3__incident_intake.sql`
- Create: `src/main/java/com/reagent/incident/IncidentRequest.java`
- Create: `src/main/java/com/reagent/incident/IncidentAccepted.java`
- Create: `src/main/java/com/reagent/incident/IncidentProperties.java`
- Create: `src/main/java/com/reagent/incident/IncidentIntakeEntity.java`
- Create: `src/main/java/com/reagent/incident/IncidentIntakeRepository.java`
- Create: `src/main/java/com/reagent/incident/IncidentGoalFactory.java`
- Create: `src/main/java/com/reagent/incident/IncidentCreationTransaction.java`
- Create: `src/main/java/com/reagent/incident/IncidentIntakeService.java`
- Create: `src/main/java/com/reagent/api/IncidentController.java`
- Modify: `src/main/java/com/reagent/api/ApiExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/incident/IncidentRequestTest.java`
- Create: `src/test/java/com/reagent/incident/IncidentGoalFactoryTest.java`
- Create: `src/test/java/com/reagent/incident/IncidentIntakeIT.java`
- Modify: `src/test/java/com/reagent/persist/SchemaMigrationIT.java`
- Create: `services/agent-capabilities/pyproject.toml`
- Create: `services/agent-capabilities/uv.lock`
- Create: `services/agent-capabilities/Dockerfile`
- Create: `services/agent-capabilities/.dockerignore`
- Create: `services/agent-capabilities/src/agent_capabilities/__init__.py`
- Create: `services/agent-capabilities/src/agent_capabilities/config.py`
- Create: `services/agent-capabilities/src/agent_capabilities/app.py`
- Create: `services/agent-capabilities/src/agent_capabilities/readiness.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/__init__.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/models.py`
- Create: `services/agent-capabilities/tests/test_config.py`
- Create: `services/agent-capabilities/tests/test_contract.py`
- Create: `services/agent-capabilities/tests/test_app.py`

**Interfaces:**

- Consumes: `AgentRunner.submit(String goal, String profileId)`, `StateStore.createTask(String, TaskProfileSnapshot)`, existing Java transaction manager.
- Produces: `IncidentIntakeService.accept(IncidentRequest)`, `POST /api/incidents`, Python `Settings`, `RagSearchRequest`, `RagSearchResponse`, and a healthy ASGI service with an explicit RAG-not-ready response.

- [ ] **Step 5.1: Add shared fixtures and write contract tests first**

Use the exact incident example from the approved spec. RAG fixtures contain `contractVersion=1`, `knowledgeBaseId=incident-ops`, a nonblank version, `topK=3`, and one bounded hit.

Java test:

```java
@Test
void sharedIncidentFixtureDeserializesWithBoundedFields() throws Exception {
    IncidentRequest request = mapper.readValue(
            Path.of("contracts/incident-intake-v1.example.json").toFile(),
            IncidentRequest.class);
    assertEquals("fake-alertmanager", request.source());
    assertEquals("ALERT-CHECKOUT-001", request.externalAlertId());
    assertEquals("checkout", request.service());
}
```

Python test:

```python
def test_shared_rag_contract_examples() -> None:
    root = Path(__file__).parents[3] / "contracts"
    request = RagSearchRequest.model_validate_json(
        (root / "rag-search-v1.request.json").read_text(encoding="utf-8")
    )
    response = RagSearchResponse.model_validate_json(
        (root / "rag-search-v1.response.json").read_text(encoding="utf-8")
    )
    assert request.contract_version == 1
    assert response.index_version == request.index_version
    assert len(response.hits) == 1
```

Run:

```bash
./mvnw -B -Dtest=IncidentRequestTest test
cd services/agent-capabilities
uv run pytest tests/test_contract.py -q
```

Expected RED: Java incident types and Python package do not exist.

- [ ] **Step 5.2: Create the locked Python package and exact API models**

`pyproject.toml` must contain these project/runtime constraints; `uv lock` supplies exact transitive versions:

```toml
[project]
name = "agent-capabilities"
version = "0.1.0"
requires-python = ">=3.12,<3.13"
dependencies = [
  "alembic>=1.16,<2",
  "mcp==1.28.1",
  "opentelemetry-api>=1.38,<2",
  "opentelemetry-sdk>=1.38,<2",
  "pydantic-settings>=2.10,<3",
  "pymysql>=1.1,<2",
  "redis[hiredis]>=6.4,<7",
  "sentence-transformers>=5,<6",
  "sqlalchemy>=2.0.41,<3",
  "uvicorn[standard]>=0.35,<1"
]

[dependency-groups]
dev = [
  "httpx>=0.28,<1",
  "pyright>=1.1.403,<2",
  "pytest>=8.4,<9",
  "pytest-asyncio>=1,<2",
  "pytest-cov>=6,<8",
  "ruff>=0.12,<1",
  "testcontainers[mysql,redis]>=4.10,<5"
]

[build-system]
requires = ["hatchling>=1.27,<2"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/agent_capabilities"]

[tool.pytest.ini_options]
addopts = "-ra --strict-markers"
markers = [
  "integration: requires Redis or MySQL containers",
  "quality: requires the real local MiniLM model"
]

[tool.ruff]
line-length = 100
target-version = "py312"

[tool.pyright]
pythonVersion = "3.12"
typeCheckingMode = "strict"
include = ["src", "tests"]
```

Create `RagSearchRequest`, `RagHit`, `RagSearchResponse`, and `ActiveIndexResponse` as Pydantic models with camelCase aliases, `extra="forbid"`, query length 1–512, `topK` 1–5, excerpt max 1,200, and score 0–1. Run `uv lock` and then:

```bash
uv sync --locked --all-groups
uv run pytest tests/test_contract.py -q
uv run ruff check .
uv run pyright
```

Expected GREEN.

- [ ] **Step 5.3: Build a startable ASGI skeleton with fail-closed readiness**

`Settings` uses `SettingsConfigDict(env_prefix="AGENT_CAPABILITIES_", extra="forbid")` and defines environment, Redis URL, MySQL URL, knowledge root, model ID, acceptance flag, chaos flag, and OTLP endpoint. Defaults target Compose service names only in container configuration; tests construct Settings explicitly.

Use this application factory shape:

```python
def create_app(settings: Settings | None = None) -> Starlette:
    resolved = settings or Settings()

    async def readiness(_: Request) -> JSONResponse:
        return JSONResponse(
            {
                "ready": False,
                "service": "agent-capabilities",
                "rag": {"ready": False, "reason": "index-not-initialized"},
                "fakeOps": {"ready": False, "reason": "not-configured"},
            },
            status_code=200,
        )

    async def rag_not_ready(_: Request) -> JSONResponse:
        return JSONResponse(
            {"code": "RAG_NOT_READY", "message": "active index is unavailable"},
            status_code=503,
        )

    return Starlette(routes=[
        Route("/internal/readiness", readiness, methods=["GET"]),
        Route("/internal/rag/search", rag_not_ready, methods=["POST"]),
    ])
```

`test_app.py` asserts both responses, rejects oversized bodies before parsing, and asserts no settings value is echoed. Run:

```bash
uv run pytest tests/test_app.py tests/test_config.py -q
```

Expected GREEN.

- [ ] **Step 5.4: Write V3 migration and concurrency tests before Java implementation**

Migration shape:

```sql
CREATE TABLE incident_intake (
    id VARCHAR(36) NOT NULL,
    source VARCHAR(64) NOT NULL,
    external_alert_id VARCHAR(128) NOT NULL,
    bounded_payload_json MEDIUMTEXT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_source_external UNIQUE (source, external_alert_id),
    CONSTRAINT uk_incident_task UNIQUE (task_id),
    CONSTRAINT fk_incident_task FOREIGN KEY (task_id) REFERENCES task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Extend `SchemaMigrationIT` to assert V3 on fresh and legacy paths. `IncidentIntakeIT` must cover:

```java
@Test
void concurrentDuplicateReturnsOneTask() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new CountDownLatch(1);
        List<Future<IncidentAccepted>> futures = IntStream.range(0, 8)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return service.accept(fixture());
                }))
                .toList();
        start.countDown();
        List<IncidentAccepted> results = futures.stream().map(Future::get).toList();
        assertEquals(1, results.stream().map(IncidentAccepted::taskId).distinct().count());
        assertEquals(1, jdbc.queryForObject("select count(*) from incident_intake", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from task", Integer.class));
    }
}
```

Also assert the unique-conflict loser leaves no orphan task, an unknown source returns 400, body > 32 KiB returns 413, labels > 20 or values > 256 chars return 400, and repeated request returns `deduplicated=true`.

Run:

```bash
./mvnw -B -Dit.test=SchemaMigrationIT,IncidentIntakeIT verify
```

Expected RED: V3 and incident service are absent.

- [ ] **Step 5.5: Implement atomic incident creation**

Add `spring-boot-starter-validation`. `IncidentCreationTransaction.create` is the only transactional creator:

```java
@Transactional
public IncidentAccepted create(ValidatedIncident incident) {
    TaskProfileSnapshot profile = profiles.snapshot("incident-ops");
    TaskEntity task = stateStore.createTask(goalFactory.create(incident), profile);
    IncidentIntakeEntity row = IncidentIntakeEntity.create(
            UUID.randomUUID().toString(), incident, task.getId(), clock.instant(), mapper);
    repository.saveAndFlush(row);
    return new IncidentAccepted(row.getId(), task.getId(), false);
}
```

`IncidentIntakeService.accept` must call that method through a separate Spring bean/proxy. Catch `DataIntegrityViolationException` outside the rolled-back transaction, then load `(source, externalAlertId)` in a new read transaction and return its IDs with `deduplicated=true`. Do not catch inside the creator transaction.

`IncidentGoalFactory` emits a bounded deterministic user goal containing source, external ID, service, severity, title, summary, startedAt and sorted labels; it never copies text into the system prompt. Configure only `fake-alertmanager` in `reagent.incidents.trusted-sources`.

Rerun:

```bash
./mvnw -B -Dtest=IncidentRequestTest,IncidentGoalFactoryTest test
./mvnw -B -Dit.test=SchemaMigrationIT,IncidentIntakeIT verify
```

Expected GREEN.

- [ ] **Step 5.6: Verify the Python image and commit Task 5**

The service Dockerfile uses Python 3.12 slim, installs uv from its official image, copies `pyproject.toml` and `uv.lock` before source, runs `uv sync --locked --no-dev`, uses a non-root user, and starts `uvicorn agent_capabilities.app:create_app --factory --host 0.0.0.0 --port 8090`.

Run:

```bash
./mvnw -B test
./mvnw -B -Pci verify
cd services/agent-capabilities
uv sync --locked --all-groups
uv run ruff check .
uv run pyright
uv run pytest -m "not integration and not quality" -q
docker build -t reagent-agent-capabilities:task5 .
git diff --check
```

Expected: all commands succeed; Python readiness is reachable and reports `ready=false` until Task 6.

Commit:

```bash
git add pom.xml contracts src/main/java/com/reagent/incident src/main/java/com/reagent/api \
  src/main/resources src/test/java/com/reagent/incident src/test/java/com/reagent/persist \
  services/agent-capabilities
git commit -m "feat: add incident intake and Python capability skeleton"
```

---

### Task 6: Deterministic MiniLM and Redis 8 RAG

**Files:**

- Create: `knowledge/incident-ops/manifest.txt`
- Create: `knowledge/incident-ops/runbooks/checkout-db-pool.md`
- Create: `knowledge/incident-ops/architecture/checkout-service.md`
- Create: `knowledge/incident-ops/incidents/2025-11-checkout-pool.md`
- Create: `knowledge/incident-ops/distractors/search-latency.md`
- Create: `knowledge/incident-ops/distractors/kafka-consumer-lag.md`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/domain.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/loader.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/chunker.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/embedding.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/index.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/initializer.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/service.py`
- Create: `services/agent-capabilities/src/agent_capabilities/rag/api.py`
- Modify: `services/agent-capabilities/src/agent_capabilities/app.py`
- Modify: `services/agent-capabilities/src/agent_capabilities/readiness.py`
- Create: `services/agent-capabilities/evaluation/queries.json`
- Create: `services/agent-capabilities/tests/fakes.py`
- Create: `services/agent-capabilities/tests/test_loader.py`
- Create: `services/agent-capabilities/tests/test_chunker.py`
- Create: `services/agent-capabilities/tests/test_search_service.py`
- Create: `services/agent-capabilities/tests/test_initializer.py`
- Create: `services/agent-capabilities/tests/test_redis_index_integration.py`
- Create: `services/agent-capabilities/tests/test_retrieval_quality.py`

**Interfaces:**

- Consumes: Task 5 Pydantic contracts, Redis URL, fixed Markdown corpus.
- Produces: `EmbeddingPort`, `RedisKnowledgeIndex`, `RagInitializer.initialize()`, `RagService.search(request)`, active-version endpoint, ready RAG API and quality report.

- [ ] **Step 6.1: Define immutable RAG domain and failing loader/chunker tests**

Use these exact domain shapes:

```python
@dataclass(frozen=True, slots=True)
class KnowledgeDocument:
    document_id: str
    title: str
    source: str
    markdown: str

@dataclass(frozen=True, slots=True)
class KnowledgeChunk:
    chunk_id: str
    document_id: str
    title: str
    section: str
    source: str
    content: str
    checksum: str

@dataclass(frozen=True, slots=True)
class EmbeddedChunk:
    chunk: KnowledgeChunk
    vector: tuple[float, ...]
    embedding_model: str
    index_version: str

class EmbeddingPort(Protocol):
    @property
    def model_id(self) -> str: ...
    @property
    def dimensions(self) -> int: ...
    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]: ...
```

The `...` tokens above are Python Protocol method bodies, not implementation placeholders.

Tests fix the algorithm:

- manifest order is the only load order;
- CRLF becomes LF, trailing whitespace is removed, repeated whitespace inside prose is collapsed;
- first H1 is title, H2 starts section, blank lines split paragraphs;
- target max is 1,200 Unicode code points and overlap max is 160;
- oversized paragraphs split at `.?!` sentence boundaries before hard split;
- `checksum = sha256(normalized content)`;
- `chunk_id = document_id + "#" + section_slug + "#" + checksum[:12]`;
- paths with `..`, backslash, absolute prefix, control chars or symlink escape are rejected;
- two loads return byte-identical chunks.

Run:

```bash
cd services/agent-capabilities
uv run pytest tests/test_loader.py tests/test_chunker.py -q
```

Expected RED: domain, loader and chunker are absent.

- [ ] **Step 6.2: Implement the fixed corpus and deterministic chunker**

The checkout Runbook must contain testable symptoms, threshold, evidence and mitigation: error rate >10% for five minutes, pool active 40/40, pending >20, connection acquisition timeout logs, stop nonessential batch traffic, and create P1 only when metrics and logs corroborate. The incident document contains the same root cause with different wording. Distractors cover search latency and Kafka consumer lag without checkout/pool mitigation facts.

Implement the parser with Python stdlib only. Reject duplicate chunk IDs. Clamp every stored content to the algorithmic bound and preserve exact `source` relative path.

Run the loader/chunker tests twice in the same process; expected GREEN and identical output.

- [ ] **Step 6.3: Write FakeEmbedding search tests and implement normalized cosine behavior**

`FakeEmbeddingPort` maps known tokens to fixed 4-dimensional vectors and L2-normalizes them. Tests assert topK ordering, min score, empty hits, bounded excerpt, and stable tie-break by chunk ID.

```python
def l2_normalize(vector: Sequence[float]) -> tuple[float, ...]:
    norm = math.sqrt(sum(value * value for value in vector))
    if not math.isfinite(norm) or norm == 0.0:
        raise ValueError("embedding vector must have a finite non-zero norm")
    return tuple(value / norm for value in vector)
```

Run:

```bash
uv run pytest tests/test_search_service.py -q
```

Expected RED, then GREEN after `RagService` and the in-memory test index are implemented.

- [ ] **Step 6.4: Implement MiniLM adapter and deterministic manifest version**

`MiniLmEmbedding` loads `sentence-transformers/all-MiniLM-L6-v2` on CPU, asserts dimension 384, uses `normalize_embeddings=True`, converts NaN/Inf to a hard failure, and exposes a model artifact checksum computed from sorted model files. `IndexManifest` canonical JSON contains dataset checksums, `chunkerVersion="markdown-v1"`, model ID, model file checksum, dimension 384, and distance `COSINE`.

Version generation:

```python
def index_version(manifest: IndexManifest) -> str:
    encoded = json.dumps(
        asdict(manifest), sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    return f"v1-{hashlib.sha256(encoded).hexdigest()[:16]}"
```

`test_initializer.py` asserts any corpus/chunker/model checksum change changes the version.

- [ ] **Step 6.5: Write real Redis HNSW integration tests before the index adapter**

The integration test starts `redis:8` and asserts:

- index name `idx:rag:incident:{version}`;
- key prefix `rag:incident:chunk:{version}:`;
- HNSW FLOAT32, DIM 384, DISTANCE_METRIC COSINE;
- metadata fields `chunk_id`, `document_id`, `title`, `section`, `source`, `content`, `checksum`, `embedding_model`, `index_version`;
- KNN results sorted by distance then chunk ID;
- active key `rag:incident:active` changes only after count validation and smoke query;
- a forced failure before switch leaves the former active version unchanged;
- a second initialization of the same version performs no writes.

Run:

```bash
uv run pytest tests/test_redis_index_integration.py -m integration -q
```

Expected RED: Redis adapter is absent.

- [ ] **Step 6.6: Implement two-phase Redis initialization**

Acquire `rag:incident:init:{version}` with `SET NX PX 120000` and a random owner token; release with compare-and-delete Lua. Build versioned chunk hashes and index, validate exact chunk count and a fixed smoke query, write a ready manifest, then atomically set `rag:incident:active` to the new version. On failure, delete only keys/index for the failed version when the owner token still matches; never delete the prior active version.

The search query must pass a little-endian FLOAT32 byte vector and use `DIALECT 2`. Convert Redis cosine distance with `score = max(0.0, min(1.0, 1.0 - distance))`.

Rerun integration test; expected GREEN.

- [ ] **Step 6.7: Replace RAG-not-ready with the real API and readiness**

At ASGI lifespan startup, initialize RAG in a worker thread, then expose:

```python
async def search(request: Request) -> JSONResponse:
    body = await bounded_json(request, max_bytes=16_384)
    query = RagSearchRequest.model_validate(body)
    result = await anyio.to_thread.run_sync(service.search, query)
    return JSONResponse(result.model_dump(by_alias=True))
```

`GET /internal/rag/indexes/{knowledgeBaseId}/active` returns 404 for unknown KB and the exact active version for `incident-ops`. Search rejects a requested version that is absent instead of substituting active. Empty retrieval returns `hits=[]` with HTTP 200.

Run all non-quality tests; expected GREEN.

- [ ] **Step 6.8: Add the real MiniLM quality gate**

`evaluation/queries.json` contains at least five queries with expected document IDs, including checkout pool exhaustion, connection acquisition timeouts, mitigation, and two distractor queries. The test writes `build/reports/rag-quality.json` with per-query rank, reciprocal rank, top-three chunk/source and aggregate MRR.

Assertions:

```python
assert all(case.expected_document_id in case.top_three_document_ids for case in report.cases)
assert report.mrr >= 0.80
assert all(hit.source_path.exists() for case in report.cases for hit in case.hits)
```

Run:

```bash
uv run pytest tests/test_retrieval_quality.py -m quality -q
test -s build/reports/rag-quality.json
```

Expected GREEN. The first run may download the pinned Hugging Face model; CI must cache it and later runs use local cache.

- [ ] **Step 6.9: Verify and commit Task 6**

```bash
uv run ruff check .
uv run pyright
uv run pytest -m "not integration and not quality" -q
uv run pytest -m integration -q
uv run pytest -m quality -q
git diff --check
```

Commit:

```bash
git add knowledge services/agent-capabilities
git commit -m "feat: add versioned Python RAG and quality gate"
```

---

### Task 7: Java RAG gateway, frozen version, tool, profile, and events

**Files:**

- Create: `src/main/java/com/reagent/rag/RagProperties.java`
- Create: `src/main/java/com/reagent/rag/RagSearchRequest.java`
- Create: `src/main/java/com/reagent/rag/RagHit.java`
- Create: `src/main/java/com/reagent/rag/RagSearchResponse.java`
- Create: `src/main/java/com/reagent/rag/ActiveIndexResponse.java`
- Create: `src/main/java/com/reagent/rag/RagGateway.java`
- Create: `src/main/java/com/reagent/rag/HttpRagGateway.java`
- Create: `src/main/java/com/reagent/rag/RagContractException.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeVersionProvider.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeSearchTool.java`
- Modify: `src/main/java/com/reagent/profile/AgentProfileRegistry.java`
- Modify: `src/main/java/com/reagent/profile/ToolCatalogResolver.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/rag/RagContractTest.java`
- Create: `src/test/java/com/reagent/rag/HttpRagGatewayTest.java`
- Create: `src/test/java/com/reagent/rag/KnowledgeSearchToolTest.java`
- Create: `src/test/java/com/reagent/rag/RagGatewayIT.java`
- Modify: `src/test/java/com/reagent/profile/AgentProfileRegistryTest.java`
- Modify: `src/test/java/com/reagent/profile/TaskProfilePersistenceIT.java`

**Interfaces:**

- Consumes: Python active-version/search endpoints, `TaskProfileSnapshot`, `Tool`, token-fenced event bus.
- Produces: `KnowledgeVersionProvider.requireActiveVersion(String)`, `RagGateway.search(RagSearchRequest)`, frozen incident profile, `search_knowledge` tool and `KNOWLEDGE_RETRIEVED` event.

- [ ] **Step 7.1: Write DTO boundary tests first**

Java records enforce contract version 1, KB/version/query bounds, topK 1–5, hit count ≤ topK, score 0–1, excerpt ≤ 1,200 chars, source under `knowledge/incident-ops/`, and unique chunk IDs. Deserialize the shared fixtures and reject unknown fields with a dedicated bounded ObjectMapper reader for this contract.

Run:

```bash
./mvnw -B -Dtest=RagContractTest test
```

Expected RED: Java RAG records are absent.

- [ ] **Step 7.2: Specify active-version freezing before implementation**

`AgentProfileRegistryTest` configures `knowledge-index-version: active`, stubs `KnowledgeVersionProvider` to return `v1-fixed`, snapshots `incident-ops`, and asserts the persisted snapshot contains `v1-fixed`. A later provider value `v1-new` must not alter the saved task profile loaded during recovery.

Define:

```java
public interface KnowledgeVersionProvider {
    String requireActiveVersion(String knowledgeBaseId);
}

public interface RagGateway extends KnowledgeVersionProvider {
    RagSearchResponse search(RagSearchRequest request);
}
```

Modify registry snapshot construction only when a profile has nonblank KB and configured version `active`; coding profile remains unchanged and never calls the provider.

- [ ] **Step 7.3: Implement bounded HTTP with one read-only retry**

`RagProperties` contains trusted base URL, connect timeout 500 ms, request timeout 2 s, maximum response 64 KiB, and one retry with 100 ms backoff. Build one configured Java `HttpClient` or Spring `RestClient`; task input cannot override base URL.

Retry only I/O timeout/connect failures once. Do not retry 4xx, contract errors, or index mismatch. Propagate W3C headers from current OTel Context. Convert non-2xx/oversized/malformed bodies to `RagContractException` with code and bounded message.

`HttpRagGatewayTest` uses a local stub HTTP server and covers success, empty hits, retry-once, no retry on 400, oversized body, unknown field, mismatched version and invalid source.

Run:

```bash
./mvnw -B -Dtest=HttpRagGatewayTest test
```

Expected RED, then GREEN.

- [ ] **Step 7.4: Implement `search_knowledge` against the task snapshot**

Tool schema:

```java
Map.of(
    "type", "object",
    "additionalProperties", false,
    "properties", Map.of(
        "query", Map.of("type", "string", "minLength", 1, "maxLength", 512),
        "topK", Map.of("type", "integer", "minimum", 1, "maximum", 5)
    ),
    "required", List.of("query")
)
```

The tool requires `ToolContext.runToken`, loads the persisted profile through `StateStore.loadProfile(token)`, and constructs a request with its frozen `knowledgeBaseId` and `knowledgeIndexVersion`. It ignores no model-supplied KB/version because those fields are absent from Schema. It serializes only the bounded response JSON, declares `READ_ONLY` and `ApprovalPolicy.NONE`, and publishes `KNOWLEDGE_RETRIEVED` with task ID, version, hit count and chunk IDs but no full content.

Tests assert malformed args, missing run token, Python empty hits, timeout error, response version mismatch and successful citations. Run:

```bash
./mvnw -B -Dtest=KnowledgeSearchToolTest test
```

Expected GREEN.

- [ ] **Step 7.5: Register the incident profile and prove recovery keeps the frozen index**

Add `incident-ops` configuration with English incident system prompt, `knowledge-base-id: incident-ops`, `knowledge-index-version: active`, tool name `search_knowledge`, and empty MCP servers until Task 9. Extend persistence IT:

1. create a task while provider returns `v1-a`;
2. change provider to `v1-b`;
3. load profile through a claimed run token;
4. assert `v1-a` remains and the gateway request uses `v1-a`;
5. assert coding profile behavior is unchanged.

- [ ] **Step 7.6: Run a real Python/Redis gateway integration**

`RagGatewayIT` starts Redis 8 and the Task 5 Python image with the Task 6 corpus mounted, waits for RAG readiness, reads active version, searches checkout pool exhaustion, and asserts every returned source exists in the mounted corpus. Restart the Python container against the same Redis volume and assert the same active version and chunk IDs.

Run:

```bash
./mvnw -B -Dit.test=RagGatewayIT,TaskProfilePersistenceIT verify
```

Expected GREEN with no API key.

- [ ] **Step 7.7: Verify and commit Task 7**

```bash
./mvnw -B test
./mvnw -B -Pci verify
cd services/agent-capabilities
uv run pytest -m "not quality" -q
git diff --check
```

Commit:

```bash
git add src/main/java/com/reagent/rag src/main/java/com/reagent/profile \
  src/main/java/com/reagent/stream src/main/java/com/reagent/obs src/main/resources/application.yml \
  src/test/java/com/reagent/rag src/test/java/com/reagent/profile
git commit -m "feat: add frozen Java RAG gateway and tool"
```

## Plan Completion Gate

- [ ] Duplicate incidents return one incident and one task under real MySQL concurrency.
- [ ] Python service is locked, linted, typed and starts as a non-root container.
- [ ] Chunk IDs/checksums and index version are stable across two initializations.
- [ ] Failed index construction never replaces the former active version.
- [ ] Real MiniLM retrieves every required document in top 3 and MRR is at least 0.80.
- [ ] Java freezes active index version into the task profile and uses it on recovery.
- [ ] RAG response validation rejects version mismatch, invalid source, oversized body and malformed JSON.
- [ ] Coding profile and existing Runtime tests remain green.
- [ ] Proceed only then to `2026-07-19-reagent-mcp-incident.md`.
