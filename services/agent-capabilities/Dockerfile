# syntax=docker/dockerfile:1
FROM ghcr.io/astral-sh/uv:0.8.3 AS uv
FROM python:3.12-slim
COPY --from=uv /uv /uvx /bin/
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy PATH="/app/.venv/bin:$PATH"
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY src ./src
RUN uv sync --locked --no-dev && chown -R app:app /app
USER app
EXPOSE 8090
CMD ["uvicorn", "agent_capabilities.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]
