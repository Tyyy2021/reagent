from dataclasses import dataclass
from functools import partial

from anyio.to_thread import run_sync as run_sync_in_worker
from mcp.server.fastmcp import FastMCP

from agent_capabilities.fake_ops.acceptance import AcceptanceTracker
from agent_capabilities.fake_ops.faults import TicketFaultGate
from agent_capabilities.fake_ops.logs import search_logs as provide_logs
from agent_capabilities.fake_ops.metrics import query_metrics as provide_metrics
from agent_capabilities.fake_ops.tickets import TicketService


@dataclass(slots=True)
class FakeOpsRuntimeState:
    ticket_service: TicketService | None = None

    def require_ticket_service(self) -> TicketService:
        if self.ticket_service is None:
            raise RuntimeError("Fake Ops ticket persistence is not ready")
        return self.ticket_service


def create_mcp(
    state: FakeOpsRuntimeState,
    fault_gate: TicketFaultGate,
    tracker: AcceptanceTracker,
) -> FastMCP[None]:
    mcp = FastMCP(
        "reagent-fake-ops",
        stateless_http=True,
        json_response=True,
        streamable_http_path="/",
    )

    @mcp.tool()
    async def query_metrics(
        service: str,
        start: str,
        end: str,
    ) -> dict[str, object]:
        await tracker.record("query_metrics")
        return provide_metrics(service, start, end)

    @mcp.tool()
    async def search_logs(
        service: str,
        start: str,
        end: str,
        query: str,
        limit: int,
    ) -> dict[str, object]:
        await tracker.record("search_logs")
        return provide_logs(service, start, end, query, limit)

    @mcp.tool()
    async def create_ticket(
        idempotency_key: str,
        title: str,
        severity: str,
        evidence: str,
    ) -> dict[str, object]:
        result = await run_sync_in_worker(
            partial(
                state.require_ticket_service().create_or_read,
                idempotency_key,
                title,
                severity,
                evidence,
            )
        )
        await fault_gate.after_commit(idempotency_key)
        return {
            "ticketId": result.ticket_id,
            "deduplicated": result.deduplicated,
            "attemptCount": result.attempt_count,
        }

    registered_tools = (query_metrics, search_logs, create_ticket)
    del registered_tools
    return mcp
