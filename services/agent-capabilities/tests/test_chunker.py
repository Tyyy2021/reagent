import hashlib

import pytest

from agent_capabilities.rag.chunker import MAX_CHUNK_CODEPOINTS, chunk_documents
from agent_capabilities.rag.domain import KnowledgeDocument


def test_chunker_uses_h2_sections_blank_line_paragraphs_and_stable_ids() -> None:
    document = KnowledgeDocument(
        document_id="runbooks/checkout-db-pool",
        title="Checkout DB Pool",
        source="runbooks/checkout-db-pool.md",
        markdown=(
            "# Checkout DB Pool\n\n"
            "Intro paragraph.\n\n"
            "## Immediate Response\n\n"
            "First line of evidence.\nSecond line of evidence.\n\n"
            "Mitigate carefully.\n"
        ),
    )

    chunks = chunk_documents([document])

    assert [(chunk.section, chunk.content) for chunk in chunks] == [
        ("Checkout DB Pool", "Intro paragraph."),
        (
            "Immediate Response",
            "First line of evidence. Second line of evidence.\n\nMitigate carefully.",
        ),
    ]
    for chunk in chunks:
        expected_checksum = hashlib.sha256(chunk.content.encode("utf-8")).hexdigest()
        assert chunk.checksum == expected_checksum
        section_slug = chunk.section.lower().replace(" ", "-")
        assert chunk.chunk_id == (
            f"{document.document_id}#{section_slug}#{expected_checksum[:12]}"
        )
        assert chunk.title == document.title
        assert chunk.source == document.source


def test_chunker_splits_oversized_paragraphs_at_sentences_then_hard_limits() -> None:
    sentence = "a" * 590 + "."
    oversized_sentence = "z" * 1_350
    document = _document(
        "## Limits\n\n" + sentence + " " + sentence + " " + sentence + "\n\n" + oversized_sentence
    )

    chunks = chunk_documents([document])

    assert len(chunks) >= 4
    assert all(0 < len(chunk.content) <= MAX_CHUNK_CODEPOINTS for chunk in chunks)
    assert chunks[0].content.endswith(".")
    assert chunks[1].content.endswith(".")
    assert "z" * MAX_CHUNK_CODEPOINTS in [chunk.content for chunk in chunks]


def test_chunker_overlap_is_bounded_and_output_is_byte_deterministic() -> None:
    paragraphs = [f"paragraph-{index}-" + (chr(97 + index) * 430) for index in range(6)]
    document = _document("## Capacity\n\n" + "\n\n".join(paragraphs))

    first = chunk_documents([document])
    second = chunk_documents([document])

    assert first == second
    assert len(first) >= 3
    assert all(len(chunk.content) <= MAX_CHUNK_CODEPOINTS for chunk in first)
    for previous, current in zip(first, first[1:], strict=False):
        shared_suffix = max(
            (
                size
                for size in range(1, min(160, len(previous.content), len(current.content)) + 1)
                if previous.content[-size:] == current.content[:size]
            ),
            default=0,
        )
        assert shared_suffix <= 160


def test_chunker_rejects_duplicate_chunk_ids() -> None:
    document = _document("## Duplicate\n\nidentical paragraph")

    with pytest.raises(ValueError, match="duplicate chunk ID"):
        chunk_documents([document, document])


def _document(markdown_tail: str) -> KnowledgeDocument:
    return KnowledgeDocument(
        document_id="incidents/example",
        title="Example",
        source="incidents/example.md",
        markdown=f"# Example\n\n{markdown_tail}\n",
    )
