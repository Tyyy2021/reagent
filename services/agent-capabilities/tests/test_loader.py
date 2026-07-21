from pathlib import Path

import pytest

from agent_capabilities.rag.loader import load_documents


def test_load_documents_uses_manifest_order_and_normalizes_markdown(tmp_path: Path) -> None:
    root = tmp_path / "incident-ops"
    _write(
        root / "runbooks" / "second.md",
        "preamble\r\n#   Second   title  \r\n\r\nA   paragraph with   spaces.  \r\n",
    )
    _write(
        root / "architecture" / "first.md",
        "# First title\n\nArchitecture facts.\n",
    )
    _write(
        root / "manifest.txt",
        "runbooks/second.md\narchitecture/first.md\n",
    )

    documents = load_documents(root)

    assert [document.source for document in documents] == [
        "knowledge/incident-ops/runbooks/second.md",
        "knowledge/incident-ops/architecture/first.md",
    ]
    assert [document.document_id for document in documents] == [
        "runbooks/second",
        "architecture/first",
    ]
    assert documents[0].title == "Second title"
    assert documents[0].markdown == (
        "preamble\n# Second title\n\nA paragraph with spaces.\n"
    )


@pytest.mark.parametrize(
    "unsafe_path",
    [
        "../outside.md",
        "nested/../outside.md",
        r"runbooks\\outside.md",
        "/absolute.md",
        "runbooks/control\x00.md",
        "runbooks/control\x1f.md",
    ],
)
def test_load_documents_rejects_unsafe_manifest_paths(
    tmp_path: Path, unsafe_path: str
) -> None:
    root = tmp_path / "incident-ops"
    _write(root / "manifest.txt", f"{unsafe_path}\n")

    with pytest.raises(ValueError, match="unsafe manifest path"):
        load_documents(root)


def test_load_documents_rejects_symlink_escape(tmp_path: Path) -> None:
    root = tmp_path / "incident-ops"
    outside = tmp_path / "outside.md"
    _write(outside, "# Outside\n")
    (root / "runbooks").mkdir(parents=True)
    (root / "runbooks" / "escape.md").symlink_to(outside)
    _write(root / "manifest.txt", "runbooks/escape.md\n")

    with pytest.raises(ValueError, match="escapes knowledge root"):
        load_documents(root)


def test_load_documents_requires_a_markdown_h1_title(tmp_path: Path) -> None:
    root = tmp_path / "incident-ops"
    _write(root / "untitled.md", "Only prose.\n")
    _write(root / "manifest.txt", "untitled.md\n")

    with pytest.raises(ValueError, match="first H1"):
        load_documents(root)


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="")
