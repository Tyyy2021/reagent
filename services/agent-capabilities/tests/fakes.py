import re
from collections.abc import Sequence

from agent_capabilities.rag.domain import EmbeddedChunk, KnowledgeChunk
from agent_capabilities.rag.embedding import l2_normalize
from agent_capabilities.rag.service import IndexHit


class FakeEmbeddingPort:
    _TOKEN_VECTORS: dict[str, tuple[float, float, float, float]] = {
        "checkout": (1.0, 0.0, 0.0, 0.0),
        "pool": (0.0, 1.0, 0.0, 0.0),
        "timeout": (0.5, 0.5, 0.0, 0.0),
        "kafka": (0.0, 0.0, 1.0, 0.0),
        "lag": (0.0, 0.0, 1.0, 0.0),
        "search": (0.0, 0.0, 0.0, 1.0),
        "latency": (0.0, 0.0, 0.0, 1.0),
    }

    @property
    def model_id(self) -> str:
        return "fake-token-embedding-v1"

    @property
    def dimensions(self) -> int:
        return 4

    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> tuple[float, ...]:
        vector = [0.0, 0.0, 0.0, 0.0]
        matched = False
        for token in re.findall(r"[a-z0-9]+", text.casefold()):
            token_vector = self._TOKEN_VECTORS.get(token)
            if token_vector is None:
                continue
            matched = True
            for index, value in enumerate(token_vector):
                vector[index] += value
        if not matched:
            vector[3] = 1.0
        return l2_normalize(vector)


class InMemoryKnowledgeIndex:
    def __init__(
        self,
        *,
        version: str,
        embedding: FakeEmbeddingPort,
        chunks: Sequence[KnowledgeChunk],
    ) -> None:
        vectors = embedding.embed([chunk.content for chunk in chunks])
        self._version = version
        self._chunks = [
            EmbeddedChunk(
                chunk=chunk,
                vector=vector,
                embedding_model=embedding.model_id,
                index_version=version,
            )
            for chunk, vector in zip(chunks, vectors, strict=True)
        ]

    def has_version(self, index_version: str) -> bool:
        return index_version == self._version

    def search(
        self, index_version: str, vector: tuple[float, ...], top_k: int
    ) -> list[IndexHit]:
        if not self.has_version(index_version):
            return []
        query = l2_normalize(vector)
        hits = [
            IndexHit(
                chunk=embedded.chunk,
                score=max(
                    0.0,
                    min(1.0, sum(left * right for left, right in zip(query, embedded.vector))),
                ),
            )
            for embedded in self._chunks
        ]
        return sorted(hits, key=lambda hit: (-hit.score, hit.chunk.chunk_id))[:top_k]
