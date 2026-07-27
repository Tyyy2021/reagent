import sys
from collections.abc import Callable
from pathlib import Path
from types import ModuleType
from typing import cast

import pytest
from pytest import MonkeyPatch

import agent_capabilities.rag.embedding as embedding_module
from agent_capabilities.rag.embedding import MiniLmEmbedding, model_artifact_checksum
from agent_capabilities.rag.initializer import IndexManifest, index_version

_CPU_MODEL_ARTIFACTS = [
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


def test_index_version_changes_for_corpus_chunker_or_model_checksum() -> None:
    baseline = _manifest()
    baseline_version = index_version(baseline)

    assert baseline_version == index_version(_manifest())
    assert baseline_version.startswith("v1-")
    assert len(baseline_version) == 19
    assert baseline_version != index_version(
        _manifest(dataset_checksums=(("runbooks/a.md", "changed"),))
    )
    assert baseline_version != index_version(_manifest(chunker_version="markdown-v2"))
    assert baseline_version != index_version(_manifest(model_file_checksum="changed"))


def test_model_artifact_checksum_uses_sorted_relative_paths_and_bytes(tmp_path: Path) -> None:
    first = tmp_path / "first"
    second = tmp_path / "second"
    _write(first / "z" / "weights.bin", b"weights")
    _write(first / "config.json", b"config")
    _write(second / "config.json", b"config")
    _write(second / "z" / "weights.bin", b"weights")

    assert model_artifact_checksum(first) == model_artifact_checksum(second)

    _write(second / "z" / "weights.bin", b"changed")
    assert model_artifact_checksum(first) != model_artifact_checksum(second)


def test_snapshot_model_downloads_only_fixed_cpu_safetensors_artifacts(
    tmp_path: Path, monkeypatch: MonkeyPatch
) -> None:
    observed: dict[str, object] = {}

    def snapshot_download(
        *, repo_id: str, cache_dir: str | None, allow_patterns: list[str] | None = None
    ) -> str:
        observed["repo_id"] = repo_id
        observed["cache_dir"] = cache_dir
        observed["allow_patterns"] = allow_patterns
        return str(tmp_path / "snapshot")

    fake_hub = ModuleType("huggingface_hub")
    setattr(fake_hub, "snapshot_download", snapshot_download)
    monkeypatch.setitem(sys.modules, "huggingface_hub", fake_hub)

    snapshot_model = cast(
        Callable[[str, Path | None], Path],
        getattr(embedding_module, "_snapshot_model"),
    )
    snapshot = snapshot_model(
        "sentence-transformers/all-MiniLM-L6-v2", tmp_path / "cache"
    )

    assert snapshot == tmp_path / "snapshot"
    assert observed == {
        "repo_id": "sentence-transformers/all-MiniLM-L6-v2",
        "cache_dir": str(tmp_path / "cache"),
        "allow_patterns": _CPU_MODEL_ARTIFACTS,
    }
    assert not any(
        pattern.startswith(("onnx/", "openvino/"))
        or pattern in {"tf_model.h5", "pytorch_model.bin", "rust_model.ot"}
        for pattern in _CPU_MODEL_ARTIFACTS
    )


def test_minilm_loads_fixed_model_on_cpu_and_normalizes(
    tmp_path: Path, monkeypatch: MonkeyPatch
) -> None:
    artifact_root = tmp_path / "snapshot"
    _write(artifact_root / "config.json", b"fixed-model")
    model = _FakeSentenceTransformer()
    observed: dict[str, object] = {}

    def snapshot(model_id: str, cache_dir: Path | None) -> Path:
        observed["model_id"] = model_id
        observed["cache_dir"] = cache_dir
        return artifact_root

    def load(path: Path, device: str) -> _FakeSentenceTransformer:
        observed["path"] = path
        observed["device"] = device
        return model

    monkeypatch.setattr(embedding_module, "_snapshot_model", snapshot)
    monkeypatch.setattr(embedding_module, "_load_sentence_transformer", load)

    adapter = MiniLmEmbedding(cache_dir=tmp_path / "cache")
    vectors = adapter.embed(["checkout", "pool"])

    assert observed == {
        "model_id": "sentence-transformers/all-MiniLM-L6-v2",
        "cache_dir": tmp_path / "cache",
        "path": artifact_root,
        "device": "cpu",
    }
    assert adapter.model_id == "sentence-transformers/all-MiniLM-L6-v2"
    assert adapter.dimensions == 384
    assert adapter.model_file_checksum == model_artifact_checksum(artifact_root)
    assert len(vectors) == 2
    assert all(len(vector) == 384 for vector in vectors)
    assert model.encode_options == {
        "normalize_embeddings": True,
        "convert_to_numpy": True,
        "show_progress_bar": False,
    }


def test_minilm_rejects_non_finite_output(tmp_path: Path, monkeypatch: MonkeyPatch) -> None:
    artifact_root = tmp_path / "snapshot"
    _write(artifact_root / "config.json", b"fixed-model")
    model = _FakeSentenceTransformer(non_finite=True)

    def snapshot(_model_id: str, _cache_dir: Path | None) -> Path:
        return artifact_root

    def load(_path: Path, _device: str) -> _FakeSentenceTransformer:
        return model

    monkeypatch.setattr(
        embedding_module,
        "_snapshot_model",
        snapshot,
    )
    monkeypatch.setattr(
        embedding_module,
        "_load_sentence_transformer",
        load,
    )

    adapter = MiniLmEmbedding(cache_dir=tmp_path / "cache")

    with pytest.raises(ValueError, match="finite"):
        adapter.embed(["checkout"])


def _manifest(
    *,
    dataset_checksums: tuple[tuple[str, str], ...] = (("runbooks/a.md", "a"),),
    chunker_version: str = "markdown-v1",
    model_file_checksum: str = "model-a",
) -> IndexManifest:
    return IndexManifest(
        datasetChecksums=dataset_checksums,
        chunkerVersion=chunker_version,
        modelId="sentence-transformers/all-MiniLM-L6-v2",
        modelFileChecksum=model_file_checksum,
        dimension=384,
        distance="COSINE",
    )


def _write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


class _FakeSentenceTransformer:
    def __init__(self, *, non_finite: bool = False) -> None:
        self._non_finite = non_finite
        self.encode_options: dict[str, object] = {}

    def get_embedding_dimension(self) -> int:
        return 384

    def encode(self, texts: list[str], **options: object) -> list[list[float]]:
        self.encode_options = options
        first_value = float("nan") if self._non_finite else 1.0
        return [[first_value] + ([0.0] * 383) for _ in texts]
