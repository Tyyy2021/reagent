import math
from dataclasses import dataclass
from typing import Protocol

from agent_capabilities.rag.domain import EmbeddingPort, KnowledgeChunk
from agent_capabilities.rag.embedding import l2_normalize
from agent_capabilities.rag.models import RagHit, RagSearchRequest, RagSearchResponse

KNOWLEDGE_BASE_ID = "incident-ops"
MAX_EXCERPT_CODEPOINTS = 1_200


class UnknownKnowledgeBaseError(ValueError):
    def __init__(self, knowledge_base_id: str) -> None:
        super().__init__(f"unknown knowledge base: {knowledge_base_id}")
        self.knowledge_base_id = knowledge_base_id


class IndexVersionNotFoundError(ValueError):
    def __init__(self, index_version: str) -> None:
        super().__init__(f"index version is unavailable: {index_version}")
        self.index_version = index_version


@dataclass(frozen=True, slots=True)
class IndexHit:
    chunk: KnowledgeChunk
    score: float


class KnowledgeIndexPort(Protocol):
    def has_version(self, index_version: str) -> bool:
        raise NotImplementedError

    def search(
        self, index_version: str, vector: tuple[float, ...], top_k: int
    ) -> list[IndexHit]:
        raise NotImplementedError


class RagService:
    def __init__(
        self,
        embedding: EmbeddingPort,
        index: KnowledgeIndexPort,
        *,
        min_score: float = 0.20,
    ) -> None:
        if not math.isfinite(min_score) or not 0.0 <= min_score <= 1.0:
            raise ValueError("min_score must be finite and between zero and one")
        self._embedding = embedding
        self._index = index
        self._min_score = min_score

    def search(self, request: RagSearchRequest) -> RagSearchResponse:
        if request.knowledge_base_id != KNOWLEDGE_BASE_ID:
            raise UnknownKnowledgeBaseError(request.knowledge_base_id)
        if not self._index.has_version(request.index_version):
            raise IndexVersionNotFoundError(request.index_version)

        vectors = self._embedding.embed([request.query])
        if len(vectors) != 1:
            raise ValueError("embedding adapter returned an unexpected vector count")
        query_vector = l2_normalize(vectors[0])
        if len(query_vector) != self._embedding.dimensions:
            raise ValueError("embedding vector dimension does not match the adapter")

        candidates = self._index.search(request.index_version, query_vector, request.top_k)
        ranked = sorted(candidates, key=lambda hit: (-hit.score, hit.chunk.chunk_id))
        hits = [
            RagHit(
                chunk_id=hit.chunk.chunk_id,
                title=hit.chunk.title,
                section=hit.chunk.section,
                source=hit.chunk.source,
                score=_bounded_score(hit.score),
                excerpt=hit.chunk.content[:MAX_EXCERPT_CODEPOINTS],
            )
            for hit in ranked
            if hit.score >= self._min_score
        ][: request.top_k]
        return RagSearchResponse(
            contract_version=1,
            index_version=request.index_version,
            hits=hits,
        )


def _bounded_score(score: float) -> float:
    if not math.isfinite(score):
        raise ValueError("index score must be finite")
    bounded = max(0.0, min(1.0, score))
    if math.isclose(bounded, 1.0, abs_tol=1e-12):
        return 1.0
    return bounded
