from collections.abc import Callable
from functools import partial
from threading import Lock
from typing import Literal

from anyio.to_thread import run_sync as run_sync_in_worker
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from agent_capabilities.fake_ops.faults import TicketFaultGate
from agent_capabilities.fake_ops.tickets import TicketService

ToolName = Literal["query_metrics", "search_logs"]


class AcceptanceTracker:
    def __init__(self) -> None:
        self._lock = Lock()
        self._attempts = {"query_metrics": 0, "search_logs": 0}

    async def record(self, tool_name: ToolName) -> None:
        with self._lock:
            self._attempts[tool_name] += 1

    async def snapshot(self) -> dict[str, int]:
        with self._lock:
            return dict(self._attempts)


def acceptance_routes(
    require_ticket_service: Callable[[], TicketService],
    tracker: AcceptanceTracker,
    fault_gate: TicketFaultGate,
    *,
    enabled: bool,
) -> list[Route]:
    if not enabled:
        return []

    async def acceptance(request: Request) -> JSONResponse:
        key = request.query_params.get("idempotencyKey")
        if key is not None and (not 1 <= len(key) <= 255):
            return JSONResponse({"code": "INVALID_REQUEST"}, status_code=400)

        try:
            ticket_service = require_ticket_service()
        except RuntimeError:
            return JSONResponse({"code": "FAKE_OPS_NOT_READY"}, status_code=503)
        ticket_snapshot = await run_sync_in_worker(
            partial(ticket_service.acceptance_snapshot, key)
        )
        read_attempts = await tracker.snapshot()
        scoped = key is not None
        query_attempts = 0 if scoped else read_attempts["query_metrics"]
        log_attempts = 0 if scoped else read_attempts["search_logs"]
        create_attempts = ticket_snapshot.attempt_count
        return JSONResponse(
            {
                "contractVersion": 1,
                "scope": "idempotency-key" if scoped else "all",
                "toolAttempts": {
                    "query_metrics": query_attempts,
                    "search_logs": log_attempts,
                    "create_ticket": create_attempts,
                },
                "createTicketAttempts": create_attempts,
                "uniqueTicketCount": ticket_snapshot.unique_count,
                "ticketIds": list(ticket_snapshot.ticket_ids),
                "faultGateState": await fault_gate.acceptance_state(),
            }
        )

    return [Route("/internal/acceptance", acceptance, methods=["GET"])]
