import hashlib
import importlib
import math
import re
import struct
from collections.abc import Callable, Generator, Iterator, Mapping, Sequence
from pathlib import Path
from typing import Protocol, Self, cast

import pytest
from redis.exceptions import ResponseError

from agent_capabilities.rag.domain import EmbeddedChunk, KnowledgeChunk
from agent_capabilities.rag.embedding import l2_normalize
from agent_capabilities.rag.index import RedisClient, RedisKnowledgeIndex
from agent_capabilities.rag.initializer import (
    ActiveCommitOutcomeUnknownError,
    RagInitializer,
)
from agent_capabilities.rag.models import RagSearchRequest
from agent_capabilities.rag.service import RagService

pytestmark = pytest.mark.integration

_VERSION = "v1-integration"
_WRITE_COMMANDS = {
    "cmdstat_del",
    "cmdstat_eval",
    "cmdstat_evalsha",
    "cmdstat_ft.create",
    "cmdstat_ft.dropindex",
    "cmdstat_hset",
    "cmdstat_set",
}


class _TestRedisClient(RedisClient, Protocol):
    def ping(self) -> bool:
        raise NotImplementedError

    def flushall(self) -> bool:
        raise NotImplementedError

    def close(self) -> None:
        raise NotImplementedError

    def hgetall(self, name: str) -> dict[bytes, bytes]:
        raise NotImplementedError


class _RedisFactory(Protocol):
    def __call__(
        self, *, host: str, port: int, decode_responses: bool
    ) -> _TestRedisClient:
        raise NotImplementedError


class _Container(Protocol):
    def with_exposed_ports(self, *ports: int) -> Self:
        raise NotImplementedError

    def waiting_for(self, strategy: object) -> Self:
        raise NotImplementedError

    def get_container_host_ip(self) -> str:
        raise NotImplementedError

    def get_exposed_port(self, port: int) -> str:
        raise NotImplementedError

    def __enter__(self) -> Self:
        raise NotImplementedError

    def __exit__(
        self,
        exception_type: type[BaseException] | None,
        exception: BaseException | None,
        traceback: object | None,
    ) -> None:
        raise NotImplementedError


class _ContainerFactory(Protocol):
    def __call__(self, image: str) -> _Container:
        raise NotImplementedError


class _WaitStrategyFactory(Protocol):
    def __call__(self, message: str) -> object:
        raise NotImplementedError


@pytest.fixture(scope="module")
def redis_client() -> Generator[_TestRedisClient]:
    container_factory = _container_factory()
    wait_strategy_factory = _wait_strategy_factory()
    configured = (
        container_factory("redis:8")
        .with_exposed_ports(6379)
        .waiting_for(wait_strategy_factory("Ready to accept connections"))
    )
    with configured as container:
        client = _redis_factory()(
            host=container.get_container_host_ip(),
            port=int(container.get_exposed_port(6379)),
            decode_responses=False,
        )
        assert client.ping()
        yield client
        client.close()


@pytest.fixture(autouse=True)
def empty_redis(redis_client: _TestRedisClient) -> None:
    redis_client.flushall()


def test_index_uses_exact_hnsw_schema_namespace_metadata_and_stable_knn(
    redis_client: _TestRedisClient,
) -> None:
    index = RedisKnowledgeIndex(redis_client)
    owner_token = "schema-owner"
    chunks = [
        _embedded("chunk-b", 0),
        _embedded("chunk-a", 0),
        _embedded("chunk-c", 1),
    ]

    assert index.acquire_initialization_lock(_VERSION, owner_token)
    assert index.create_version(_VERSION, owner_token)
    assert index.write_chunks(_VERSION, chunks, owner_token)

    assert index.index_name(_VERSION) == "idx:rag:incident:v1-integration"
    assert index.chunk_prefix(_VERSION) == "rag:incident:chunk:v1-integration:"
    keys = sorted(
        _text(key)
        for key in redis_client.scan_iter(match=f"{index.chunk_prefix(_VERSION)}*")
    )
    assert keys == [
        "rag:incident:chunk:v1-integration:chunk-a",
        "rag:incident:chunk:v1-integration:chunk-b",
        "rag:incident:chunk:v1-integration:chunk-c",
    ]

    info = _deep_text(redis_client.execute_command("FT.INFO", index.index_name(_VERSION)))
    schema = repr(info)
    for expected in (
        "HNSW",
        "FLOAT32",
        "384",
        "COSINE",
        "chunk_id",
        "document_id",
        "title",
        "section",
        "source",
        "content",
        "checksum",
        "embedding_model",
        "index_version",
    ):
        assert expected in schema

    metadata = {
        _text(field): _text(value)
        for field, value in redis_client.hgetall(keys[0]).items()
        if _text(field) != "vector"
    }
    assert set(metadata) == {
        "chunk_id",
        "document_id",
        "title",
        "section",
        "source",
        "content",
        "checksum",
        "embedding_model",
        "index_version",
    }

    hits = index.search(_VERSION, _unit_vector(0), top_k=3)

    assert [hit.chunk.chunk_id for hit in hits] == ["chunk-a", "chunk-b", "chunk-c"]
    assert math.isclose(hits[0].score, 1.0, abs_tol=1e-6)
    assert math.isclose(hits[1].score, 1.0, abs_tol=1e-6)
    assert math.isclose(hits[2].score, 0.0, abs_tol=1e-6)


def test_service_preserves_raw_distance_order_when_public_scores_clamp_to_zero(
    redis_client: _TestRedisClient,
) -> None:
    index = RedisKnowledgeIndex(redis_client)
    owner_token = "ranking-owner"
    assert index.acquire_initialization_lock(_VERSION, owner_token)
    assert index.create_version(_VERSION, owner_token)
    assert index.write_chunks(
        _VERSION,
        [
            _embedded_vector("z-nearer", _negative_cosine_vector(-0.25)),
            _embedded_vector("a-farther", _negative_cosine_vector(-0.75)),
        ],
        owner_token,
    )
    redis_client.set(index.ready_key(_VERSION), "{}")
    service = RagService(embedding=_FixedQueryEmbedding(), index=index, min_score=0.0)

    response = service.search(
        RagSearchRequest(
            contract_version=1,
            knowledge_base_id="incident-ops",
            index_version=_VERSION,
            query="negative cosine ranking",
            top_k=2,
        )
    )

    assert [hit.chunk_id for hit in response.hits] == ["z-nearer", "a-farther"]
    assert [hit.score for hit in response.hits] == [0.0, 0.0]


def test_initializer_validates_count_and_smoke_before_switch_and_preserves_previous(
    redis_client: _TestRedisClient,
) -> None:
    knowledge_root = Path(__file__).resolve().parents[3] / "knowledge" / "incident-ops"
    baseline_index = RedisKnowledgeIndex(redis_client)
    baseline = RagInitializer(
        knowledge_root=knowledge_root,
        embedding=_DeterministicEmbedding("model-a"),
        index=baseline_index,
    ).initialize()
    assert baseline_index.active_version() == baseline

    failing_index = _FailBeforeSwitchIndex(redis_client)
    with pytest.raises(RuntimeError, match="forced failure before active switch"):
        RagInitializer(
            knowledge_root=knowledge_root,
            embedding=_DeterministicEmbedding("model-b"),
            index=failing_index,
        ).initialize()

    assert failing_index.events == ["create", "count", "smoke", "count"]
    assert baseline_index.active_version() == baseline
    failed_version = failing_index.created_version
    assert failed_version is not None
    assert list(redis_client.scan_iter(match=f"{failing_index.chunk_prefix(failed_version)}*")) == []
    assert not failing_index.has_version(failed_version)
    with pytest.raises(ResponseError):
        redis_client.execute_command("FT.INFO", failing_index.index_name(failed_version))


def test_initializer_stops_before_writes_when_successor_takes_over_after_embedding(
    redis_client: _TestRedisClient,
) -> None:
    knowledge_root = Path(__file__).resolve().parents[3] / "knowledge" / "incident-ops"
    prior_version = "v1-prior-active"
    index = _TakeoverAfterAcquireIndex(redis_client, successor_token="owner-b")
    redis_client.set("rag:incident:active", prior_version)
    embedding = _TakeoverEmbedding("model-stale", index.take_over)

    with pytest.raises(RuntimeError, match="initialization lock.*lost"):
        RagInitializer(
            knowledge_root=knowledge_root,
            embedding=embedding,
            index=index,
        ).initialize()

    stale_version = index.acquired_version
    assert stale_version is not None
    assert index.takeover_occurred
    assert index.active_version() == prior_version
    assert index.owns_initialization_lock(stale_version, "owner-b")
    assert not index.has_version(stale_version)
    assert list(redis_client.scan_iter(match=f"{index.chunk_prefix(stale_version)}*")) == []
    with pytest.raises(ResponseError):
        redis_client.execute_command("FT.INFO", index.index_name(stale_version))


def test_cleanup_cannot_delete_successor_data_when_ownership_changes_at_boundary(
    redis_client: _TestRedisClient,
) -> None:
    prior_version = "v1-prior-active"
    owner_a = "owner-a"
    owner_b = "owner-b"
    index = RedisKnowledgeIndex(redis_client)
    redis_client.set("rag:incident:active", prior_version)
    assert index.acquire_initialization_lock(_VERSION, owner_a)
    assert index.create_version(_VERSION, owner_a)
    boundary_client = _CleanupBoundaryRedisClient(
        redis_client,
        index_version=_VERSION,
        owner_a=owner_a,
        owner_b=owner_b,
    )

    RedisKnowledgeIndex(boundary_client).cleanup_failed_version(_VERSION, owner_a)

    successor_key = f"{index.chunk_prefix(_VERSION)}successor-chunk"
    assert boundary_client.takeover_occurred
    assert index.active_version() == prior_version
    assert index.owns_initialization_lock(_VERSION, owner_b)
    assert index.has_version(_VERSION)
    assert redis_client.hgetall(successor_key)
    redis_client.execute_command("FT.INFO", index.index_name(_VERSION))


def test_initializer_reconciles_active_commit_when_response_is_lost(
    redis_client: _TestRedisClient,
) -> None:
    knowledge_root = Path(__file__).resolve().parents[3] / "knowledge" / "incident-ops"
    embedding = _DeterministicEmbedding("model-post-apply-failure")
    index = _RaiseAfterActiveIndex(redis_client)
    redis_client.set("rag:incident:active", "v1-prior-active")
    initialized_version: str | None = None

    try:
        initialized_version = RagInitializer(
            knowledge_root=knowledge_root,
            embedding=embedding,
            index=index,
        ).initialize()
    except RuntimeError as error:
        assert str(error) == "lost activation response"
        initialized_version = index.active_version()

    assert initialized_version is not None
    assert index.active_version() == initialized_version
    assert index.has_version(initialized_version)
    query_vector = embedding.embed(["searchable committed version"])[0]
    assert index.search(initialized_version, query_vector, top_k=1)


def test_initializer_cleans_pre_apply_active_failure_and_preserves_previous(
    redis_client: _TestRedisClient,
) -> None:
    knowledge_root = Path(__file__).resolve().parents[3] / "knowledge" / "incident-ops"
    prior_version = "v1-prior-active"
    index = _RaiseBeforeActiveIndex(redis_client)
    redis_client.set("rag:incident:active", prior_version)

    with pytest.raises(RuntimeError, match="failure before activation apply"):
        RagInitializer(
            knowledge_root=knowledge_root,
            embedding=_DeterministicEmbedding("model-pre-apply-failure"),
            index=index,
        ).initialize()

    failed_version = index.created_version
    assert failed_version is not None
    assert index.active_version() == prior_version
    assert not index.has_version(failed_version)
    assert list(redis_client.scan_iter(match=f"{index.chunk_prefix(failed_version)}*")) == []
    with pytest.raises(ResponseError):
        redis_client.execute_command("FT.INFO", index.index_name(failed_version))


def test_initializer_fails_closed_when_active_commit_cannot_be_reconciled(
    redis_client: _TestRedisClient,
) -> None:
    knowledge_root = Path(__file__).resolve().parents[3] / "knowledge" / "incident-ops"
    embedding = _DeterministicEmbedding("model-unknown-active-outcome")
    index = _UnobservableAfterActiveIndex(redis_client)
    redis_client.set("rag:incident:active", "v1-prior-active")

    with pytest.raises(ActiveCommitOutcomeUnknownError, match="outcome is unknown"):
        RagInitializer(
            knowledge_root=knowledge_root,
            embedding=embedding,
            index=index,
        ).initialize()

    committed_version = index.committed_version
    assert committed_version is not None
    observable_index = RedisKnowledgeIndex(redis_client)
    assert observable_index.active_version() == committed_version
    assert observable_index.has_version(committed_version)
    query_vector = embedding.embed(["recoverable ready version"])[0]
    assert observable_index.search(committed_version, query_vector, top_k=1)


def test_cleanup_never_deletes_the_version_named_by_active_key(
    redis_client: _TestRedisClient,
) -> None:
    owner_token = "active-owner"
    index = RedisKnowledgeIndex(redis_client)
    assert index.acquire_initialization_lock(_VERSION, owner_token)
    assert index.create_version(_VERSION, owner_token)
    assert index.write_chunks(_VERSION, [_embedded("active-chunk", 0)], owner_token)
    redis_client.set(index.ready_key(_VERSION), "ready")
    assert index.set_active(_VERSION, owner_token)

    cleaned = index.cleanup_failed_version(_VERSION, owner_token)

    active_key = f"{index.chunk_prefix(_VERSION)}active-chunk"
    assert not cleaned
    assert index.active_version() == _VERSION
    assert index.has_version(_VERSION)
    assert redis_client.hgetall(active_key)
    redis_client.execute_command("FT.INFO", index.index_name(_VERSION))


def test_second_initialization_of_same_active_version_performs_zero_writes(
    redis_client: _TestRedisClient,
) -> None:
    knowledge_root = Path(__file__).resolve().parents[3] / "knowledge" / "incident-ops"
    index = RedisKnowledgeIndex(redis_client)
    initializer = RagInitializer(
        knowledge_root=knowledge_root,
        embedding=_DeterministicEmbedding("model-same"),
        index=index,
    )
    first = initializer.initialize()
    writes_before = _write_command_calls(redis_client)

    second = initializer.initialize()

    assert second == first
    assert index.active_version() == first
    assert _write_command_calls(redis_client) == writes_before


class _FailBeforeSwitchIndex(RedisKnowledgeIndex):
    def __init__(self, redis_client: _TestRedisClient) -> None:
        super().__init__(redis_client)
        self.events: list[str] = []
        self.created_version: str | None = None

    def create_version(self, index_version: str, owner_token: str) -> bool:
        self.events.append("create")
        self.created_version = index_version
        return super().create_version(index_version, owner_token)

    def count(self, index_version: str) -> int:
        self.events.append("count")
        return super().count(index_version)

    def smoke_query(self, index_version: str, vector: tuple[float, ...]) -> None:
        self.events.append("smoke")
        super().smoke_query(index_version, vector)
        raise RuntimeError("forced failure before active switch")

    def set_active(self, index_version: str, owner_token: str) -> bool:
        self.events.append("switch")
        return super().set_active(index_version, owner_token)


class _RaiseAfterActiveIndex(RedisKnowledgeIndex):
    def set_active(self, index_version: str, owner_token: str) -> bool:
        applied = super().set_active(index_version, owner_token)
        assert applied
        raise RuntimeError("lost activation response")


class _RaiseBeforeActiveIndex(RedisKnowledgeIndex):
    def __init__(self, redis_client: _TestRedisClient) -> None:
        super().__init__(redis_client)
        self.created_version: str | None = None

    def create_version(self, index_version: str, owner_token: str) -> bool:
        self.created_version = index_version
        return super().create_version(index_version, owner_token)

    def set_active(self, index_version: str, owner_token: str) -> bool:
        raise RuntimeError("failure before activation apply")


class _UnobservableAfterActiveIndex(RedisKnowledgeIndex):
    def __init__(self, redis_client: _TestRedisClient) -> None:
        super().__init__(redis_client)
        self.committed_version: str | None = None

    def set_active(self, index_version: str, owner_token: str) -> bool:
        applied = super().set_active(index_version, owner_token)
        assert applied
        self.committed_version = index_version
        raise RuntimeError("lost activation response")

    def active_version(self) -> str | None:
        raise RuntimeError("active commit reconciliation unavailable")


class _DeterministicEmbedding:
    def __init__(self, model_file_checksum: str) -> None:
        self._model_file_checksum = model_file_checksum

    @property
    def model_id(self) -> str:
        return "test/deterministic-384"

    @property
    def dimensions(self) -> int:
        return 384

    @property
    def model_file_checksum(self) -> str:
        return self._model_file_checksum

    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]:
        return [_hash_vector(text) for text in texts]


class _TakeoverEmbedding(_DeterministicEmbedding):
    def __init__(self, model_file_checksum: str, take_over: Callable[[], None]) -> None:
        super().__init__(model_file_checksum)
        self._take_over = take_over

    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]:
        self._take_over()
        return super().embed(texts)


class _FixedQueryEmbedding:
    @property
    def model_id(self) -> str:
        return "test/fixed-query-384"

    @property
    def dimensions(self) -> int:
        return 384

    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]:
        return [_unit_vector(0) for _ in texts]


class _TakeoverAfterAcquireIndex(RedisKnowledgeIndex):
    def __init__(
        self, redis_client: _TestRedisClient, *, successor_token: str
    ) -> None:
        super().__init__(redis_client)
        self._client = redis_client
        self._successor_token = successor_token
        self.acquired_version: str | None = None
        self.takeover_occurred = False

    def acquire_initialization_lock(self, index_version: str, owner_token: str) -> bool:
        acquired = super().acquire_initialization_lock(index_version, owner_token)
        if acquired:
            self.acquired_version = index_version
        return acquired

    def take_over(self) -> None:
        index_version = self.acquired_version
        assert index_version is not None
        assert self._client.delete(self.lock_key(index_version)) == 1
        assert self._client.set(
            self.lock_key(index_version),
            self._successor_token,
            nx=True,
            px=120_000,
        )
        self.takeover_occurred = True


class _CleanupBoundaryRedisClient:
    def __init__(
        self,
        delegate: _TestRedisClient,
        *,
        index_version: str,
        owner_a: str,
        owner_b: str,
    ) -> None:
        self._delegate = delegate
        self._index_version = index_version
        self._owner_a = owner_a
        self._owner_b = owner_b
        self.takeover_occurred = False

    def execute_command(self, *args: object) -> object:
        return self._delegate.execute_command(*args)

    def hset(self, name: str, *, mapping: Mapping[str, str | bytes]) -> int:
        return self._delegate.hset(name, mapping=mapping)

    def exists(self, name: str) -> int:
        return self._delegate.exists(name)

    def set(
        self,
        name: str,
        value: str,
        *,
        nx: bool = False,
        px: int | None = None,
    ) -> bool | None:
        return self._delegate.set(name, value, nx=nx, px=px)

    def get(self, name: str) -> bytes | None:
        observed = self._delegate.get(name)
        if name == RedisKnowledgeIndex.lock_key(self._index_version):
            assert observed == self._owner_a.encode("utf-8")
            self._take_over()
        return observed

    def eval(
        self, script: str, numkeys: int, *keys_and_args: str | bytes
    ) -> object:
        if (
            keys_and_args
            and keys_and_args[0] == RedisKnowledgeIndex.lock_key(self._index_version)
        ):
            self._take_over()
        return self._delegate.eval(script, numkeys, *keys_and_args)

    def scan_iter(self, *, match: str) -> Iterator[bytes]:
        return self._delegate.scan_iter(match=match)

    def delete(self, *names: bytes | str) -> int:
        return self._delegate.delete(*names)

    def _take_over(self) -> None:
        if self.takeover_occurred:
            return
        lock_key = RedisKnowledgeIndex.lock_key(self._index_version)
        assert self._delegate.delete(lock_key) == 1
        assert self._delegate.set(lock_key, self._owner_b, nx=True, px=120_000)
        chunk_key = (
            f"{RedisKnowledgeIndex.chunk_prefix(self._index_version)}successor-chunk"
        )
        self._delegate.hset(
            chunk_key,
            mapping={
                "chunk_id": "successor-chunk",
                "document_id": "documents/successor",
                "title": "Successor",
                "section": "Evidence",
                "source": "documents/successor.md",
                "content": "Successor-owned content",
                "checksum": hashlib.sha256(b"successor").hexdigest(),
                "embedding_model": "test/deterministic-384",
                "index_version": self._index_version,
                "vector": struct.pack("<384f", *_unit_vector(0)),
            },
        )
        self._delegate.set(
            RedisKnowledgeIndex.ready_key(self._index_version), "successor-ready"
        )
        self.takeover_occurred = True


def _embedded(chunk_id: str, vector_index: int) -> EmbeddedChunk:
    return _embedded_vector(chunk_id, _unit_vector(vector_index))


def _embedded_vector(chunk_id: str, vector: tuple[float, ...]) -> EmbeddedChunk:
    chunk = KnowledgeChunk(
        chunk_id=chunk_id,
        document_id=f"documents/{chunk_id}",
        title=f"Title {chunk_id}",
        section="Evidence",
        source=f"documents/{chunk_id}.md",
        content=f"Content for {chunk_id}",
        checksum=hashlib.sha256(chunk_id.encode("utf-8")).hexdigest(),
    )
    return EmbeddedChunk(
        chunk=chunk,
        vector=vector,
        embedding_model="test/deterministic-384",
        index_version=_VERSION,
    )


def _unit_vector(index: int) -> tuple[float, ...]:
    values = [0.0] * 384
    values[index] = 1.0
    return tuple(values)


def _negative_cosine_vector(cosine: float) -> tuple[float, ...]:
    values = [0.0] * 384
    values[0] = cosine
    values[1] = math.sqrt(1.0 - (cosine * cosine))
    return tuple(values)


def _hash_vector(text: str) -> tuple[float, ...]:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    values = [((digest[index % len(digest)] / 255.0) - 0.5) for index in range(384)]
    return l2_normalize(values)


def _write_command_calls(redis_client: _TestRedisClient) -> int:
    raw: object = redis_client.execute_command("INFO", "commandstats")
    if isinstance(raw, dict):
        command_stats = cast(dict[object, object], raw)
        return sum(
            _calls(value)
            for command, value in command_stats.items()
            if _text(command).casefold() in _WRITE_COMMANDS
        )
    info = _text(raw)
    calls = 0
    for line in info.splitlines():
        command, separator, stats = line.partition(":")
        if not separator or command not in _WRITE_COMMANDS:
            continue
        match = re.search(r"(?:^|,)calls=(\d+)(?:,|$)", stats)
        if match is not None:
            calls += int(match.group(1))
    return calls


def _calls(value: object) -> int:
    if not isinstance(value, dict):
        raise ValueError("expected Redis command statistics")
    stats = cast(dict[object, object], value)
    for name, observed in stats.items():
        if _text(name) == "calls" and isinstance(observed, int):
            return observed
    raise ValueError("Redis command statistics are missing calls")


def _text(value: object) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8")
    if isinstance(value, str):
        return value
    raise ValueError("expected Redis text")


def _deep_text(value: object) -> object:
    if isinstance(value, bytes):
        return value.decode("utf-8")
    if isinstance(value, list):
        return [_deep_text(item) for item in cast(list[object], value)]
    if isinstance(value, dict):
        mapping = cast(dict[object, object], value)
        return {_deep_text(key): _deep_text(item) for key, item in mapping.items()}
    return value


def _redis_factory() -> _RedisFactory:
    redis_module = importlib.import_module("redis")
    return cast(_RedisFactory, getattr(redis_module, "Redis"))


def _container_factory() -> _ContainerFactory:
    module = importlib.import_module("testcontainers.core.container")
    return cast(_ContainerFactory, getattr(module, "DockerContainer"))


def _wait_strategy_factory() -> _WaitStrategyFactory:
    module = importlib.import_module("testcontainers.core.wait_strategies")
    return cast(_WaitStrategyFactory, getattr(module, "LogMessageWaitStrategy"))
