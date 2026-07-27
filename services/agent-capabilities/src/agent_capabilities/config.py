from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENT_CAPABILITIES_", extra="forbid")

    env: str = "container"
    redis_url: str = "redis://redis:6379/0"
    mysql_url: str = "mysql+pymysql://fake_ops_app:fake-ops-local-only@mysql:3306/fake_ops"
    knowledge_root: Path = Path("/app/knowledge")
    model_id: str = "sentence-transformers/all-MiniLM-L6-v2"
    acceptance_enabled: bool = False
    chaos_enabled: bool = False
    otlp_endpoint: str = "http://otel-collector:4318/v1/traces"
    rag_request_max_bytes: int = 16_384
