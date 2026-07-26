import pytest
import anyio
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.sdk.trace import ReadableSpan
from pathlib import Path
from starlette.testclient import TestClient

from agent_capabilities.app import create_app
from agent_capabilities.config import Settings
from agent_capabilities.observability import (
    ObservabilityRuntime,
    safe_attributes,
    traced,
)
from agent_capabilities.rag.api import RagRuntime
from agent_capabilities.rag.domain import KnowledgeChunk
from agent_capabilities.rag.service import RagService
from agent_capabilities.fake_ops.acceptance import AcceptanceTracker
from agent_capabilities.fake_ops.faults import DisabledTicketFaultGate
from agent_capabilities.fake_ops.mcp_server import FakeOpsRuntimeState, create_mcp
from agent_capabilities.fake_ops.tickets import TicketResult
from fakes import FakeEmbeddingPort, InMemoryKnowledgeIndex


REQUIRED_SPANS = {
    "rag.embed",
    "rag.index",
    "rag.search",
    "mcp.query_metrics",
    "mcp.search_logs",
    "mcp.create_ticket",
    "ticket.insert_or_read",
}


def test_required_spans_inherit_w3c_parent_and_export_only_safe_attributes() -> None:
    exporter = InMemorySpanExporter()
    runtime = ObservabilityRuntime.for_exporter(
        service_name="agent-capabilities",
        service_version="0.1.0",
        exporter=exporter,
    )
    runtime.activate()
    headers = {
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "tracestate": "vendor=value",
    }

    with runtime.incoming_context(headers):
        for name in sorted(REQUIRED_SPANS):
            with traced(
                name,
                {
                    "knowledge_base.id": "incident-ops",
                    "rag.index.version": "v1-safe",
                    "rag.top_k": 3,
                    "rag.hit_count": 1,
                    "rag.chunk_ids": ["chunk-safe"],
                    "mcp.tool": name.removeprefix("mcp."),
                    "mcp.tool_call_id_hash": "a" * 64,
                    "ticket.deduplicated": False,
                    "alert.source": "fake-alertmanager",
                    "alert.external_id": "alert-safe",
                },
            ):
                pass

    assert runtime.force_flush()
    spans = exporter.get_finished_spans()
    assert {span.name for span in spans} == REQUIRED_SPANS
    assert {format(_trace_id(span), "032x") for span in spans} == {
        "0123456789abcdef0123456789abcdef"
    }
    for span in spans:
        assert span.resource.attributes["service.name"] == "agent-capabilities"
        assert span.resource.attributes["service.version"] == "0.1.0"
        attributes = span.attributes
        assert attributes is not None
        assert all(
            not any(
                forbidden in key.lower()
                for forbidden in (
                    "token",
                    "key",
                    "password",
                    "payload",
                    "content",
                    "evidence",
                    "log",
                    "output",
                )
            )
            for key in attributes
        )
    runtime.shutdown()


@pytest.mark.parametrize(
    "key",
    [
        "api_token",
        "apiKey",
        "password",
        "payload",
        "content",
        "evidence",
        "raw_log",
        "output",
    ],
)
def test_forbidden_attribute_keys_are_rejected(key: str) -> None:
    with pytest.raises(ValueError, match="forbidden"):
        safe_attributes({key: "SECRET_SENTINEL"})


def test_string_attributes_are_clamped_to_256_characters() -> None:
    bounded = safe_attributes(
        {
            "alert.external_id": "x" * 300,
            "rag.chunk_ids": ["y" * 300, "chunk-2"],
        }
    )

    assert bounded["alert.external_id"] == "x" * 256
    assert bounded["rag.chunk_ids"] == ["y" * 256, "chunk-2"]


def test_app_lifecycle_exports_nested_rag_spans_under_incoming_java_trace() -> None:
    exporter = InMemorySpanExporter()
    observability = ObservabilityRuntime.for_exporter(
        service_name="agent-capabilities",
        service_version="0.1.0",
        exporter=exporter,
    )
    embedding = FakeEmbeddingPort()
    index = _ActiveIndex(
        version="v1-observed",
        embedding=embedding,
        chunks=[_chunk("chunk-observed", "checkout pool")],
    )
    rag_runtime = RagRuntime(
        initializer=_Initializer(),
        service=RagService(embedding=embedding, index=index),
        index=index,
        close=lambda: None,
    )
    settings = Settings.model_validate(
        {
            "env": "test",
            "knowledge_root": Path("/unused"),
            "otlp_endpoint": "http://unused.invalid/v1/traces",
        }
    )

    with TestClient(
        create_app(
            settings,
            runtime_factory=lambda: rag_runtime,
            observability_runtime=observability,
        )
    ) as client:
        response = client.post(
            "/internal/rag/search",
            headers={
                "traceparent": (
                    "00-1123456789abcdef0123456789abcdef-0123456789abcdef-01"
                )
            },
            json={
                "contractVersion": 1,
                "knowledgeBaseId": "incident-ops",
                "indexVersion": "v1-observed",
                "query": "checkout pool",
                "topK": 1,
            },
        )

    assert response.status_code == 200
    spans = exporter.get_finished_spans()
    business = [span for span in spans if span.name.startswith("rag.")]
    assert {span.name for span in business} == {"rag.search", "rag.embed", "rag.index"}
    assert {format(_trace_id(span), "032x") for span in business} == {
        "1123456789abcdef0123456789abcdef"
    }
    search = next(span for span in business if span.name == "rag.search")
    search_attributes = search.attributes
    assert search_attributes is not None
    assert search_attributes["rag.hit_count"] == 1
    assert search_attributes["rag.chunk_ids"] == ("chunk-observed",)


def test_real_mcp_tool_functions_emit_the_exact_safe_span_names() -> None:
    exporter = InMemorySpanExporter()
    observability = ObservabilityRuntime.for_exporter(
        service_name="agent-capabilities",
        service_version="0.1.0",
        exporter=exporter,
    )
    observability.activate()
    state = FakeOpsRuntimeState(ticket_service=_FakeTicketService())  # type: ignore[arg-type]
    mcp = create_mcp(state, DisabledTicketFaultGate(), AcceptanceTracker())
    manager = mcp._tool_manager  # pyright: ignore[reportPrivateUsage]
    tools = {tool.name: tool for tool in manager.list_tools()}

    async def invoke() -> None:
        await tools["query_metrics"].run(
            {
                "service": "checkout",
                "start": "2026-07-19T10:00:00Z",
                "end": "2026-07-19T10:15:00Z",
            }
        )
        await tools["search_logs"].run(
            {
                "service": "checkout",
                "start": "2026-07-19T10:00:00Z",
                "end": "2026-07-19T10:15:00Z",
                "query": "SQLTransientConnectionException",
                "limit": 2,
            }
        )
        await tools["create_ticket"].run(
            {
                "idempotency_key": "SECRET_SENTINEL-not-exported",
                "title": "Checkout pool",
                "severity": "critical",
                "evidence": "FULL_LOG_SENTINEL",
            }
        )

    anyio.run(invoke)
    assert observability.force_flush()
    spans = exporter.get_finished_spans()
    assert {span.name for span in spans} == {
        "mcp.query_metrics",
        "mcp.search_logs",
        "mcp.create_ticket",
    }
    exported_attributes: list[object] = []
    for span in spans:
        attributes = span.attributes
        assert attributes is not None
        exported_attributes.append(
            (span.name, {key: value for key, value in attributes.items()})
        )
    exported = repr(exported_attributes)
    assert "SECRET_SENTINEL" not in exported
    assert "FULL_LOG_SENTINEL" not in exported
    create = next(span for span in spans if span.name == "mcp.create_ticket")
    create_attributes = create.attributes
    assert create_attributes is not None
    assert create_attributes["mcp.tool"] == "create_ticket"
    call_id_hash = create_attributes["mcp.tool_call_id_hash"]
    assert isinstance(call_id_hash, str)
    assert len(call_id_hash) == 64
    assert create_attributes["ticket.deduplicated"] is False
    observability.shutdown()


def _chunk(chunk_id: str, content: str) -> KnowledgeChunk:
    return KnowledgeChunk(
        chunk_id=chunk_id,
        document_id=f"documents/{chunk_id}",
        title="Observed",
        section="Pool",
        source="documents/observed.md",
        content=content,
        checksum="0" * 64,
    )


class _ActiveIndex(InMemoryKnowledgeIndex):
    def active_version(self) -> str | None:
        return self._version  # pyright: ignore[reportPrivateUsage]


class _Initializer:
    def initialize(self) -> str:
        return "v1-observed"


class _FakeTicketService:
    def create_or_read(
        self,
        idempotency_key: str,
        title: str,
        severity: str,
        evidence: str,
    ) -> TicketResult:
        return TicketResult(
            ticket_id="OPS-0123456789AB",
            deduplicated=False,
            attempt_count=1,
        )


def _trace_id(span: ReadableSpan) -> int:
    span_context = span.context
    assert span_context is not None
    return span_context.trace_id
