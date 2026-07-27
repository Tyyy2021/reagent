# syntax=docker/dockerfile:1
FROM ghcr.io/astral-sh/uv:0.8.3 AS uv
FROM python:3.12-slim

COPY --from=uv /uv /uvx /bin/
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/app/.cache/huggingface
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/.cache/huggingface \
    && chown app:app /app /app/.cache /app/.cache/huggingface
USER app
COPY --chown=app:app services/agent-capabilities/pyproject.toml \
    services/agent-capabilities/uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY --chown=app:app services/agent-capabilities/alembic.ini ./
COPY --chown=app:app services/agent-capabilities/migrations ./migrations
COPY --chown=app:app services/agent-capabilities/src ./src
COPY --chown=app:app knowledge ./knowledge
RUN uv sync --locked --no-dev

EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=5m --retries=18 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/internal/readiness', timeout=2).read(1)"]
CMD ["uvicorn", "agent_capabilities.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]
