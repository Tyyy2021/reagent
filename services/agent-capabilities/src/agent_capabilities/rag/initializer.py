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

    def renew_initialization_lock(self, index_version: str, owner_token: str) -> bool:
        raise NotImplementedError

    def cleanup_failed_version(self, index_version: str, owner_token: str) -> bool:
        raise NotImplementedError

    def create_version(self, index_version: str, owner_token: str) -> bool:
        raise NotImplementedError

    def write_chunks(
        self,
        index_version: str,
        embedded_chunks: Sequence[EmbeddedChunk],
        owner_token: str,
    ) -> bool:
        raise NotImplementedError

    def count(self, index_version: str) -> int:
        raise NotImplementedError

    def smoke_query(self, index_version: str, vector: tuple[float, ...]) -> None:
        raise NotImplementedError

    def write_ready(
        self, index_version: str, manifest: IndexManifest, owner_token: str
    ) -> bool:
        raise NotImplementedError

    def set_active(self, index_version: str, owner_token: str) -> bool:
        raise NotImplementedError


class InitializationInProgressError(RuntimeError):
    pass


class InitializationLockLostError(RuntimeError):
    def __init__(self, index_version: str, phase: str) -> None:
        super().__init__(f"RAG initialization lock was lost before {phase}: {index_version}")
        self.index_version = index_version
        self.phase = phase


class ActiveCommitOutcomeUnknownError(RuntimeError):
    def __init__(self, index_version: str) -> None:
        super().__init__(f"RAG active commit outcome is unknown: {index_version}")
        self.index_version = index_version


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
                self._require_lock(
                    self._index.renew_initialization_lock(version, owner_token),
                    version,
                    "existing-index validation",
                )
                self._validate_existing(version, len(chunks), smoke_vector)
                return self._commit_active(version, owner_token)

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

            self._require_lock(
                self._index.create_version(version, owner_token),
                version,
                "index creation",
            )
            self._require_lock(
                self._index.write_chunks(version, embedded_chunks, owner_token),
                version,
                "chunk writes",
            )
            self._require_lock(
                self._index.renew_initialization_lock(version, owner_token),
                version,
                "index validation",
            )
            self._validate_existing(version, len(chunks), smoke_vector)
            self._require_lock(
                self._index.write_ready(version, manifest, owner_token),
                version,
                "ready publication",
            )
            return self._commit_active(version, owner_token)
        except ActiveCommitOutcomeUnknownError:
            raise
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

    def _commit_active(self, index_version: str, owner_token: str) -> str:
        try:
            owned_commit = self._index.set_active(index_version, owner_token)
        except Exception as activation_error:
            return self._reconcile_active_commit(
                index_version,
                owner_token,
                activation_error,
            )
        self._require_lock(owned_commit, index_version, "active commit")
        return index_version

    def _reconcile_active_commit(
        self,
        index_version: str,
        owner_token: str,
        activation_error: Exception,
    ) -> str:
        try:
            active_version = self._index.active_version()
            if active_version == index_version:
                if self._index.has_version(index_version):
                    return index_version
                raise ActiveCommitOutcomeUnknownError(index_version)
            still_owned = self._index.renew_initialization_lock(
                index_version, owner_token
            )
        except ActiveCommitOutcomeUnknownError:
            raise
        except Exception as reconciliation_error:
            raise ActiveCommitOutcomeUnknownError(index_version) from reconciliation_error

        if not still_owned:
            raise InitializationLockLostError(
                index_version, "active-commit reconciliation"
            ) from activation_error
        raise activation_error

    @staticmethod
    def _require_lock(owned: bool, index_version: str, phase: str) -> None:
        if not owned:
            raise InitializationLockLostError(index_version, phase)
