import json
from collections.abc import Callable
from dataclasses import dataclass
from json import JSONDecodeError
from typing import Protocol, cast

from pydantic import ValidationError
from anyio.to_thread import run_sync as run_sync_in_worker
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from agent_capabilities.rag.models import ActiveIndexResponse, RagSearchRequest
from agent_capabilities.rag.service import (
    KNOWLEDGE_BASE_ID,
    IndexVersionNotFoundError,
    KnowledgeIndexPort,
    RagService,
    UnknownKnowledgeBaseError,
)


class RagInitializerPort(Protocol):
    def initialize(self) -> str:
        raise NotImplementedError


class ActiveKnowledgeIndexPort(KnowledgeIndexPort, Protocol):
    def active_version(self) -> str | None:
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class RagRuntime:
    initializer: RagInitializerPort
    service: RagService
    index: ActiveKnowledgeIndexPort
    close: Callable[[], None]


@dataclass(slots=True)
class RagRuntimeState:
    runtime: RagRuntime | None = None


def rag_routes(state: RagRuntimeState, *, max_bytes: int) -> list[Route]:
    async def search(request: Request) -> JSONResponse:
        body = await bounded_json(request, max_bytes=max_bytes)
        runtime = state.runtime
        if runtime is None:
            return JSONResponse(
                {"code": "RAG_NOT_READY", "message": "active index is unavailable"},
                status_code=503,
            )
        try:
            query = RagSearchRequest.model_validate(body)
            result = await run_sync_in_worker(runtime.service.search, query)
        except ValidationError:
            return JSONResponse(
                {"code": "INVALID_RAG_REQUEST", "message": "request contract is invalid"},
                status_code=422,
            )
        except UnknownKnowledgeBaseError:
            return JSONResponse(
                {"code": "KNOWLEDGE_BASE_NOT_FOUND", "message": "knowledge base is unavailable"},
                status_code=404,
            )
        except IndexVersionNotFoundError:
            return JSONResponse(
                {"code": "INDEX_VERSION_NOT_FOUND", "message": "index version is unavailable"},
                status_code=404,
            )
        return JSONResponse(result.model_dump(by_alias=True))

    async def active_index(request: Request) -> JSONResponse:
        knowledge_base_id = cast(str, request.path_params["knowledge_base_id"])
        if knowledge_base_id != KNOWLEDGE_BASE_ID:
            return JSONResponse(
                {"code": "KNOWLEDGE_BASE_NOT_FOUND", "message": "knowledge base is unavailable"},
                status_code=404,
            )
        runtime = state.runtime
        if runtime is None:
            return JSONResponse(
                {"code": "RAG_NOT_READY", "message": "active index is unavailable"},
                status_code=503,
            )
        version = await run_sync_in_worker(runtime.index.active_version)
        if version is None:
            return JSONResponse(
                {"code": "RAG_NOT_READY", "message": "active index is unavailable"},
                status_code=503,
            )
        response = ActiveIndexResponse(
            contract_version=1,
            knowledge_base_id="incident-ops",
            index_version=version,
            ready=True,
        )
        return JSONResponse(response.model_dump(by_alias=True))

    return [
        Route("/internal/rag/search", search, methods=["POST"]),
        Route(
            "/internal/rag/indexes/{knowledge_base_id}/active",
            active_index,
            methods=["GET"],
        ),
    ]


async def bounded_json(request: Request, max_bytes: int) -> object:
    if max_bytes < 1:
        raise ValueError("max_bytes must be positive")

    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) > max_bytes:
                raise HTTPException(status_code=413, detail="request body is too large")
        except ValueError:
            pass

    body = bytearray()
    async for chunk in request.stream():
        remaining = max_bytes + 1 - len(body)
        if remaining > 0:
            body.extend(chunk[:remaining])
        if len(body) > max_bytes:
            raise HTTPException(status_code=413, detail="request body is too large")

    try:
        return cast(object, json.loads(body))
    except (JSONDecodeError, UnicodeDecodeError) as error:
        raise HTTPException(status_code=400, detail="request body is not valid JSON") from error
