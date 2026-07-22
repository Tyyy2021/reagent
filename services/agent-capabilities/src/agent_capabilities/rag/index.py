import json
import math
import struct
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import asdict
from typing import Protocol, cast

from agent_capabilities.rag.domain import EmbeddedChunk, KnowledgeChunk
from agent_capabilities.rag.embedding import MINILM_DIMENSIONS
from agent_capabilities.rag.initializer import IndexManifest
from agent_capabilities.rag.service import IndexHit, KnowledgeIndexPort

_ACTIVE_KEY = "rag:incident:active"
_LOCK_TTL_MILLISECONDS = 120_000
_RENEW_LOCK_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
return redis.call('PEXPIRE', KEYS[1], ARGV[2])
""".strip()
_RELEASE_LOCK_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
""".strip()
_CREATE_VERSION_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
redis.call('PEXPIRE', KEYS[1], ARGV[2])
redis.call(
  'FT.CREATE', ARGV[3],
  'ON', 'HASH',
  'PREFIX', '1', ARGV[4],
  'SCHEMA',
  'chunk_id', 'TAG',
  'document_id', 'TAG',
  'title', 'TEXT', 'NOSTEM',
  'section', 'TEXT', 'NOSTEM',
  'source', 'TAG',
  'content', 'TEXT',
  'checksum', 'TAG',
  'embedding_model', 'TAG',
  'index_version', 'TAG',
  'vector', 'VECTOR', 'HNSW', '6',
  'TYPE', 'FLOAT32',
  'DIM', ARGV[5],
  'DISTANCE_METRIC', 'COSINE'
)
return 1
""".strip()
_WRITE_CHUNK_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
redis.call('PEXPIRE', KEYS[1], ARGV[2])
redis.call('HSET', KEYS[2], unpack(ARGV, 3))
return 1
""".strip()
_WRITE_READY_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
redis.call('PEXPIRE', KEYS[1], ARGV[2])
redis.call('SET', KEYS[2], ARGV[3])
return 1
""".strip()
_SET_ACTIVE_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
redis.call('PEXPIRE', KEYS[1], ARGV[2])
redis.call('SET', KEYS[2], ARGV[3])
return 1
""".strip()
_CLEANUP_VERSION_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end
if redis.call('GET', KEYS[3]) == ARGV[4] then
  return -1
end
local dropped = redis.pcall('FT.DROPINDEX', ARGV[2], 'DD')
if type(dropped) == 'table' and dropped.err then
  if not string.find(dropped.err, 'Unknown Index name', 1, true) then
    return redis.error_reply(dropped.err)
  end
end
local chunk_keys = redis.call('KEYS', ARGV[3])
if #chunk_keys > 0 then
  redis.call('DEL', unpack(chunk_keys))
end
redis.call('DEL', KEYS[2])
return 1
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

    def eval(
        self, script: str, numkeys: int, *keys_and_args: str | bytes
    ) -> object:
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

    def create_version(self, index_version: str, owner_token: str) -> bool:
        result = self._redis.eval(
            _CREATE_VERSION_SCRIPT,
            1,
            self.lock_key(index_version),
            owner_token,
            str(_LOCK_TTL_MILLISECONDS),
            self.index_name(index_version),
            self.chunk_prefix(index_version),
            str(MINILM_DIMENSIONS),
        )
        return _integer(result) == 1

    def write_chunks(
        self,
        index_version: str,
        embedded_chunks: Sequence[EmbeddedChunk],
        owner_token: str,
    ) -> bool:
        for embedded in embedded_chunks:
            if embedded.index_version != index_version:
                raise ValueError("embedded chunk index version does not match the target")
            vector = _vector_bytes(embedded.vector)
            chunk = embedded.chunk
            result = self._redis.eval(
                _WRITE_CHUNK_SCRIPT,
                2,
                self.lock_key(index_version),
                f"{self.chunk_prefix(index_version)}{chunk.chunk_id}",
                owner_token,
                str(_LOCK_TTL_MILLISECONDS),
                "chunk_id",
                chunk.chunk_id,
                "document_id",
                chunk.document_id,
                "title",
                chunk.title,
                "section",
                chunk.section,
                "source",
                chunk.source,
                "content",
                chunk.content,
                "checksum",
                chunk.checksum,
                "embedding_model",
                embedded.embedding_model,
                "index_version",
                embedded.index_version,
                "vector",
                vector,
            )
            if _integer(result) != 1:
                return False
        return True

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
        return sorted(hits, key=lambda hit: (hit.distance, hit.chunk.chunk_id))[:top_k]

    def has_version(self, index_version: str) -> bool:
        return bool(self._redis.exists(self.ready_key(index_version)))

    def write_ready(
        self, index_version: str, manifest: IndexManifest, owner_token: str
    ) -> bool:
        encoded = json.dumps(
            asdict(manifest), sort_keys=True, separators=(",", ":"), ensure_ascii=True
        )
        result = self._redis.eval(
            _WRITE_READY_SCRIPT,
            2,
            self.lock_key(index_version),
            self.ready_key(index_version),
            owner_token,
            str(_LOCK_TTL_MILLISECONDS),
            encoded,
        )
        return _integer(result) == 1

    def set_active(self, index_version: str, owner_token: str) -> bool:
        result = self._redis.eval(
            _SET_ACTIVE_SCRIPT,
            2,
            self.lock_key(index_version),
            _ACTIVE_KEY,
            owner_token,
            str(_LOCK_TTL_MILLISECONDS),
            index_version,
        )
        return _integer(result) == 1

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

    def renew_initialization_lock(self, index_version: str, owner_token: str) -> bool:
        result = self._redis.eval(
            _RENEW_LOCK_SCRIPT,
            1,
            self.lock_key(index_version),
            owner_token,
            str(_LOCK_TTL_MILLISECONDS),
        )
        return _integer(result) == 1

    def release_initialization_lock(self, index_version: str, owner_token: str) -> None:
        self._redis.eval(
            _RELEASE_LOCK_SCRIPT,
            1,
            self.lock_key(index_version),
            owner_token,
        )

    def cleanup_failed_version(self, index_version: str, owner_token: str) -> bool:
        result = self._redis.eval(
            _CLEANUP_VERSION_SCRIPT,
            3,
            self.lock_key(index_version),
            self.ready_key(index_version),
            _ACTIVE_KEY,
            owner_token,
            self.index_name(index_version),
            f"{self.chunk_prefix(index_version)}*",
            index_version,
        )
        return _integer(result) == 1


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
        chunk = KnowledgeChunk(
            chunk_id=_required_text(fields, "chunk_id"),
            document_id=_required_text(fields, "document_id"),
            title=_required_text(fields, "title"),
            section=_required_text(fields, "section"),
            source=_required_text(fields, "source"),
            content=_required_text(fields, "content"),
            checksum=_required_text(fields, "checksum"),
        )
        hits.append(IndexHit(chunk=chunk, distance=distance))
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
