import importlib
from collections.abc import AsyncGenerator, Callable
from contextlib import asynccontextmanager
from functools import partial
from typing import Protocol, cast

from anyio.to_thread import run_sync as run_sync_in_worker
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from agent_capabilities.config import Settings
from agent_capabilities.rag.api import RagRuntime, RagRuntimeState, rag_routes
from agent_capabilities.rag.embedding import MINILM_MODEL_ID, MiniLmEmbedding
from agent_capabilities.rag.index import RedisClient, RedisKnowledgeIndex
from agent_capabilities.rag.initializer import RagInitializer
from agent_capabilities.rag.service import RagService
from agent_capabilities.readiness import CapabilityReadiness


class _ClosableRedisClient(RedisClient, Protocol):
    def close(self) -> None:
        raise NotImplementedError


class _RedisFromUrl(Protocol):
    def __call__(self, url: str, *, decode_responses: bool) -> _ClosableRedisClient:
        raise NotImplementedError


def create_app(
    settings: Settings | None = None,
    *,
    runtime_factory: Callable[[], RagRuntime] | None = None,
) -> Starlette:
    resolved = settings or Settings()
    readiness_state = [CapabilityReadiness.initial()]
    rag_state = RagRuntimeState()
    selected_factory = runtime_factory
    if selected_factory is None and resolved.env != "test":
        selected_factory = partial(_production_runtime, resolved)

    @asynccontextmanager
    async def lifespan(_: Starlette) -> AsyncGenerator[None, None]:
        runtime: RagRuntime | None = None
        try:
            factory = selected_factory
            if factory is not None:
                runtime = await run_sync_in_worker(factory)
                version = await run_sync_in_worker(runtime.initializer.initialize)
                active_version = await run_sync_in_worker(runtime.index.active_version)
                if active_version != version:
                    raise RuntimeError("initialized RAG version is not active")
                rag_state.runtime = runtime
                readiness_state[0] = readiness_state[0].with_rag_ready()
            yield
        finally:
            rag_state.runtime = None
            if runtime is not None:
                await run_sync_in_worker(runtime.close)

    async def readiness(_: Request) -> JSONResponse:
        return JSONResponse(readiness_state[0].as_dict(), status_code=200)

    return Starlette(
        routes=[
            Route("/internal/readiness", readiness, methods=["GET"]),
            *rag_routes(rag_state, max_bytes=resolved.rag_request_max_bytes),
        ],
        lifespan=lifespan,
    )


def _production_runtime(settings: Settings) -> RagRuntime:
    if settings.model_id != MINILM_MODEL_ID:
        raise ValueError(f"RAG model must be {MINILM_MODEL_ID}")
    redis_client = _redis_from_url()(settings.redis_url, decode_responses=False)
    embedding = MiniLmEmbedding()
    index = RedisKnowledgeIndex(redis_client)
    initializer = RagInitializer(
        knowledge_root=settings.knowledge_root / "incident-ops",
        embedding=embedding,
        index=index,
    )
    service = RagService(embedding=embedding, index=index)
    return RagRuntime(
        initializer=initializer,
        service=service,
        index=index,
        close=redis_client.close,
    )


def _redis_from_url() -> _RedisFromUrl:
    redis_module = importlib.import_module("redis")
    redis_class = getattr(redis_module, "Redis")
    return cast(_RedisFromUrl, getattr(redis_class, "from_url"))
