import importlib
from collections.abc import AsyncGenerator, Callable
from contextlib import asynccontextmanager
from functools import partial
from typing import Protocol, cast

from anyio.to_thread import run_sync as run_sync_in_worker
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route

from agent_capabilities.config import Settings
from agent_capabilities.database import run_migrations
from agent_capabilities.fake_ops.acceptance import AcceptanceTracker, acceptance_routes
from agent_capabilities.fake_ops.faults import (
    DisabledTicketFaultGate,
    LatchTicketFaultGate,
    chaos_routes,
)
from agent_capabilities.fake_ops.mcp_server import FakeOpsRuntimeState, create_mcp
from agent_capabilities.fake_ops.tickets import TicketService
from agent_capabilities.observability import ObservabilityRuntime
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
    ticket_service_factory: Callable[[], TicketService] | None = None,
    migration_runner: Callable[[str], None] = run_migrations,
    acceptance_tracker: AcceptanceTracker | None = None,
    observability_runtime: ObservabilityRuntime | None = None,
) -> Starlette:
    resolved = settings or Settings()
    readiness_state = [CapabilityReadiness.initial()]
    rag_state = RagRuntimeState()
    selected_factory = runtime_factory
    if selected_factory is None and resolved.env != "test":
        selected_factory = partial(_production_runtime, resolved)
    selected_ticket_factory = ticket_service_factory
    if selected_ticket_factory is None and resolved.env != "test":
        selected_ticket_factory = partial(TicketService.from_url, resolved.mysql_url)

    latch_gate = LatchTicketFaultGate()
    fault_gate = (
        latch_gate if resolved.chaos_enabled else DisabledTicketFaultGate()
    )
    fake_ops_state = FakeOpsRuntimeState()
    tracker = acceptance_tracker or AcceptanceTracker()
    observability = observability_runtime or ObservabilityRuntime(
        service_name="agent-capabilities",
        service_version="0.1.0",
        otlp_endpoint=resolved.otlp_endpoint,
    )
    mcp = create_mcp(fake_ops_state, fault_gate, tracker)
    mcp_app = mcp.streamable_http_app()
    mcp_app.router.redirect_slashes = False

    @asynccontextmanager
    async def lifespan(_: Starlette) -> AsyncGenerator[None, None]:
        runtime: RagRuntime | None = None
        ticket_service: TicketService | None = None
        observability.activate()
        try:
            ticket_factory = selected_ticket_factory
            if ticket_factory is not None:
                await run_sync_in_worker(migration_runner, resolved.mysql_url)
                ticket_service = await run_sync_in_worker(ticket_factory)
                fake_ops_state.ticket_service = ticket_service
                readiness_state[0] = readiness_state[0].with_fake_ops_ready()

            factory = selected_factory
            if factory is not None:
                runtime = await run_sync_in_worker(factory)
                version = await run_sync_in_worker(runtime.initializer.initialize)
                active_version = await run_sync_in_worker(runtime.index.active_version)
                if active_version != version:
                    raise RuntimeError("initialized RAG version is not active")
                rag_state.runtime = runtime
                readiness_state[0] = readiness_state[0].with_rag_ready()
            async with mcp.session_manager.run():
                yield
        finally:
            fake_ops_state.ticket_service = None
            rag_state.runtime = None
            if ticket_service is not None:
                await run_sync_in_worker(ticket_service.close)
            if runtime is not None:
                await run_sync_in_worker(runtime.close)
            observability.shutdown()

    async def readiness(_: Request) -> JSONResponse:
        return JSONResponse(readiness_state[0].as_dict(), status_code=200)

    app = Starlette(
        routes=[
            Route("/internal/readiness", readiness, methods=["GET"]),
            *acceptance_routes(
                fake_ops_state.require_ticket_service,
                tracker,
                fault_gate,
                enabled=resolved.acceptance_enabled,
            ),
            *rag_routes(rag_state, max_bytes=resolved.rag_request_max_bytes),
            *chaos_routes(latch_gate, enabled=resolved.chaos_enabled),
            Mount("/", app=mcp_app),
        ],
        lifespan=lifespan,
    )
    observability.instrument(app)
    return app


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
