import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import cast

import pytest

from agent_capabilities.rag.chunker import chunk_documents
from agent_capabilities.rag.embedding import MiniLmEmbedding
from agent_capabilities.rag.loader import load_documents

pytestmark = pytest.mark.quality


@dataclass(frozen=True, slots=True)
class EvaluationQuery:
    query: str
    expected_document_id: str


@dataclass(frozen=True, slots=True)
class QualityHit:
    chunk_id: str
    document_id: str
    source: str
    score: float


@dataclass(frozen=True, slots=True)
class QualityCase:
    query: str
    expected_document_id: str
    rank: int | None
    reciprocal_rank: float
    hits: tuple[QualityHit, ...]

    @property
    def top_three_document_ids(self) -> tuple[str, ...]:
        return tuple(hit.document_id for hit in self.hits)


@dataclass(frozen=True, slots=True)
class QualityReport:
    model_id: str
    mrr: float
    cases: tuple[QualityCase, ...]


def test_real_minilm_retrieval_quality() -> None:
    repo_root = Path(__file__).resolve().parents[3]
    evaluation_path = repo_root / "services" / "agent-capabilities" / "evaluation" / "queries.json"
    queries = _load_queries(evaluation_path)
    hf_home = Path(os.environ["HF_HOME"])
    documents = load_documents(repo_root / "knowledge" / "incident-ops")
    chunks = chunk_documents(documents)
    embedding = MiniLmEmbedding(cache_dir=hf_home)

    vectors = embedding.embed(
        [*[chunk.content for chunk in chunks], *[case.query for case in queries]]
    )
    chunk_vectors = vectors[: len(chunks)]
    query_vectors = vectors[len(chunks) :]
    cases: list[QualityCase] = []
    for query, query_vector in zip(queries, query_vectors, strict=True):
        ranked = sorted(
            (
                (
                    max(
                        0.0,
                        min(
                            1.0,
                            sum(
                                left * right
                                for left, right in zip(query_vector, chunk_vector, strict=True)
                            ),
                        ),
                    ),
                    chunk,
                )
                for chunk, chunk_vector in zip(chunks, chunk_vectors, strict=True)
            ),
            key=lambda item: (-item[0], item[1].chunk_id),
        )
        rank = next(
            (
                position
                for position, (_, chunk) in enumerate(ranked, start=1)
                if chunk.document_id == query.expected_document_id
            ),
            None,
        )
        hits = tuple(
            QualityHit(
                chunk_id=chunk.chunk_id,
                document_id=chunk.document_id,
                source=chunk.source,
                score=score,
            )
            for score, chunk in ranked[:3]
        )
        cases.append(
            QualityCase(
                query=query.query,
                expected_document_id=query.expected_document_id,
                rank=rank,
                reciprocal_rank=0.0 if rank is None else 1.0 / rank,
                hits=hits,
            )
        )

    report = QualityReport(
        model_id=embedding.model_id,
        mrr=sum(case.reciprocal_rank for case in cases) / len(cases),
        cases=tuple(cases),
    )
    report_path = repo_root / "services" / "agent-capabilities" / "build" / "reports"
    report_path.mkdir(parents=True, exist_ok=True)
    (report_path / "rag-quality.json").write_text(
        json.dumps(_report_dict(report), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    assert all(case.expected_document_id in case.top_three_document_ids for case in report.cases)
    assert report.mrr >= 0.80
    assert all((repo_root / hit.source).is_file() for case in report.cases for hit in case.hits)


def _load_queries(path: Path) -> list[EvaluationQuery]:
    raw: object = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError("quality evaluation must be a list")
    items = cast(list[object], raw)
    if len(items) < 5:
        raise ValueError("quality evaluation must contain at least five queries")
    queries: list[EvaluationQuery] = []
    for item in items:
        if not isinstance(item, dict):
            raise ValueError("quality query must be an object")
        values = cast(dict[object, object], item)
        query = values.get("query")
        expected = values.get("expectedDocumentId")
        if not isinstance(query, str) or not query.strip():
            raise ValueError("quality query text must be non-empty")
        if not isinstance(expected, str) or not expected.strip():
            raise ValueError("quality expectedDocumentId must be non-empty")
        queries.append(EvaluationQuery(query=query, expected_document_id=expected))
    return queries


def _report_dict(report: QualityReport) -> dict[str, object]:
    return {
        "modelId": report.model_id,
        "mrr": report.mrr,
        "cases": [
            {
                "query": case.query,
                "expectedDocumentId": case.expected_document_id,
                "rank": case.rank,
                "reciprocalRank": case.reciprocal_rank,
                "topThreeDocumentIds": list(case.top_three_document_ids),
                "hits": [
                    {
                        "chunkId": hit.chunk_id,
                        "documentId": hit.document_id,
                        "source": hit.source,
                        "score": hit.score,
                    }
                    for hit in case.hits
                ],
            }
            for case in report.cases
        ],
    }
