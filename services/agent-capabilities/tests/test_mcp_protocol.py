import asyncio
import json
import socket
from types import TracebackType
from collections.abc import Generator
from pathlib import Path
from typing import cast

import anyio
import httpx
import pytest
import uvicorn
from anyio.abc import TaskGroup
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from starlette.types import ASGIApp
from testcontainers.mysql import MySqlContainer  # pyright: ignore[reportMissingTypeStubs]

from agent_capabilities.app import create_app
from agent_capabilities.config import Settings
from agent_capabilities.fake_ops.acceptance import AcceptanceTracker
from agent_capabilities.fake_ops.tickets import TicketService

pytestmark = pytest.mark.integration

START = "2026-07-19T10:00:00Z"
END = "2026-07-19T10:15:00Z"


def test_exact_mcp_initialize_does_not_redirect() -> None:
    async def exercise() -> None:
        configured = Settings.model_validate(
            {
                "env": "test",
                "acceptance_enabled": False,
                "chaos_enabled": False,
                "knowledge_root": Path("/unused"),
            }
        )
        app = create_app(configured)
        payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "route-regression", "version": "1.0.0"},
            },
        }

        async with _serve(app) as server_url:
            async with httpx.AsyncClient(follow_redirects=False) as client:
                response = await client.post(
                    server_url,
                    headers={
                        "Accept": "application/json, text/event-stream",
                        "Content-Type": "application/json",
                        "MCP-Protocol-Version": "2025-06-18",
                    },
                    json=payload,
                )
                nested_response = await client.post(
                    f"{server_url}/mcp",
                    headers={
                        "Accept": "application/json, text/event-stream",
                        "Content-Type": "application/json",
                        "MCP-Protocol-Version": "2025-06-18",
                    },
                    json=payload,
                )

        assert response.status_code < 300
        assert response.headers.get("location") is None
        assert response.content
        assert response.json()["result"]["protocolVersion"] == "2025-06-18"
        assert nested_response.status_code >= 400

    anyio.run(exercise)


@pytest.fixture(scope="module")
def mysql_url() -> Generator[str]:
    with MySqlContainer(
        "mysql:8.0",
        dialect="pymysql",
        username="fake_ops_app",
        password="fake-ops-test",
        root_password="fake-ops-root",
        dbname="fake_ops",
    ) as mysql:
        yield mysql.get_connection_url()


def test_streamable_http_initialize_list_and_call_exact_tools(mysql_url: str) -> None:
    async def exercise() -> None:
        configured = Settings.model_validate(
            {
                "env": "test",
                "mysql_url": mysql_url,
                "acceptance_enabled": False,
                "chaos_enabled": False,
                "knowledge_root": Path("/unused"),
            }
        )
        app = create_app(
            configured,
            ticket_service_factory=lambda: TicketService.from_url(
                mysql_url, pool_size=8
            ),
        )

        async with _serve(app) as server_url:
            assert server_url.endswith("/mcp")
            async with streamable_http_client(server_url) as streams:
                read_stream, write_stream, _ = streams
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    listed = await session.list_tools()

                    assert {tool.name for tool in listed.tools} == {
                        "query_metrics",
                        "search_logs",
                        "create_ticket",
                    }
                    create_schema = next(
                        tool.inputSchema
                        for tool in listed.tools
                        if tool.name == "create_ticket"
                    )
                    assert "idempotency_key" in create_schema["required"]

                    metrics = await session.call_tool(
                        "query_metrics",
                        {"service": "checkout", "start": START, "end": END},
                    )
                    logs = await session.call_tool(
                        "search_logs",
                        {
                            "service": "checkout",
                            "start": START,
                            "end": END,
                            "query": "SQLTransientConnectionException",
                            "limit": 2,
                        },
                    )
                    first, replay = await asyncio.gather(
                        session.call_tool(
                            "create_ticket",
                            {
                                "idempotency_key": "mcp-concurrent-tool-call",
                                "title": "Checkout connection pool exhausted",
                                "severity": "critical",
                                "evidence": "metrics and timeout logs corroborate saturation",
                            },
                        ),
                        session.call_tool(
                            "create_ticket",
                            {
                                "idempotency_key": "mcp-concurrent-tool-call",
                                "title": "Checkout connection pool exhausted",
                                "severity": "critical",
                                "evidence": "metrics and timeout logs corroborate saturation",
                            },
                        ),
                    )

            metrics_json = _json_content(metrics)
            logs_json = _json_content(logs)
            ticket_results = [_json_content(first), _json_content(replay)]
            assert metrics_json["errorRatePercent"] == 14.2
            assert len(logs_json["entries"]) == 2  # type: ignore[arg-type]
            assert {result["ticketId"] for result in ticket_results} == {
                ticket_results[0]["ticketId"]
            }
            attempt_counts = [result["attemptCount"] for result in ticket_results]
            deduplicated = [result["deduplicated"] for result in ticket_results]
            assert all(isinstance(value, int) for value in attempt_counts)
            assert all(isinstance(value, bool) for value in deduplicated)
            assert sorted(cast(list[int], attempt_counts)) == [1, 2]
            assert sorted(cast(list[bool], deduplicated)) == [False, True]

    anyio.run(exercise)


def test_unknown_arguments_are_rejected_without_side_effects(mysql_url: str) -> None:
    async def exercise() -> None:
        configured = Settings.model_validate(
            {
                "env": "test",
                "mysql_url": mysql_url,
                "acceptance_enabled": False,
                "chaos_enabled": False,
                "knowledge_root": Path("/unused"),
            }
        )
        tracker = AcceptanceTracker()
        ticket_service = TicketService.from_url(mysql_url)
        app = create_app(
            configured,
            ticket_service_factory=lambda: ticket_service,
            acceptance_tracker=tracker,
        )

        async with _serve(app) as server_url:
            async with streamable_http_client(server_url) as streams:
                read_stream, write_stream, _ = streams
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    listed = await session.list_tools()
                    schemas = {
                        tool.name: tool.inputSchema for tool in listed.tools
                    }
                    metrics = await session.call_tool(
                        "query_metrics",
                        {
                            "service": "checkout",
                            "start": START,
                            "end": END,
                            "unexpected": "must-fail",
                        },
                    )
                    logs = await session.call_tool(
                        "search_logs",
                        {
                            "service": "checkout",
                            "start": START,
                            "end": END,
                            "query": "SQLTransientConnectionException",
                            "limit": 1,
                            "unexpected": "must-fail",
                        },
                    )
                    ticket = await session.call_tool(
                        "create_ticket",
                        {
                            "idempotency_key": "unknown-extra-tool-call",
                            "title": "Must not be created",
                            "severity": "critical",
                            "evidence": "unknown argument must reject this call",
                            "unexpected": "must-fail",
                        },
                    )

            tracker_attempts = await tracker.snapshot()
            ticket_snapshot = ticket_service.acceptance_snapshot(
                "unknown-extra-tool-call"
            )

        assert {
            "additionalProperties": {
                name: schema.get("additionalProperties")
                for name, schema in schemas.items()
            },
            "isError": {
                "query_metrics": metrics.isError,
                "search_logs": logs.isError,
                "create_ticket": ticket.isError,
            },
            "trackerAttempts": tracker_attempts,
            "ticketAttempts": ticket_snapshot.attempt_count,
            "uniqueTickets": ticket_snapshot.unique_count,
            "ticketIds": ticket_snapshot.ticket_ids,
        } == {
            "additionalProperties": {
                "query_metrics": False,
                "search_logs": False,
                "create_ticket": False,
            },
            "isError": {
                "query_metrics": True,
                "search_logs": True,
                "create_ticket": True,
            },
            "trackerAttempts": {"query_metrics": 0, "search_logs": 0},
            "ticketAttempts": 0,
            "uniqueTickets": 0,
            "ticketIds": (),
        }

    anyio.run(exercise)


def _json_content(result: object) -> dict[str, object]:
    content = getattr(result, "content")
    assert len(content) == 1
    text_content = content[0]
    return cast(dict[str, object], json.loads(text_content.text))


class _serve:
    def __init__(self, app: ASGIApp) -> None:
        self._socket = socket.socket()
        self._socket.bind(("127.0.0.1", 0))
        self._socket.listen(128)
        self._socket.setblocking(False)
        self._port = int(self._socket.getsockname()[1])
        self._server = uvicorn.Server(
            uvicorn.Config(
                app,
                host="127.0.0.1",
                port=self._port,
                log_level="critical",
                lifespan="on",
            )
        )
        self._tasks: TaskGroup | None = None

    async def __aenter__(self) -> str:
        tasks = anyio.create_task_group()
        self._tasks = tasks
        await tasks.__aenter__()
        tasks.start_soon(self._run)
        with anyio.fail_after(10):
            while not self._server.started:
                await anyio.sleep(0.01)
        return f"http://127.0.0.1:{self._port}/mcp"

    async def __aexit__(
        self,
        exception_type: type[BaseException] | None,
        exception: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self._server.should_exit = True
        tasks = self._tasks
        assert tasks is not None
        await tasks.__aexit__(exception_type, exception, traceback)

    async def _run(self) -> None:
        await self._server.serve(sockets=[self._socket])
