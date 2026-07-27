from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


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
    def model_id(self) -> str:
        raise NotImplementedError

    @property
    def dimensions(self) -> int:
        raise NotImplementedError

    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]:
        raise NotImplementedError
