import hashlib
import json
import secrets
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol

from agent_capabilities.rag.chunker import CHUNKER_VERSION, chunk_documents
from agent_capabilities.rag.domain import EmbeddedChunk, EmbeddingPort, KnowledgeDocument
from agent_capabilities.rag.loader import load_documents

SMOKE_QUERY = "checkout database connection pool exhaustion"


@dataclass(frozen=True, slots=True)
class IndexManifest:
    datasetChecksums: tuple[tuple[str, str], ...]
    chunkerVersion: str
    modelId: str
    modelFileChecksum: str
    dimension: int
    distance: str


class VersionedEmbeddingPort(EmbeddingPort, Protocol):
    @property
    def model_file_checksum(self) -> str:
        raise NotImplementedError


class InitializableKnowledgeIndex(Protocol):
    def has_version(self, index_version: str) -> bool:
        raise NotImplementedError

    def active_version(self) -> str | None:
        raise NotImplementedError

    def acquire_initialization_lock(self, index_version: str, owner_token: str) -> bool:
        raise NotImplementedError

    def release_initialization_lock(self, index_version: str, owner_token: str) -> None:
        raise NotImplementedError

    def cleanup_failed_version(self, index_version: str, owner_token: str) -> None:
        raise NotImplementedError

    def create_version(self, index_version: str) -> None:
        raise NotImplementedError

    def write_chunks(
        self, index_version: str, embedded_chunks: Sequence[EmbeddedChunk]
    ) -> None:
        raise NotImplementedError

    def count(self, index_version: str) -> int:
        raise NotImplementedError

    def smoke_query(self, index_version: str, vector: tuple[float, ...]) -> None:
        raise NotImplementedError

    def write_ready(self, index_version: str, manifest: IndexManifest) -> None:
        raise NotImplementedError

    def set_active(self, index_version: str) -> None:
        raise NotImplementedError


class InitializationInProgressError(RuntimeError):
    pass


def index_version(manifest: IndexManifest) -> str:
    encoded = json.dumps(
        asdict(manifest), sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    return f"v1-{hashlib.sha256(encoded).hexdigest()[:16]}"


class RagInitializer:
    def __init__(
        self,
        *,
        knowledge_root: Path,
        embedding: VersionedEmbeddingPort,
        index: InitializableKnowledgeIndex,
    ) -> None:
        if embedding.dimensions != 384:
            raise ValueError("RAG initialization requires 384-dimensional embeddings")
        self._knowledge_root = knowledge_root
        self._embedding = embedding
        self._index = index

    def initialize(self) -> str:
        documents = load_documents(self._knowledge_root)
        chunks = chunk_documents(documents)
        if not chunks:
            raise ValueError("RAG corpus must produce at least one chunk")
        manifest = self._manifest(documents)
        version = index_version(manifest)

        if self._index.has_version(version) and self._index.active_version() == version:
            return version

        owner_token = secrets.token_hex(32)
        if not self._index.acquire_initialization_lock(version, owner_token):
            raise InitializationInProgressError(f"RAG initialization is already active: {version}")

        try:
            if self._index.has_version(version):
                smoke_vector = self._only_vector(self._embedding.embed([SMOKE_QUERY]))
                self._validate_existing(version, len(chunks), smoke_vector)
                self._index.set_active(version)
                return version

            texts = [chunk.content for chunk in chunks]
            vectors = self._embedding.embed([*texts, SMOKE_QUERY])
            if len(vectors) != len(chunks) + 1:
                raise ValueError("embedding adapter returned an unexpected vector count")
            embedded_chunks = [
                EmbeddedChunk(
                    chunk=chunk,
                    vector=vector,
                    embedding_model=self._embedding.model_id,
                    index_version=version,
                )
                for chunk, vector in zip(chunks, vectors[:-1], strict=True)
            ]
            smoke_vector = vectors[-1]

            self._index.create_version(version)
            self._index.write_chunks(version, embedded_chunks)
            self._validate_existing(version, len(chunks), smoke_vector)
            self._index.write_ready(version, manifest)
            self._index.set_active(version)
            return version
        except BaseException:
            self._index.cleanup_failed_version(version, owner_token)
            raise
        finally:
            self._index.release_initialization_lock(version, owner_token)

    def _manifest(self, documents: Sequence[KnowledgeDocument]) -> IndexManifest:
        dataset_checksums = tuple(
            (
                document.source,
                hashlib.sha256(document.markdown.encode("utf-8")).hexdigest(),
            )
            for document in documents
        )
        return IndexManifest(
            datasetChecksums=dataset_checksums,
            chunkerVersion=CHUNKER_VERSION,
            modelId=self._embedding.model_id,
            modelFileChecksum=self._embedding.model_file_checksum,
            dimension=self._embedding.dimensions,
            distance="COSINE",
        )

    def _validate_existing(
        self, index_version: str, expected_count: int, smoke_vector: tuple[float, ...]
    ) -> None:
        observed_count = self._index.count(index_version)
        if observed_count != expected_count:
            raise ValueError(
                f"RAG index count mismatch: expected {expected_count}, got {observed_count}"
            )
        self._index.smoke_query(index_version, smoke_vector)

    @staticmethod
    def _only_vector(vectors: Sequence[tuple[float, ...]]) -> tuple[float, ...]:
        if len(vectors) != 1:
            raise ValueError("embedding adapter returned an unexpected vector count")
        return vectors[0]
