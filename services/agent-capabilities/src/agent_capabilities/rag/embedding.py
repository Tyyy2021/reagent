import hashlib
import importlib
import math
from collections.abc import Sequence
from pathlib import Path
from typing import Protocol, cast

from agent_capabilities.rag.domain import EmbeddingPort

MINILM_MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
MINILM_DIMENSIONS = 384
_MINILM_CPU_ARTIFACTS = [
    "config.json",
    "config_sentence_transformers.json",
    "modules.json",
    "sentence_bert_config.json",
    "model.safetensors",
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "vocab.txt",
    "1_Pooling/config.json",
]


class _EncodedArray(Protocol):
    def tolist(self) -> list[list[float]]:
        raise NotImplementedError


class _SentenceTransformerModel(Protocol):
    def get_embedding_dimension(self) -> int | None:
        raise NotImplementedError

    def encode(
        self,
        texts: list[str],
        *,
        normalize_embeddings: bool,
        convert_to_numpy: bool,
        show_progress_bar: bool,
    ) -> object:
        raise NotImplementedError


class _SnapshotDownload(Protocol):
    def __call__(
        self,
        *,
        repo_id: str,
        cache_dir: str | None,
        allow_patterns: list[str],
    ) -> str:
        raise NotImplementedError


def l2_normalize(vector: Sequence[float]) -> tuple[float, ...]:
    norm = math.sqrt(sum(value * value for value in vector))
    if not math.isfinite(norm) or norm == 0.0:
        raise ValueError("embedding vector must have a finite non-zero norm")
    normalized = tuple(value / norm for value in vector)
    if not all(math.isfinite(value) for value in normalized):
        raise ValueError("embedding vector must have finite values")
    return normalized


class MiniLmEmbedding(EmbeddingPort):
    def __init__(self, *, cache_dir: Path | None = None) -> None:
        snapshot_path = _snapshot_model(MINILM_MODEL_ID, cache_dir)
        model = _load_sentence_transformer(snapshot_path, "cpu")
        dimensions = model.get_embedding_dimension()
        if dimensions != MINILM_DIMENSIONS:
            raise ValueError(
                f"MiniLM embedding dimension must be {MINILM_DIMENSIONS}, got {dimensions}"
            )
        self._model = model
        self._model_file_checksum = model_artifact_checksum(snapshot_path)

    @property
    def model_id(self) -> str:
        return MINILM_MODEL_ID

    @property
    def dimensions(self) -> int:
        return MINILM_DIMENSIONS

    @property
    def model_file_checksum(self) -> str:
        return self._model_file_checksum

    def embed(self, texts: Sequence[str]) -> list[tuple[float, ...]]:
        if not texts:
            return []
        encoded = self._model.encode(
            list(texts),
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        rows = _as_rows(encoded)
        if len(rows) != len(texts):
            raise ValueError("MiniLM returned an unexpected vector count")

        vectors: list[tuple[float, ...]] = []
        for row in rows:
            vector = tuple(float(value) for value in row)
            if len(vector) != MINILM_DIMENSIONS:
                raise ValueError(
                    f"MiniLM embedding dimension must be {MINILM_DIMENSIONS}, got {len(vector)}"
                )
            if not all(math.isfinite(value) for value in vector):
                raise ValueError("MiniLM embedding values must be finite")
            vectors.append(l2_normalize(vector))
        return vectors


def model_artifact_checksum(root: Path) -> str:
    files = sorted(
        (path for path in root.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(root).as_posix(),
    )
    if not files:
        raise ValueError("model artifact directory must contain files")

    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(relative)
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def _as_rows(encoded: object) -> list[list[float]]:
    if isinstance(encoded, list):
        return cast(list[list[float]], encoded)
    if hasattr(encoded, "tolist"):
        return cast(_EncodedArray, encoded).tolist()
    raise ValueError("MiniLM returned an unsupported embedding representation")


def _snapshot_model(model_id: str, cache_dir: Path | None) -> Path:
    huggingface_hub = importlib.import_module("huggingface_hub")
    download = cast(_SnapshotDownload, getattr(huggingface_hub, "snapshot_download"))
    return Path(
        download(
            repo_id=model_id,
            cache_dir=str(cache_dir) if cache_dir is not None else None,
            allow_patterns=_MINILM_CPU_ARTIFACTS,
        )
    )


def _load_sentence_transformer(
    snapshot_path: Path, device: str
) -> _SentenceTransformerModel:
    from sentence_transformers import SentenceTransformer

    return cast(
        _SentenceTransformerModel,
        SentenceTransformer(str(snapshot_path), device=device),
    )
