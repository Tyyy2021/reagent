from dataclasses import dataclass
from functools import partial
import hashlib

from anyio.to_thread import run_sync as run_sync_in_worker
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.tools.tool_manager import ToolManager

from agent_capabilities.fake_ops.acceptance import AcceptanceTracker
from agent_capabilities.fake_ops.faults import TicketFaultGate
from agent_capabilities.fake_ops.logs import search_logs as provide_logs
from agent_capabilities.fake_ops.metrics import query_metrics as provide_metrics
from agent_capabilities.fake_ops.tickets import TicketService
from agent_capabilities.observability import safe_attributes, traced


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
        streamable_http_path="/mcp",
    )

    @mcp.tool()
    async def query_metrics(
        service: str,
        start: str,
        end: str,
    ) -> dict[str, object]:
        with traced("mcp.query_metrics", {"mcp.tool": "query_metrics"}):
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
        with traced("mcp.search_logs", {"mcp.tool": "search_logs"}):
            await tracker.record("search_logs")
            return provide_logs(service, start, end, query, limit)

    @mcp.tool()
    async def create_ticket(
        idempotency_key: str,
        title: str,
        severity: str,
        evidence: str,
    ) -> dict[str, object]:
        call_id_hash = hashlib.sha256(idempotency_key.encode("utf-8")).hexdigest()
        with traced(
            "mcp.create_ticket",
            {
                "mcp.tool": "create_ticket",
                "mcp.tool_call_id_hash": call_id_hash,
            },
        ) as span:
            result = await run_sync_in_worker(
                partial(
                    state.require_ticket_service().create_or_read,
                    idempotency_key,
                    title,
                    severity,
                    evidence,
                )
            )
            span.set_attributes(
                safe_attributes({"ticket.deduplicated": result.deduplicated})
            )
            await fault_gate.after_commit(idempotency_key)
            return {
                "ticketId": result.ticket_id,
                "deduplicated": result.deduplicated,
                "attemptCount": result.attempt_count,
            }

    registered_tools = (query_metrics, search_logs, create_ticket)
    del registered_tools
    _forbid_unknown_arguments(
        mcp,
        {"query_metrics", "search_logs", "create_ticket"},
    )
    return mcp


def _forbid_unknown_arguments(
    mcp: FastMCP[None],
    expected_names: set[str],
) -> None:
    manager = getattr(mcp, "_tool_manager", None)
    if not isinstance(manager, ToolManager):
        raise RuntimeError("unsupported FastMCP tool manager shape")
    tools = {tool.name: tool for tool in manager.list_tools()}
    if set(tools) != expected_names:
        raise RuntimeError("unexpected FastMCP tool registration set")

    for tool in tools.values():
        argument_model = tool.fn_metadata.arg_model
        argument_model.model_config["extra"] = "forbid"
        argument_model.model_rebuild(force=True)
        input_schema = argument_model.model_json_schema(by_alias=True)
        if input_schema.get("additionalProperties") is not False:
            raise RuntimeError("FastMCP argument model did not become strict")
        tool.parameters = input_schema
