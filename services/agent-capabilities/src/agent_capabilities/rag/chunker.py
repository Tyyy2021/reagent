import hashlib
import re
from collections.abc import Iterable, Sequence

from agent_capabilities.rag.domain import KnowledgeChunk, KnowledgeDocument

MAX_CHUNK_CODEPOINTS = 1_200
MAX_OVERLAP_CODEPOINTS = 160
CHUNKER_VERSION = "markdown-v1"

_H1 = re.compile(r"^#\s+(.+)$")
_H2 = re.compile(r"^##\s+(.+)$")
_SENTENCE = re.compile(r".+?(?:[.?!](?=\s|$)|$)")


def chunk_documents(documents: Sequence[KnowledgeDocument]) -> list[KnowledgeChunk]:
    chunks: list[KnowledgeChunk] = []
    chunk_ids: set[str] = set()
    for document in documents:
        for section, paragraphs in _sections(document):
            for content in _chunk_section(paragraphs):
                checksum = hashlib.sha256(content.encode("utf-8")).hexdigest()
                chunk_id = f"{document.document_id}#{_slug(section)}#{checksum[:12]}"
                if chunk_id in chunk_ids:
                    raise ValueError(f"duplicate chunk ID: {chunk_id}")
                chunk_ids.add(chunk_id)
                chunks.append(
                    KnowledgeChunk(
                        chunk_id=chunk_id,
                        document_id=document.document_id,
                        title=document.title,
                        section=section,
                        source=document.source,
                        content=content,
                        checksum=checksum,
                    )
                )
    return chunks


def _sections(document: KnowledgeDocument) -> list[tuple[str, list[str]]]:
    sections: list[tuple[str, list[str]]] = []
    section = document.title
    paragraphs: list[str] = []
    paragraph_lines: list[str] = []

    def flush_paragraph() -> None:
        if paragraph_lines:
            paragraphs.append(" ".join(paragraph_lines))
            paragraph_lines.clear()

    def flush_section() -> None:
        flush_paragraph()
        if paragraphs:
            sections.append((section, list(paragraphs)))
            paragraphs.clear()

    for line in document.markdown.splitlines():
        if _H1.fullmatch(line):
            continue
        h2 = _H2.fullmatch(line)
        if h2 is not None:
            flush_section()
            section = h2.group(1).strip()
        elif not line.strip():
            flush_paragraph()
        else:
            paragraph_lines.append(" ".join(line.split()))
    flush_section()
    return sections


def _chunk_section(paragraphs: Sequence[str]) -> list[str]:
    results: list[str] = []
    current = ""
    for paragraph in paragraphs:
        pieces = list(_split_paragraph(paragraph))
        for index, piece in enumerate(pieces):
            separator = "\n\n" if index == 0 else " "
            candidate = piece if not current else current + separator + piece
            if len(candidate) <= MAX_CHUNK_CODEPOINTS:
                current = candidate
                continue

            if current:
                results.append(current)
            current = _with_overlap(results[-1] if results else "", piece)

    if current:
        results.append(current)
    return results


def _split_paragraph(paragraph: str) -> Iterable[str]:
    normalized = " ".join(paragraph.split())
    if len(normalized) <= MAX_CHUNK_CODEPOINTS:
        yield normalized
        return

    sentences = [match.group(0).strip() for match in _SENTENCE.finditer(normalized)]
    sentence_group = ""
    for sentence in sentences:
        if len(sentence) > MAX_CHUNK_CODEPOINTS:
            if sentence_group:
                yield sentence_group
                sentence_group = ""
            yield from _hard_split(sentence)
            continue
        candidate = sentence if not sentence_group else f"{sentence_group} {sentence}"
        if len(candidate) <= MAX_CHUNK_CODEPOINTS:
            sentence_group = candidate
        else:
            yield sentence_group
            sentence_group = sentence
    if sentence_group:
        yield sentence_group


def _hard_split(content: str) -> Iterable[str]:
    for start in range(0, len(content), MAX_CHUNK_CODEPOINTS):
        yield content[start : start + MAX_CHUNK_CODEPOINTS]


def _with_overlap(previous: str, piece: str) -> str:
    available = MAX_CHUNK_CODEPOINTS - len(piece) - 1
    overlap_size = min(MAX_OVERLAP_CODEPOINTS, max(0, available), len(previous))
    if overlap_size == 0:
        return piece
    return f"{previous[-overlap_size:]} {piece}"


def _slug(section: str) -> str:
    slug = re.sub(r"[^\w]+", "-", section.casefold()).strip("-").replace("_", "-")
    if not slug:
        raise ValueError("section slug must not be empty")
    return slug
