# ReAgent RAG Implementation Plan

> **已替代：** 本 Java-only RAG 计划不再执行。Task 5–7 以 `2026-07-19-reagent-python-rag.md` 为准。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, locally embedded operations knowledge base whose Redis 8 vector search returns bounded, stable, source-verifiable citations through the existing ReAgent tool path.

**Architecture:** Define narrow chunking, embedding, and index ports; prove content/citation behavior with deterministic vectors; implement Redis 8 HNSW using raw Spring Data Redis commands; then bind the packaged quantized MiniLM model behind `EmbeddingPort`, register `search_knowledge` in the incident profile, and enforce an offline top-3/MRR quality gate.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Jackson 2, Spring Data Redis, Redis 8 Query Engine/HNSW, Testcontainers, LangChain4j ONNX `AllMiniLmL6V2QuantizedEmbeddingModel`, JUnit 5, OpenTelemetry.

## Global Constraints

- Start only after the runtime plan completion gate passes.
- RAG is a ReAgent `Tool`; it does not call the LLM or create a parallel chat/history store.
- Production search uses Redis 8. Unit tests use `FakeEmbeddingPort`; model quality uses the real packaged ONNX model without any API key or network call at test runtime.
- Keep the approved English corpus/query choice. Final LLM output may be Chinese, but retrieval quality assertions are English.
- Stable IDs and citations are derived from content/path, never database auto IDs.
- Search returns at most configured `topK`, clamps excerpt length, and never fabricates a source on empty results.
- Index initialization is idempotent and readiness remains false until the active version is fully queryable.
- Do not add MCP, approval, Fake tickets, UI, or compose changes in this plan.
- Commit after each task with the exact message shown.

---

## Task 1: Define stable chunks, citations, and deterministic retrieval behavior

**Files:**

- Create: `src/main/java/com/reagent/rag/KnowledgeDocument.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeChunk.java`
- Create: `src/main/java/com/reagent/rag/EmbeddedChunk.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeHit.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeSearchResult.java`
- Create: `src/main/java/com/reagent/rag/EmbeddingPort.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeIndex.java`
- Create: `src/main/java/com/reagent/rag/DocumentChunker.java`
- Create: `src/main/java/com/reagent/rag/ClasspathKnowledgeLoader.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeSearchService.java`
- Create: `src/main/java/com/reagent/rag/RagProperties.java`
- Create: `src/main/resources/knowledge/incident-ops/manifest.txt`
- Create: `src/main/resources/knowledge/incident-ops/runbooks/checkout-db-pool.md`
- Create: `src/main/resources/knowledge/incident-ops/architecture/checkout-service.md`
- Create: `src/main/resources/knowledge/incident-ops/incidents/2025-11-checkout-pool.md`
- Create: `src/main/resources/knowledge/incident-ops/distractors/search-latency.md`
- Create: `src/main/resources/knowledge/incident-ops/distractors/kafka-consumer-lag.md`
- Create: `src/test/java/com/reagent/rag/FakeEmbeddingPort.java`
- Create: `src/test/java/com/reagent/rag/InMemoryKnowledgeIndex.java`
- Create: `src/test/java/com/reagent/rag/DocumentChunkerTest.java`
- Create: `src/test/java/com/reagent/rag/ClasspathKnowledgeLoaderTest.java`
- Create: `src/test/java/com/reagent/rag/KnowledgeSearchServiceTest.java`

**Consumes:** Runtime profile/tool contracts, Jackson, classpath resources.

**Produces:** Stable domain records, deterministic Markdown chunker, bounded citation result, synthetic incident corpus, Fake embedding/index test doubles.

### Step 1.1: Write immutable domain-contract tests

- [ ] Define tests that reject blank document/chunk/source IDs, non-384-dimensional embedded chunks when the configured dimension is 384, score outside `[0,1]`, and mutable collection leakage.
- [ ] Define exact public records/interfaces from the roadmap, adding these required fields:

```java
public record KnowledgeDocument(String documentId, String title, String source, String markdown) {}

public record KnowledgeChunk(
        String chunkId,
        String documentId,
        String title,
        String section,
        String source,
        String content,
        String checksum,
        String embeddingModel,
        String indexVersion
) {}

public record KnowledgeHit(
        String chunkId,
        String title,
        String section,
        String source,
        double score,
        String excerpt
) {}
```

- [ ] Run:

```bash
./mvnw -B -Dtest=DocumentChunkerTest,KnowledgeSearchServiceTest test
```

Expected red: RAG types do not exist.

### Step 1.2: Specify the chunking algorithm in tests

- [ ] Test this exact normalization/chunking policy:
  - UTF-8 text, CRLF normalized to LF, trailing whitespace removed;
  - first `# ` heading is the title; each `## ` heading starts a section;
  - paragraphs are separated by one or more blank lines;
  - chunks target at most `1,200` Unicode code points;
  - an oversized paragraph is split at sentence punctuation, then hard-split only if needed;
  - a new chunk repeats at most the previous final `160` code points as overlap;
  - ID is `{documentId}#{section-slug}#{two-digit-ordinal}`;
  - checksum is lowercase SHA-256 of normalized `title + "\n" + section + "\n" + content`.
- [ ] Assert two loads produce byte-identical IDs/checksums and every source remains under `knowledge/incident-ops/`.
- [ ] Assert headings or path text containing `../`, backslashes, control characters, or an empty slug are rejected.
- [ ] Run `DocumentChunkerTest`; expected red.
- [ ] Implement the smallest parser with JDK string/code-point APIs; do not add a Markdown framework.
- [ ] Rerun the test; expected green.

### Step 1.3: Add the exact synthetic knowledge facts

- [ ] List the five relative paths, one per line, in `manifest.txt`. Loader order is manifest order; classpath directory enumeration is forbidden.
- [ ] Derive `documentId` from the manifest relative path by stripping `.md`, lowercasing, and replacing each `/` with `-`; reject any character outside lowercase letters, digits, and hyphens after normalization.
- [ ] Author `checkout-db-pool.md` with these testable facts:
  - symptoms: checkout HTTP 5xx, p95 latency, `db.pool.pending`, and `db.pool.active` at max;
  - confirmation threshold: checkout error rate above 10% for 5 minutes plus pool pending above 20;
  - mitigation: stop nonessential batch traffic, temporarily raise pool only within DB connection budget, then recycle leaked connections;
  - escalation: create P1 only when metrics and logs corroborate pool exhaustion.
- [ ] Author `checkout-service.md` with checkout → payment → MySQL call flow, pool maximum 40 per replica, 4 replicas, and database budget 220.
- [ ] Author `2025-11-checkout-pool.md` with `HikariPool-1 - Connection is not available`, a 14% error rate, root cause as a missing timeout/connection leak, and the same mitigation sequence.
- [ ] Author the two distractors with plausible but unrelated search cache latency and Kafka consumer lag facts; do not mention checkout pool exhaustion in them.
- [ ] In `ClasspathKnowledgeLoaderTest`, assert all five documents, unique IDs, titles, safe sources, and the exact facts above are loaded.
- [ ] Run the loader test; expected green.

### Step 1.4: Define deterministic vector ranking

- [ ] Implement `FakeEmbeddingPort` with an explicit text-to-vector map supplied by each test. Unknown text throws; it must not hash text into pseudo-similarity.
- [ ] Implement test-only `InMemoryKnowledgeIndex` using normalized cosine similarity and deterministic tie-break by `chunkId`.
- [ ] Test `KnowledgeSearchService`:
  - pool-exhaustion query ranks Runbook Mitigation first and the historical incident second;
  - `topK` is clamped to `1..10`;
  - `minScore` removes weak hits;
  - excerpts are at most 600 code points and end with an ellipsis only when truncated;
  - empty results serialize as `hits: []` and preserve query/index metadata;
  - every returned hit maps to a loaded source/chunk.
- [ ] Add `RagProperties` defaults: knowledge base `incident`, topK `3`, min score `0.55`, excerpt limit `600`, dimension `384`, model ID `all-MiniLM-L6-v2-q`.
- [ ] Run:

```bash
./mvnw -B -Dtest='com.reagent.rag.*Test' test
git diff --check
```

Expected: all new unit tests green.

- [ ] Commit:

```bash
git add src/main/java/com/reagent/rag src/main/resources/knowledge src/test/java/com/reagent/rag
git commit -m "feat: add stable RAG chunks and citations"
```

---

## Task 2: Implement Redis 8 HNSW indexing and idempotent initialization

**Files:**

- Create: `src/main/java/com/reagent/rag/RedisVectorCodec.java`
- Create: `src/main/java/com/reagent/rag/RedisKnowledgeIndex.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeDataset.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeIndexInitializer.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeReadiness.java`
- Create: `src/main/java/com/reagent/rag/RagConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/reagent/rag/RedisVectorCodecTest.java`
- Create: `src/test/java/com/reagent/rag/RedisKnowledgeIndexIT.java`
- Create: `src/test/java/com/reagent/rag/KnowledgeIndexInitializerIT.java`
- Modify: `src/test/java/com/reagent/stream/RedisStreamTransportIT.java`

**Consumes:** `KnowledgeIndex`, Redis 8 container/support, stable chunks and FakeEmbedding.

**Produces:** little-endian FLOAT32 codec, Redis HNSW adapter, versioned/idempotent initializer, readiness state, Redis Search + Streams compatibility gate.

### Step 2.1: Prove vector byte format

- [ ] In `RedisVectorCodecTest`, assert `[1.0f, -2.5f, 0.0f]` produces 12 bytes in little-endian IEEE-754 and round-trips exactly.
- [ ] Assert NaN, infinity, and a dimension mismatch are rejected before Redis I/O.
- [ ] Run:

```bash
./mvnw -B -Dtest=RedisVectorCodecTest test
```

Expected red: codec missing.

- [ ] Implement with `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`; expected green.

### Step 2.2: Write HNSW creation/search integration tests first

- [ ] In `RedisKnowledgeIndexIT`, flush only the test container database before each case.
- [ ] Test `createsHnswIndexAndReturnsMetadata`: insert three 384-dimensional deterministic vectors and assert topK order, scores, title/section/source/excerpt.
- [ ] Test `returnsEmptyHitsWithoutFabricatingMetadata`.
- [ ] Test `separatesKnowledgeBaseAndIndexVersion`.
- [ ] Test `rejectsWrongDimensionAndOversizedMetadataBeforeRedis`.
- [ ] Run:

```bash
./mvnw -B -Dit.test=RedisKnowledgeIndexIT verify
```

Expected red: Redis adapter missing.

### Step 2.3: Implement raw Redis Search commands

- [ ] Use `StringRedisTemplate.execute(RedisCallback<?>)` so the project does not adopt a second vector framework.
- [ ] Create the index with this semantic command (encode arguments as bytes through the Redis connection):

```text
FT.CREATE idx:rag:incident:{indexVersion}
ON HASH PREFIX 1 rag:incident:chunk:
SCHEMA document_id TAG title TEXT section TEXT source TAG content TEXT
       checksum TAG embedding_model TAG index_version TAG
       embedding VECTOR HNSW 6 TYPE FLOAT32 DIM 384 DISTANCE_METRIC COSINE
```

- [ ] Search with this semantic command and `DIALECT 2`:

```text
FT.SEARCH idx:rag:incident:{indexVersion}
  "(@index_version:{$VERSION})=>[KNN $K @embedding $VECTOR AS vector_distance]"
  PARAMS 6 VERSION {escapedVersion} K {topK} VECTOR {binaryFloat32}
  SORTBY vector_distance ASC
  RETURN 7 chunk_id title section source content checksum vector_distance
  DIALECT 2
```

- [ ] Convert cosine distance to score with `1.0 - distance`, clamp numerical noise to `[0,1]`, apply minScore, and tie-break identical scores by chunk ID in Java.
- [ ] Store each chunk in `rag:incident:chunk:{chunkId}` with all approved metadata and binary embedding. Enforce configured byte limits before `HSET`.
- [ ] Treat `Index already exists` as success only after `FT.INFO` confirms dimension, algorithm, metric, and prefix; otherwise fail with `RagIndexSchemaException`.
- [ ] Rerun `RedisKnowledgeIndexIT`; expected green.

### Step 2.4: Write idempotent initializer tests

- [ ] `KnowledgeDataset` computes `indexVersion = sha256(modelId + sorted chunk checksums)` and exposes immutable chunks.
- [ ] In `KnowledgeIndexInitializerIT`, test:
  - first startup acquires `rag:incident:init:{version}`, embeds and builds once;
  - second startup with the same checksum performs zero embeddings/writes;
  - two initializers released together result in one builder and one waiter;
  - changed corpus/model builds a new index before switching `rag:incident:active-index`;
  - a build failure leaves the prior active version unchanged and readiness false for the requested version;
  - a stale initialization lock expires by configured TTL.
- [ ] Run the IT; expected red.

### Step 2.5: Implement two-phase initialization and readiness

- [ ] Acquire the version lock with Redis `SET key value NX PX {lockTtl}` and release only if the stored random owner value still matches.
- [ ] Store dataset metadata under `rag:incident:dataset:{version}` with corpus checksum, model, dimension, completed timestamp, and chunk count.
- [ ] Build all hashes/index, run one known probe query, then atomically set `rag:incident:active-index` to the new version.
- [ ] If metadata already matches and the probe succeeds, skip embedding/index writes.
- [ ] `KnowledgeReadiness` exposes `ready`, active version, chunk count, and last error without document content.
- [ ] Add configuration defaults for init lock TTL, query timeout, metadata/result byte limits, and enabled flag.
- [ ] Rerun initializer tests; expected green.

### Step 2.6: Re-prove Redis Streams on the same Redis 8 image

- [ ] Extend `RedisStreamTransportIT` to assert `FT.CREATE`/vector hashes coexist with stream publish/replay/TTL keys and do not change serialization or cursor behavior.
- [ ] Run:

```bash
./mvnw -B -Dit.test=RedisKnowledgeIndexIT,KnowledgeIndexInitializerIT,RedisStreamTransportIT verify
./mvnw -B test
git diff --check
```

Expected: HNSW, initializer, and existing Streams all green.

- [ ] Commit:

```bash
git add src/main/java/com/reagent/rag src/main/resources/application.yml src/test/java/com/reagent/rag src/test/java/com/reagent/stream
git commit -m "feat: add Redis 8 vector knowledge index"
```

---

## Task 3: Bind quantized MiniLM and expose the RAG tool/profile

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/reagent/rag/MiniLmEmbeddingAdapter.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeSearchTool.java`
- Create: `src/main/java/com/reagent/rag/KnowledgeSourceService.java`
- Modify: `src/main/java/com/reagent/rag/RagConfiguration.java`
- Modify: `src/main/java/com/reagent/profile/AgentProfileProperties.java`
- Modify: `src/main/java/com/reagent/stream/TaskEvent.java`
- Modify: `src/main/java/com/reagent/obs/Trace.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/resources/rag/evaluation.json`
- Create: `src/test/java/com/reagent/rag/MiniLmEmbeddingAdapterTest.java`
- Create: `src/test/java/com/reagent/rag/MiniLmRetrievalQualityIT.java`
- Create: `src/test/java/com/reagent/rag/KnowledgeSearchToolTest.java`
- Modify: `src/test/java/com/reagent/profile/AgentProfileRegistryTest.java`

**Consumes:** RAG ports/index, runtime Tool/Profile/ToolContext token contracts.

**Produces:** resolved quantized ONNX dependency, bounded adapter, offline quality report, `search_knowledge` read-only tool, incident profile RAG slice, RAG event/span attributes.

### Step 3.1: Resolve and compile the quantized model dependency before adapter work

- [ ] Add this intended dependency coordinate:

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-embeddings-all-minilm-l6-v2-q</artifactId>
    <version>1.18.0-beta28</version>
</dependency>
```

- [ ] Add a compile-only skeleton test importing:

```java
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
```

- [ ] Run:

```bash
./mvnw -B -DskipTests compile
```

Expected: the official quantized artifact resolves and the class compiles. A failure is a hard dependency gate; do not proceed to adapter code until repository/network resolution is healthy.

### Step 3.2: Write adapter bounds tests

- [ ] `MiniLmEmbeddingAdapterTest` uses a mocked/delegated model facade to assert:
  - `dimensions()` is exactly 384;
  - blank/oversized text is rejected;
  - returned NaN/infinite/wrong-sized vectors are rejected;
  - `embedAll` preserves input order;
  - no more than configured concurrency enters the delegate.
- [ ] Run the test; expected red.
- [ ] Create a ReAgent-owned fixed executor with the configured thread count and instantiate `new AllMiniLmL6V2QuantizedEmbeddingModel(executor)` inside `RagConfiguration`.
- [ ] Close the ReAgent-owned executor on application shutdown; do not use the model's default processor-sized cached pool.
- [ ] Convert LangChain4j `Embedding` values to defensive `float[]`; no LangChain4j Agent/RAG abstractions may appear outside this adapter/configuration.
- [ ] Rerun adapter tests; expected green.

### Step 3.3: Write the real offline quality evaluation first

- [ ] Put this fixed query/expected-document matrix in `evaluation.json`:
  - `checkout connection pool exhaustion mitigation` → checkout DB pool Runbook;
  - `Hikari connection is not available checkout errors` → 2025-11 historical incident;
  - `checkout database connection budget per replica` → checkout architecture;
  - `when should a P1 checkout incident ticket be created` → Runbook escalation.
- [ ] In `MiniLmRetrievalQualityIT`, load/chunk the real corpus, embed with the real packaged model, rank by cosine in-memory, and report actual ranks.
- [ ] Assert each expected document is top 3, every hit maps to a real chunk/source, and mean reciprocal rank is at least `0.80`.
- [ ] Run:

```bash
./mvnw -B -Dit.test=MiniLmRetrievalQualityIT verify
```

Expected red until model wiring/corpus wording yields the approved threshold. Adjust only synthetic corpus wording that remains factually consistent; do not weaken top-3 or MRR thresholds.

### Step 3.4: Implement the bounded tool result and citation event

- [ ] In `KnowledgeSearchToolTest`, assert name `search_knowledge`, schema fields `query` and optional `topK`, `READ_ONLY`, `ApprovalPolicy.NONE`, bounded JSON, empty hit behavior, and no extra source not returned by the service.
- [ ] Reject malformed JSON, blank query, topK outside limits, and reserved/internal fields with a structured error observation.
- [ ] Implement `KnowledgeSearchTool` using Jackson; the model-visible result shape is exactly:

```json
{
  "query": "checkout connection pool exhaustion",
  "indexVersion": "<stable-version>",
  "hits": [
    {
      "chunkId": "runbooks-checkout-db-pool#mitigation#01",
      "title": "Checkout DB Pool Runbook",
      "section": "Mitigation",
      "source": "knowledge/incident-ops/runbooks/checkout-db-pool.md",
      "score": 0.89,
      "excerpt": "..."
    }
  ]
}
```

- [ ] Add `RAG_RETRIEVED` to TaskEvent. When `ToolContext.runToken()` is present, publish a fenced event containing query preview, topK, hit count, chunk IDs, and index version; never publish full content.
- [ ] Add `rag.embed` and `rag.search` spans with model/index/topK/hit-count/chunk IDs and no full text.
- [ ] Rerun tool/unit and event replay tests; expected green.

### Step 3.5: Register the incident profile's RAG slice

- [ ] Add `incident-ops` profile version `1` with its approved operations system prompt, knowledge base/index fields, and `search_knowledge` in the tool allowlist. MCP tool names may be listed only after the next plan registers/discovers them; until then an incident task fails profile readiness clearly instead of running half-enabled.
- [ ] Extend `AgentProfileRegistryTest` to assert coding remains unchanged and incident snapshot includes the RAG tool metadata/hash.
- [ ] Add `KnowledgeSourceService.getSafeSummary(chunkId)` for the later REST/UI plan; it returns metadata plus bounded excerpt only for manifest-backed chunk IDs.
- [ ] Run:

```bash
./mvnw -B -Dtest=KnowledgeSearchToolTest,AgentProfileRegistryTest test
./mvnw -B -Dit.test=MiniLmRetrievalQualityIT,RedisKnowledgeIndexIT,RedisStreamTransportIT verify
git diff --check
```

Expected: all green, quality report meets threshold.

- [ ] Commit:

```bash
git add pom.xml src/main/java src/main/resources src/test/java src/test/resources
git commit -m "feat: expose cited MiniLM knowledge search"
```

## Plan 2 Completion Gate

- [ ] Stable chunks/checksums repeat across runs.
- [ ] Search output is bounded, empty-safe, and only cites manifest-backed sources.
- [ ] Redis 8 HNSW tests pass with metadata and expected ranking.
- [ ] Initializer is lock-safe, versioned, idempotent, and readiness-gated.
- [ ] Existing Redis Streams tests still pass on Redis 8.
- [ ] Quantized model compiles/resolves from the official module and the offline evaluation has top-3 success for every query plus MRR ≥ 0.80.
- [ ] `search_knowledge` is `READ_ONLY + NONE` and appears in the frozen incident profile catalog.
- [ ] Proceed only then to `2026-07-18-reagent-mcp-approval.md`.
