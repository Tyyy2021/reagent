import hashlib
import importlib
import math
import re
from collections.abc import Generator, Sequence
from pathlib import Path
from typing import Protocol, Self, cast

import pytest
from redis.exceptions import ResponseError

from agent_capabilities.rag.domain import EmbeddedChunk, KnowledgeChunk
from agent_capabilities.rag.embedding import l2_normalize
from agent_capabilities.rag.index import RedisClient, RedisKnowledgeIndex
from agent_capabilities.rag.initializer import RagInitializer

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
    chunks = [
        _embedded("chunk-b", 0),
        _embedded("chunk-a", 0),
        _embedded("chunk-c", 1),
    ]

    index.create_version(_VERSION)
    index.write_chunks(_VERSION, chunks)

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

    def create_version(self, index_version: str) -> None:
        self.events.append("create")
        self.created_version = index_version
        super().create_version(index_version)

    def count(self, index_version: str) -> int:
        self.events.append("count")
        return super().count(index_version)

    def smoke_query(self, index_version: str, vector: tuple[float, ...]) -> None:
        self.events.append("smoke")
        super().smoke_query(index_version, vector)
        raise RuntimeError("forced failure before active switch")

    def set_active(self, index_version: str) -> None:
        self.events.append("switch")
        super().set_active(index_version)


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


def _embedded(chunk_id: str, vector_index: int) -> EmbeddedChunk:
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
        vector=_unit_vector(vector_index),
        embedding_model="test/deterministic-384",
        index_version=_VERSION,
    )


def _unit_vector(index: int) -> tuple[float, ...]:
    values = [0.0] * 384
    values[index] = 1.0
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
