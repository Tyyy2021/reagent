import threading
from collections.abc import Sequence
from pathlib import Path

from starlette.testclient import TestClient

from agent_capabilities.app import create_app
from agent_capabilities.config import Settings
from agent_capabilities.rag.api import RagRuntime
from agent_capabilities.rag.domain import KnowledgeChunk
from agent_capabilities.rag.models import RagSearchRequest
from agent_capabilities.rag.service import IndexVersionNotFoundError, RagService
from fakes import FakeEmbeddingPort, InMemoryKnowledgeIndex


def test_search_returns_normalized_top_k_with_stable_chunk_id_ties() -> None:
    embedding = FakeEmbeddingPort()
    index = InMemoryKnowledgeIndex(
        version="v1-search",
        embedding=embedding,
        chunks=[
            _chunk("chunk-b", "checkout pool"),
            _chunk("chunk-a", "checkout pool"),
            _chunk("chunk-c", "checkout"),
            _chunk("chunk-d", "kafka lag"),
        ],
    )
    service = RagService(embedding=embedding, index=index, min_score=0.25)

    response = service.search(_request(query="checkout pool", top_k=3))

    assert [hit.chunk_id for hit in response.hits] == ["chunk-a", "chunk-b", "chunk-c"]
    assert response.hits[0].score == 1.0
    assert response.hits[1].score == 1.0
    assert 0.0 <= response.hits[2].score < 1.0


def test_search_applies_min_score_and_returns_empty_hits() -> None:
    embedding = FakeEmbeddingPort()
    index = InMemoryKnowledgeIndex(
        version="v1-search",
        embedding=embedding,
        chunks=[_chunk("kafka", "kafka lag"), _chunk("search", "search latency")],
    )
    service = RagService(embedding=embedding, index=index, min_score=0.50)

    response = service.search(_request(query="checkout pool", top_k=5))

    assert response.index_version == "v1-search"
    assert response.hits == []


def test_search_bounds_excerpt_to_contract_limit() -> None:
    embedding = FakeEmbeddingPort()
    index = InMemoryKnowledgeIndex(
        version="v1-search",
        embedding=embedding,
        chunks=[_chunk("long", "checkout " * 300)],
    )
    service = RagService(embedding=embedding, index=index)

    response = service.search(_request(query="checkout", top_k=1))

    assert len(response.hits) == 1
    assert len(response.hits[0].excerpt) == 1_200


def test_search_rejects_an_absent_requested_version() -> None:
    embedding = FakeEmbeddingPort()
    index = InMemoryKnowledgeIndex(version="v1-other", embedding=embedding, chunks=[])
    service = RagService(embedding=embedding, index=index)

    try:
        service.search(_request(query="checkout", top_k=1))
    except IndexVersionNotFoundError as error:
        assert error.index_version == "v1-search"
    else:
        raise AssertionError("absent requested version must be rejected")


def test_app_initializes_rag_in_worker_and_exposes_search_active_and_readiness() -> None:
    embedding = FakeEmbeddingPort()
    index = _ApiIndex(
        version="v1-search",
        embedding=embedding,
        chunks=[_chunk("checkout", "checkout pool")],
    )
    initializer = _RecordingInitializer("v1-search")
    closed: list[bool] = []
    runtime = RagRuntime(
        initializer=initializer,
        service=RagService(embedding=embedding, index=index),
        index=index,
        close=lambda: closed.append(True),
    )
    main_thread = threading.get_ident()

    with TestClient(create_app(_api_settings(), runtime_factory=lambda: runtime)) as client:
        readiness = client.get("/internal/readiness")
        search = client.post(
            "/internal/rag/search",
            json={
                "contractVersion": 1,
                "knowledgeBaseId": "incident-ops",
                "indexVersion": "v1-search",
                "query": "checkout pool",
                "topK": 1,
            },
        )
        active = client.get("/internal/rag/indexes/incident-ops/active")
        unknown = client.get("/internal/rag/indexes/unknown/active")

    assert initializer.thread_id is not None
    assert initializer.thread_id != main_thread
    assert readiness.json()["rag"] == {"ready": True, "reason": "ready"}
    assert search.status_code == 200
    assert search.json()["hits"][0]["chunkId"] == "checkout"
    assert active.status_code == 200
    assert active.json() == {
        "contractVersion": 1,
        "knowledgeBaseId": "incident-ops",
        "indexVersion": "v1-search",
        "ready": True,
    }
    assert unknown.status_code == 404
    assert closed == [True]


def test_api_returns_empty_hits_and_never_substitutes_active_version() -> None:
    embedding = FakeEmbeddingPort()
    index = _ApiIndex(version="v1-search", embedding=embedding, chunks=[])
    runtime = RagRuntime(
        initializer=_RecordingInitializer("v1-search"),
        service=RagService(embedding=embedding, index=index),
        index=index,
        close=lambda: None,
    )

    with TestClient(create_app(_api_settings(), runtime_factory=lambda: runtime)) as client:
        empty = client.post(
            "/internal/rag/search",
            json=_search_body(index_version="v1-search"),
        )
        absent = client.post(
            "/internal/rag/search",
            json=_search_body(index_version="v1-absent"),
        )

    assert empty.status_code == 200
    assert empty.json() == {
        "contractVersion": 1,
        "indexVersion": "v1-search",
        "hits": [],
    }
    assert absent.status_code == 404
    assert absent.json()["code"] == "INDEX_VERSION_NOT_FOUND"


def _request(*, query: str, top_k: int) -> RagSearchRequest:
    return RagSearchRequest(
        contract_version=1,
        knowledge_base_id="incident-ops",
        index_version="v1-search",
        query=query,
        top_k=top_k,
    )


def _chunk(chunk_id: str, content: str) -> KnowledgeChunk:
    return KnowledgeChunk(
        chunk_id=chunk_id,
        document_id=f"documents/{chunk_id}",
        title=f"Title {chunk_id}",
        section="Section",
        source=f"documents/{chunk_id}.md",
        content=content,
        checksum="0" * 64,
    )


class _ApiIndex(InMemoryKnowledgeIndex):
    def __init__(
        self,
        *,
        version: str,
        embedding: FakeEmbeddingPort,
        chunks: Sequence[KnowledgeChunk],
    ) -> None:
        super().__init__(version=version, embedding=embedding, chunks=chunks)
        self._active = version

    def active_version(self) -> str | None:
        return self._active


class _RecordingInitializer:
    def __init__(self, version: str) -> None:
        self._version = version
        self.thread_id: int | None = None

    def initialize(self) -> str:
        self.thread_id = threading.get_ident()
        return self._version


def _api_settings() -> Settings:
    return Settings.model_validate(
        {
            "env": "test",
            "redis_url": "redis://redis.test:6379/0",
            "mysql_url": "mysql+pymysql://tester:password@mysql.test/fake_ops",
            "knowledge_root": Path("/tmp/unused-knowledge"),
            "model_id": "sentence-transformers/all-MiniLM-L6-v2",
            "acceptance_enabled": False,
            "chaos_enabled": False,
            "otlp_endpoint": "http://otel.test:4318/v1/traces",
        }
    )


def _search_body(*, index_version: str) -> dict[str, object]:
    return {
        "contractVersion": 1,
        "knowledgeBaseId": "incident-ops",
        "indexVersion": index_version,
        "query": "checkout pool",
        "topK": 3,
    }
