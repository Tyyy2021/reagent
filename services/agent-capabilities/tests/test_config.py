from pathlib import Path

import pytest
from pydantic import ValidationError
from pytest import MonkeyPatch

from agent_capabilities.config import Settings


def explicit_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "env": "test",
        "redis_url": "redis://redis.test:6379/0",
        "mysql_url": "mysql+pymysql://tester:password@mysql.test:3306/fake_ops",
        "knowledge_root": Path("/tmp/agent-capabilities-knowledge"),
        "model_id": "sentence-transformers/test-model",
        "acceptance_enabled": False,
        "chaos_enabled": False,
        "otlp_endpoint": "http://otel.test:4318/v1/traces",
    }
    values.update(overrides)
    return Settings.model_validate(values)


def test_settings_define_explicit_service_configuration() -> None:
    settings = explicit_settings()

    assert settings.env == "test"
    assert settings.redis_url == "redis://redis.test:6379/0"
    assert settings.knowledge_root == Path("/tmp/agent-capabilities-knowledge")
    assert settings.rag_request_max_bytes == 16_384


def test_settings_use_prefixed_environment(monkeypatch: MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_CAPABILITIES_ENV", "environment-value")
    monkeypatch.setenv("AGENT_CAPABILITIES_RAG_REQUEST_MAX_BYTES", "2048")

    settings = Settings()

    assert settings.env == "environment-value"
    assert settings.rag_request_max_bytes == 2048


def test_settings_forbid_unknown_constructor_values() -> None:
    with pytest.raises(ValidationError):
        explicit_settings(unexpected_secret="must-not-be-accepted")
