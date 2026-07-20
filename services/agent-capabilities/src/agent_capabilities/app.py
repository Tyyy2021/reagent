import json
from json import JSONDecodeError
from typing import cast

from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from agent_capabilities.config import Settings
from agent_capabilities.readiness import CapabilityReadiness


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


def create_app(settings: Settings | None = None) -> Starlette:
    resolved = settings or Settings()
    readiness_state = CapabilityReadiness.initial()

    async def readiness(_: Request) -> JSONResponse:
        return JSONResponse(readiness_state.as_dict(), status_code=200)

    async def rag_not_ready(request: Request) -> JSONResponse:
        await bounded_json(request, max_bytes=resolved.rag_request_max_bytes)
        return JSONResponse(
            {"code": "RAG_NOT_READY", "message": "active index is unavailable"},
            status_code=503,
        )

    return Starlette(
        routes=[
            Route("/internal/readiness", readiness, methods=["GET"]),
            Route("/internal/rag/search", rag_not_ready, methods=["POST"]),
        ]
    )
