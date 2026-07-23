from typing import Protocol, cast

import anyio
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route


class TicketFaultGate(Protocol):
    async def after_commit(self, idempotency_key: str) -> None:
        raise NotImplementedError

    async def acceptance_state(self) -> str:
        raise NotImplementedError


class DisabledTicketFaultGate:
    async def after_commit(self, idempotency_key: str) -> None:
        del idempotency_key

    async def acceptance_state(self) -> str:
        return "disabled"


class LatchTicketFaultGate:
    def __init__(self) -> None:
        self._lock = anyio.Lock()
        self._armed_key: str | None = None
        self._blocked = False
        self._blocked_event: anyio.Event | None = None
        self._release_event: anyio.Event | None = None
        self._acceptance_state = "idle"

    async def arm(self, idempotency_key: str) -> None:
        async with self._lock:
            self._armed_key = idempotency_key
            self._blocked = False
            self._blocked_event = anyio.Event()
            self._release_event = anyio.Event()
            self._acceptance_state = "armed"

    async def after_commit(self, idempotency_key: str) -> None:
        async with self._lock:
            if idempotency_key != self._armed_key:
                return
            blocked_event = self._blocked_event
            release_event = self._release_event
            if blocked_event is None or release_event is None:
                return
            self._blocked = True
            self._acceptance_state = "blocked"
            blocked_event.set()

        await release_event.wait()

        async with self._lock:
            if self._release_event is release_event:
                self._armed_key = None
                self._blocked = False
                self._blocked_event = None
                self._release_event = None

    async def wait_until_blocked(self) -> None:
        async with self._lock:
            blocked_event = self._blocked_event
            if blocked_event is None:
                raise RuntimeError("fault gate is not armed")
        await blocked_event.wait()

    async def release(self) -> bool:
        async with self._lock:
            release_event = self._release_event
            if release_event is None:
                return False
            release_event.set()
            self._acceptance_state = "released"
            return True

    async def state(self) -> dict[str, bool]:
        async with self._lock:
            return {
                "armed": self._armed_key is not None,
                "blocked": self._blocked,
            }

    async def acceptance_state(self) -> str:
        async with self._lock:
            return self._acceptance_state


def chaos_routes(
    gate: LatchTicketFaultGate,
    *,
    enabled: bool,
) -> list[Route]:
    if not enabled:
        return []

    async def arm(request: Request) -> JSONResponse:
        try:
            body = cast(object, await request.json())
        except ValueError:
            return JSONResponse({"code": "INVALID_REQUEST"}, status_code=400)
        if not isinstance(body, dict):
            return JSONResponse({"code": "INVALID_REQUEST"}, status_code=400)
        values = cast(dict[str, object], body)
        key = values.get("idempotencyKey")
        if not isinstance(key, str) or not 1 <= len(key) <= 255:
            return JSONResponse({"code": "INVALID_REQUEST"}, status_code=400)
        await gate.arm(key)
        return JSONResponse(await gate.state())

    async def status(_: Request) -> JSONResponse:
        return JSONResponse(await gate.state())

    async def release(_: Request) -> JSONResponse:
        return JSONResponse({"released": await gate.release()})

    return [
        Route(
            "/internal/chaos/ticket-after-commit/arm",
            arm,
            methods=["POST"],
        ),
        Route(
            "/internal/chaos/ticket-after-commit/status",
            status,
            methods=["GET"],
        ),
        Route(
            "/internal/chaos/ticket-after-commit/release",
            release,
            methods=["POST"],
        ),
    ]
