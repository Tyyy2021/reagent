from pathlib import Path

import pytest
from starlette.applications import Starlette
from starlette.testclient import TestClient

from agent_capabilities.app import create_app
from agent_capabilities.config import Settings
from agent_capabilities.fake_ops.tickets import TicketService
from agent_capabilities.observability import ObservabilityRuntime
from agent_capabilities.rag.api import RagRuntime
from agent_capabilities.rag.service import RagService
from fakes import FakeEmbeddingPort, InMemoryKnowledgeIndex


def settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "env": "test",
        "redis_url": "redis://sensitive-user:sensitive-password@redis.test:6379/0",
        "mysql_url": "mysql+pymysql://sensitive-user:sensitive-password@mysql.test/fake_ops",
        "knowledge_root": Path("/private/knowledge-root"),
        "model_id": "private-model-id",
        "acceptance_enabled": False,
        "chaos_enabled": False,
        "otlp_endpoint": "http://private-otel.test:4318/v1/traces",
    }
    values.update(overrides)
    return Settings.model_validate(values)


def test_readiness_is_healthy_but_fail_closed() -> None:
    configured = settings()
    with TestClient(create_app(configured)) as client:
        response = client.get("/internal/readiness")

    assert response.status_code == 200
    assert response.json() == {
        "ready": False,
        "service": "agent-capabilities",
        "version": "0.1.0",
        "rag": {"ready": False, "reason": "index-not-initialized"},
        "fakeOps": {"ready": False, "reason": "not-configured"},
    }
    assert_settings_are_not_echoed(response.text, configured)


def test_rag_search_returns_explicit_not_ready_without_echoing_settings() -> None:
    configured = settings()
    with TestClient(create_app(configured)) as client:
        response = client.post("/internal/rag/search", json={"query": "checkout"})

    assert response.status_code == 503
    assert response.json() == {
        "code": "RAG_NOT_READY",
        "message": "active index is unavailable",
    }
    assert_settings_are_not_echoed(response.text, configured)


def test_rag_search_rejects_oversized_body_before_json_parsing() -> None:
    configured = settings(rag_request_max_bytes=32)
    invalid_json_larger_than_limit = b"{" + (b"x" * 32)

    with TestClient(create_app(configured)) as client:
        response = client.post(
            "/internal/rag/search",
            content=invalid_json_larger_than_limit,
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 413


def test_lifespan_attempts_every_cleanup_when_ticket_close_fails() -> None:
    events: list[str] = []
    embedding = FakeEmbeddingPort()
    index = _ActiveIndex()
    runtime = RagRuntime(
        initializer=_ReadyInitializer(),
        service=RagService(embedding=embedding, index=index),
        index=index,
        close=lambda: events.append("rag.close"),
    )
    ticket = _FailingTicketService(events)
    observability = _RecordingObservability(events)

    with pytest.raises(RuntimeError, match="ticket close failed"):
        with TestClient(
            create_app(
                settings(),
                runtime_factory=lambda: runtime,
                ticket_service_factory=lambda: ticket,
                migration_runner=lambda _: None,
                observability_runtime=observability,
            )
        ):
            pass

    assert events == [
        "ticket.close",
        "rag.close",
        "observability.shutdown",
    ]


def assert_settings_are_not_echoed(body: str, configured: Settings) -> None:
    for value in (
        configured.redis_url,
        configured.mysql_url,
        str(configured.knowledge_root),
        configured.model_id,
        configured.otlp_endpoint,
    ):
        assert value not in body


class _ReadyInitializer:
    def initialize(self) -> str:
        return "v1-cleanup"


class _ActiveIndex(InMemoryKnowledgeIndex):
    def __init__(self) -> None:
        super().__init__(
            version="v1-cleanup",
            embedding=FakeEmbeddingPort(),
            chunks=[],
        )

    def active_version(self) -> str | None:
        return "v1-cleanup"


class _FailingTicketService(TicketService):
    def __init__(self, events: list[str]) -> None:
        self._events = events

    def close(self) -> None:
        self._events.append("ticket.close")
        raise RuntimeError("ticket close failed")


class _RecordingObservability(ObservabilityRuntime):
    def __init__(self, events: list[str]) -> None:
        self._events = events

    def activate(self) -> None:
        pass

    def instrument(self, app: Starlette) -> None:
        del app

    def shutdown(self) -> None:
        self._events.append("observability.shutdown")
