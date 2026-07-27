from collections.abc import Generator, Mapping
from contextlib import contextmanager
from re import Pattern, compile as compile_pattern
from typing import TypeAlias

from opentelemetry import context, trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.trace import Span, Tracer
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
)
from starlette.applications import Starlette
from starlette.types import ASGIApp, Receive, Scope, Send

AttributeValue: TypeAlias = str | bool | int | float | list[str]

_INSTRUMENTATION_NAME = "com.reagent.agent-capabilities"
_FORBIDDEN_KEY: Pattern[str] = compile_pattern(
    r"token|key|password|payload|content|evidence|log|output"
)
_ALLOWED_KEYS = {
    "knowledge_base.id",
    "rag.index.version",
    "rag.top_k",
    "rag.hit_count",
    "rag.chunk_ids",
    "mcp.tool",
    "mcp.tool_call_id_hash",
    "ticket.deduplicated",
    "alert.source",
    "alert.external_id",
}
_tracer: Tracer = trace.get_tracer(_INSTRUMENTATION_NAME)
_TRACE_CONTEXT = TraceContextTextMapPropagator()


class ObservabilityRuntime:
    def __init__(
        self,
        *,
        service_name: str,
        service_version: str,
        otlp_endpoint: str,
    ) -> None:
        provider = _provider(service_name, service_version)
        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint))
        )
        self._provider = provider

    @classmethod
    def for_exporter(
        cls,
        *,
        service_name: str,
        service_version: str,
        exporter: SpanExporter,
    ) -> "ObservabilityRuntime":
        runtime = cls.__new__(cls)
        provider = _provider(service_name, service_version)
        provider.add_span_processor(SimpleSpanProcessor(exporter))
        runtime._provider = provider
        return runtime

    def activate(self) -> None:
        global _tracer
        _tracer = self._provider.get_tracer(_INSTRUMENTATION_NAME)

    def instrument(self, app: Starlette) -> None:
        app.add_middleware(_W3cExtractionMiddleware)

    @contextmanager
    def incoming_context(self, headers: Mapping[str, str]) -> Generator[None, None, None]:
        token = context.attach(_TRACE_CONTEXT.extract(headers))
        try:
            yield
        finally:
            context.detach(token)

    def force_flush(self) -> bool:
        return self._provider.force_flush()

    def shutdown(self) -> None:
        self._provider.shutdown()


@contextmanager
def traced(
    name: str,
    attributes: Mapping[str, AttributeValue] | None = None,
) -> Generator[Span, None, None]:
    with _tracer.start_as_current_span(
        name,
        attributes=safe_attributes(attributes or {}),
        record_exception=False,
        set_status_on_exception=False,
    ) as span:
        yield span


def safe_attributes(
    attributes: Mapping[str, AttributeValue],
) -> dict[str, AttributeValue]:
    safe: dict[str, AttributeValue] = {}
    for key, value in attributes.items():
        normalized = key.lower()
        if _FORBIDDEN_KEY.search(normalized):
            raise ValueError(f"forbidden trace attribute key: {key}")
        if key not in _ALLOWED_KEYS:
            raise ValueError(f"trace attribute key is not allowlisted: {key}")
        safe[key] = _bounded(value)
    return safe


def _bounded(value: AttributeValue) -> AttributeValue:
    if isinstance(value, str):
        return value[:256]
    if isinstance(value, list):
        return [item[:256] for item in value]
    return value


def _provider(service_name: str, service_version: str) -> TracerProvider:
    if not service_name or not service_version:
        raise ValueError("service name and version are required")
    return TracerProvider(
        resource=Resource.create(
            {
                "service.name": service_name[:256],
                "service.version": service_version[:256],
            }
        )
    )


class _W3cExtractionMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self._app = app

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        if scope["type"] not in {"http", "websocket"}:
            await self._app(scope, receive, send)
            return
        headers = {
            name.decode("latin-1"): value.decode("latin-1")
            for name, value in scope.get("headers", [])
        }
        token = context.attach(_TRACE_CONTEXT.extract(headers))
        try:
            await self._app(scope, receive, send)
        finally:
            context.detach(token)
