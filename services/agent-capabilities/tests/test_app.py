from pathlib import Path

from starlette.testclient import TestClient

from agent_capabilities.app import create_app
from agent_capabilities.config import Settings


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


def assert_settings_are_not_echoed(body: str, configured: Settings) -> None:
    for value in (
        configured.redis_url,
        configured.mysql_url,
        str(configured.knowledge_root),
        configured.model_id,
        configured.otlp_endpoint,
    ):
        assert value not in body
