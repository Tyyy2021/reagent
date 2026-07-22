import re
import unicodedata
from pathlib import Path, PurePosixPath

from agent_capabilities.rag.domain import KnowledgeDocument

_H1 = re.compile(r"^#\s+(.+)$")
_SOURCE_ROOT = PurePosixPath("knowledge/incident-ops")


def load_documents(knowledge_root: Path) -> list[KnowledgeDocument]:
    root = knowledge_root.resolve()
    manifest = root / "manifest.txt"
    entries = re.split(r"\r\n|\r|\n", manifest.read_text(encoding="utf-8"))
    documents: list[KnowledgeDocument] = []
    seen_sources: set[str] = set()

    for raw_entry in entries:
        if any(unicodedata.category(character) == "Cc" for character in raw_entry):
            raise ValueError(f"unsafe manifest path: {raw_entry!r}")
        entry = raw_entry.strip()
        if not entry:
            continue
        manifest_source = _validated_source(entry)
        if manifest_source in seen_sources:
            raise ValueError(f"duplicate manifest path: {manifest_source}")
        seen_sources.add(manifest_source)

        path = root.joinpath(*PurePosixPath(manifest_source).parts)
        resolved_path = path.resolve()
        if not resolved_path.is_relative_to(root):
            raise ValueError(f"manifest path escapes knowledge root: {manifest_source}")
        if not resolved_path.is_file():
            raise ValueError(f"manifest document is not a file: {manifest_source}")

        markdown = _normalize_markdown(resolved_path.read_text(encoding="utf-8"))
        source = (_SOURCE_ROOT / manifest_source).as_posix()
        title = _first_h1(markdown, source)
        document_id = PurePosixPath(manifest_source).with_suffix("").as_posix()
        documents.append(
            KnowledgeDocument(
                document_id=document_id,
                title=title,
                source=source,
                markdown=markdown,
            )
        )

    return documents


def _validated_source(entry: str) -> str:
    if (
        not entry
        or entry.startswith("/")
        or "\\" in entry
        or any(ord(character) < 32 or ord(character) == 127 for character in entry)
    ):
        raise ValueError(f"unsafe manifest path: {entry!r}")

    candidate = PurePosixPath(entry)
    if candidate.is_absolute() or ".." in candidate.parts or candidate.suffix != ".md":
        raise ValueError(f"unsafe manifest path: {entry!r}")
    return candidate.as_posix()


def _normalize_markdown(markdown: str) -> str:
    normalized_newlines = markdown.replace("\r\n", "\n").replace("\r", "\n")
    lines: list[str] = []
    for line in normalized_newlines.split("\n"):
        if line.strip():
            lines.append(re.sub(r"[ \t]+", " ", line.strip()))
        else:
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _first_h1(markdown: str, source: str) -> str:
    for line in markdown.splitlines():
        match = _H1.fullmatch(line)
        if match is not None:
            return match.group(1)
    raise ValueError(f"document must contain a first H1 title: {source}")
