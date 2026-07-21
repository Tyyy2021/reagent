import json
import math
import struct
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import asdict
from typing import Protocol, cast

from redis.exceptions import ResponseError

from agent_capabilities.rag.domain import EmbeddedChunk, KnowledgeChunk
from agent_capabilities.rag.embedding import MINILM_DIMENSIONS
from agent_capabilities.rag.initializer import IndexManifest
from agent_capabilities.rag.service import IndexHit, KnowledgeIndexPort

_ACTIVE_KEY = "rag:incident:active"
_LOCK_TTL_MILLISECONDS = 120_000
_RELEASE_LOCK_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
""".strip()


class RedisClient(Protocol):
    def execute_command(self, *args: object) -> object:
        raise NotImplementedError

    def hset(self, name: str, *, mapping: Mapping[str, str | bytes]) -> int:
        raise NotImplementedError

    def exists(self, name: str) -> int:
        raise NotImplementedError

    def set(
        self,
        name: str,
        value: str,
        *,
        nx: bool = False,
        px: int | None = None,
    ) -> bool | None:
        raise NotImplementedError

    def get(self, name: str) -> bytes | None:
        raise NotImplementedError

    def eval(self, script: str, numkeys: int, *keys_and_args: str) -> object:
        raise NotImplementedError

    def scan_iter(self, *, match: str) -> Iterator[bytes]:
        raise NotImplementedError

    def delete(self, *names: bytes | str) -> int:
        raise NotImplementedError


class RedisKnowledgeIndex(KnowledgeIndexPort):
    def __init__(self, redis_client: RedisClient) -> None:
        self._redis = redis_client

    @staticmethod
    def index_name(index_version: str) -> str:
        return f"idx:rag:incident:{index_version}"

    @staticmethod
    def chunk_prefix(index_version: str) -> str:
        return f"rag:incident:chunk:{index_version}:"

    @staticmethod
    def ready_key(index_version: str) -> str:
        return f"rag:incident:ready:{index_version}"

    @staticmethod
    def lock_key(index_version: str) -> str:
        return f"rag:incident:init:{index_version}"

    def create_version(self, index_version: str) -> None:
        self._redis.execute_command(
            "FT.CREATE",
            self.index_name(index_version),
            "ON",
            "HASH",
            "PREFIX",
            "1",
            self.chunk_prefix(index_version),
            "SCHEMA",
            "chunk_id",
            "TAG",
            "document_id",
            "TAG",
            "title",
            "TEXT",
            "NOSTEM",
            "section",
            "TEXT",
            "NOSTEM",
            "source",
            "TAG",
            "content",
            "TEXT",
            "checksum",
            "TAG",
            "embedding_model",
            "TAG",
            "index_version",
            "TAG",
            "vector",
            "VECTOR",
            "HNSW",
            "6",
            "TYPE",
            "FLOAT32",
            "DIM",
            str(MINILM_DIMENSIONS),
            "DISTANCE_METRIC",
            "COSINE",
        )

    def write_chunks(
        self, index_version: str, embedded_chunks: Sequence[EmbeddedChunk]
    ) -> None:
        for embedded in embedded_chunks:
            if embedded.index_version != index_version:
                raise ValueError("embedded chunk index version does not match the target")
            vector = _vector_bytes(embedded.vector)
            chunk = embedded.chunk
            self._redis.hset(
                f"{self.chunk_prefix(index_version)}{chunk.chunk_id}",
                mapping={
                    "chunk_id": chunk.chunk_id,
                    "document_id": chunk.document_id,
                    "title": chunk.title,
                    "section": chunk.section,
                    "source": chunk.source,
                    "content": chunk.content,
                    "checksum": chunk.checksum,
                    "embedding_model": embedded.embedding_model,
                    "index_version": embedded.index_version,
                    "vector": vector,
                },
            )

    def count(self, index_version: str) -> int:
        info: object = self._redis.execute_command("FT.INFO", self.index_name(index_version))
        value = _info_value(info, "num_docs")
        return _integer(value)

    def smoke_query(self, index_version: str, vector: tuple[float, ...]) -> None:
        if not self.search(index_version, vector, top_k=1):
            raise ValueError("RAG smoke query returned no chunks")

    def search(
        self, index_version: str, vector: tuple[float, ...], top_k: int
    ) -> list[IndexHit]:
        if top_k < 1:
            raise ValueError("top_k must be positive")
        vector_blob = _vector_bytes(vector)
        document_count = self.count(index_version)
        if document_count == 0:
            return []
        candidate_count = max(top_k, document_count)
        response: object = self._redis.execute_command(
            "FT.SEARCH",
            self.index_name(index_version),
            "(*)=>[KNN $K @vector $VECTOR AS distance]",
            "PARAMS",
            "4",
            "K",
            str(candidate_count),
            "VECTOR",
            vector_blob,
            "SORTBY",
            "distance",
            "ASC",
            "RETURN",
            "10",
            "chunk_id",
            "document_id",
            "title",
            "section",
            "source",
            "content",
            "checksum",
            "embedding_model",
            "index_version",
            "distance",
            "LIMIT",
            "0",
            str(candidate_count),
            "DIALECT",
            "2",
        )
        hits = _search_hits(response)
        return sorted(hits, key=lambda hit: (-hit.score, hit.chunk.chunk_id))[:top_k]

    def has_version(self, index_version: str) -> bool:
        return bool(self._redis.exists(self.ready_key(index_version)))

    def write_ready(self, index_version: str, manifest: IndexManifest) -> None:
        encoded = json.dumps(
            asdict(manifest), sort_keys=True, separators=(",", ":"), ensure_ascii=True
        )
        self._redis.set(self.ready_key(index_version), encoded)

    def set_active(self, index_version: str) -> None:
        self._redis.set(_ACTIVE_KEY, index_version)

    def active_version(self) -> str | None:
        value = self._redis.get(_ACTIVE_KEY)
        return _optional_text(value)

    def acquire_initialization_lock(self, index_version: str, owner_token: str) -> bool:
        acquired = self._redis.set(
            self.lock_key(index_version),
            owner_token,
            nx=True,
            px=_LOCK_TTL_MILLISECONDS,
        )
        return acquired is True

    def owns_initialization_lock(self, index_version: str, owner_token: str) -> bool:
        observed = self._redis.get(self.lock_key(index_version))
        return _optional_text(observed) == owner_token

    def release_initialization_lock(self, index_version: str, owner_token: str) -> None:
        self._redis.eval(
            _RELEASE_LOCK_SCRIPT,
            1,
            self.lock_key(index_version),
            owner_token,
        )

    def cleanup_failed_version(self, index_version: str, owner_token: str) -> None:
        if not self.owns_initialization_lock(index_version, owner_token):
            return
        try:
            self._redis.execute_command("FT.DROPINDEX", self.index_name(index_version), "DD")
        except ResponseError as error:
            if "Unknown Index name" not in str(error):
                raise
        keys: list[bytes] = list(
            self._redis.scan_iter(match=f"{self.chunk_prefix(index_version)}*")
        )
        if keys:
            self._redis.delete(*keys)
        self._redis.delete(self.ready_key(index_version))


def _vector_bytes(vector: Sequence[float]) -> bytes:
    if len(vector) != MINILM_DIMENSIONS:
        raise ValueError(f"Redis vector dimension must be {MINILM_DIMENSIONS}")
    values = tuple(float(value) for value in vector)
    if not all(math.isfinite(value) for value in values):
        raise ValueError("Redis vector values must be finite")
    return struct.pack(f"<{MINILM_DIMENSIONS}f", *values)


def _search_hits(response: object) -> list[IndexHit]:
    values = _list(response)
    hits: list[IndexHit] = []
    for offset in range(1, len(values), 2):
        if offset + 1 >= len(values):
            raise ValueError("Redis search response is truncated")
        fields = _field_map(values[offset + 1])
        distance = float(_required_text(fields, "distance"))
        if not math.isfinite(distance):
            raise ValueError("Redis cosine distance must be finite")
        score = max(0.0, min(1.0, 1.0 - distance))
        chunk = KnowledgeChunk(
            chunk_id=_required_text(fields, "chunk_id"),
            document_id=_required_text(fields, "document_id"),
            title=_required_text(fields, "title"),
            section=_required_text(fields, "section"),
            source=_required_text(fields, "source"),
            content=_required_text(fields, "content"),
            checksum=_required_text(fields, "checksum"),
        )
        hits.append(IndexHit(chunk=chunk, score=score))
    return hits


def _info_value(response: object, name: str) -> object:
    values = _list(response)
    for offset in range(0, len(values) - 1, 2):
        if _text(values[offset]) == name:
            return values[offset + 1]
    raise ValueError(f"Redis index info is missing {name}")


def _field_map(response: object) -> dict[str, object]:
    values = _list(response)
    if len(values) % 2 != 0:
        raise ValueError("Redis field response must contain pairs")
    return {_text(values[index]): values[index + 1] for index in range(0, len(values), 2)}


def _required_text(fields: dict[str, object], name: str) -> str:
    if name not in fields:
        raise ValueError(f"Redis search result is missing {name}")
    return _text(fields[name])


def _list(response: object) -> list[object]:
    if not isinstance(response, (list, tuple)):
        raise ValueError("Redis response must be a list")
    return list(cast(Sequence[object], response))


def _integer(value: object) -> int:
    if isinstance(value, int):
        return value
    return int(_text(value))


def _optional_text(value: object | None) -> str | None:
    return None if value is None else _text(value)


def _text(value: object) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8")
    if isinstance(value, str):
        return value
    raise ValueError("Redis text field has an unexpected type")
