import os
import subprocess
from collections.abc import Generator
from functools import partial
from pathlib import Path

import anyio
import httpx
import pytest
from anyio.to_thread import run_sync as run_sync_in_worker
from sqlalchemy import create_engine, text
from starlette.applications import Starlette
from starlette.testclient import TestClient
from testcontainers.mysql import MySqlContainer  # pyright: ignore[reportMissingTypeStubs]

from agent_capabilities.fake_ops.faults import (
    DisabledTicketFaultGate,
    LatchTicketFaultGate,
    chaos_routes,
)
from agent_capabilities.fake_ops.tickets import TicketResult, TicketService

_SERVICE_ROOT = Path(__file__).resolve().parents[1]


def test_disabled_gate_never_blocks() -> None:
    async def exercise() -> None:
        with anyio.fail_after(1):
            await DisabledTicketFaultGate().after_commit("tool-call")

    anyio.run(exercise)


def test_chaos_routes_are_absent_when_disabled() -> None:
    app = Starlette(routes=chaos_routes(LatchTicketFaultGate(), enabled=False))

    with TestClient(app) as client:
        assert client.post(
            "/internal/chaos/ticket-after-commit/arm",
            json={"idempotencyKey": "tool-call"},
        ).status_code == 404
        assert client.get(
            "/internal/chaos/ticket-after-commit/status"
        ).status_code == 404
        assert client.post(
            "/internal/chaos/ticket-after-commit/release"
        ).status_code == 404


def test_enabled_chaos_routes_arm_report_and_release_without_echoing_key() -> None:
    gate = LatchTicketFaultGate()
    app = Starlette(routes=chaos_routes(gate, enabled=True))

    with TestClient(app) as client:
        armed = client.post(
            "/internal/chaos/ticket-after-commit/arm",
            json={"idempotencyKey": "sensitive-tool-call"},
        )
        status = client.get("/internal/chaos/ticket-after-commit/status")
        released = client.post("/internal/chaos/ticket-after-commit/release")

    assert armed.status_code == 200
    assert armed.json() == {"armed": True, "blocked": False}
    assert status.json() == {"armed": True, "blocked": False}
    assert released.json() == {"released": True}
    assert "sensitive-tool-call" not in (armed.text + status.text + released.text)


def test_same_key_rearm_while_blocked_preserves_original_waiter() -> None:
    async def exercise() -> None:
        gate = LatchTicketFaultGate()
        completed = anyio.Event()
        await gate.arm("same-key")

        async def wait_after_commit() -> None:
            await gate.after_commit("same-key")
            completed.set()

        with anyio.fail_after(1):
            async with anyio.create_task_group() as tasks:
                tasks.start_soon(wait_after_commit)
                await gate.wait_until_blocked()
                await gate.arm("same-key")
                assert await gate.release() is True
                await completed.wait()

        assert await gate.state() == {"armed": False, "blocked": False}
        assert await gate.acceptance_state() == "released"

    anyio.run(exercise)


def test_different_key_rearm_returns_conflict_and_preserves_waiter() -> None:
    async def exercise() -> None:
        first_key = "first-sensitive-key"
        second_key = "second-sensitive-key"
        gate = LatchTicketFaultGate()
        app = Starlette(routes=chaos_routes(gate, enabled=True))
        completed = anyio.Event()

        async def wait_after_commit() -> None:
            await gate.after_commit(first_key)
            completed.set()

        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://test",
        ) as client:
            conflict: httpx.Response | None = None
            status: httpx.Response | None = None
            released: httpx.Response | None = None
            armed = await client.post(
                "/internal/chaos/ticket-after-commit/arm",
                json={"idempotencyKey": first_key},
            )
            assert armed.status_code == 200
            with anyio.fail_after(1):
                async with anyio.create_task_group() as tasks:
                    tasks.start_soon(wait_after_commit)
                    await gate.wait_until_blocked()
                    conflict = await client.post(
                        "/internal/chaos/ticket-after-commit/arm",
                        json={"idempotencyKey": second_key},
                    )
                    status = await client.get(
                        "/internal/chaos/ticket-after-commit/status"
                    )
                    released = await client.post(
                        "/internal/chaos/ticket-after-commit/release"
                    )
                    await completed.wait()

        assert conflict is not None
        assert status is not None
        assert released is not None
        assert conflict.status_code == 409
        assert conflict.json() == {"code": "GATE_CONFLICT"}
        assert status.json() == {"armed": True, "blocked": True}
        assert released.json() == {"released": True}
        all_responses = armed.text + conflict.text + status.text + released.text
        assert first_key not in all_responses
        assert second_key not in all_responses

    anyio.run(exercise)


def test_release_before_arrival_disarms_without_later_blocked_state() -> None:
    async def exercise() -> None:
        gate = LatchTicketFaultGate()
        await gate.arm("released-before-arrival")

        released = await gate.release()
        state_after_release = await gate.state()
        acceptance_after_release = await gate.acceptance_state()
        with anyio.fail_after(1):
            await gate.after_commit("released-before-arrival")
        final_state = await gate.state()
        final_acceptance = await gate.acceptance_state()
        second_release = await gate.release()

        assert {
            "released": released,
            "stateAfterRelease": state_after_release,
            "acceptanceAfterRelease": acceptance_after_release,
            "finalState": final_state,
            "finalAcceptance": final_acceptance,
            "secondRelease": second_release,
        } == {
            "released": True,
            "stateAfterRelease": {"armed": False, "blocked": False},
            "acceptanceAfterRelease": "released",
            "finalState": {"armed": False, "blocked": False},
            "finalAcceptance": "released",
            "secondRelease": False,
        }

    anyio.run(exercise)


@pytest.mark.parametrize("idempotency_key", ["", "k" * 256, 42])
def test_chaos_arm_rejects_invalid_selected_key(idempotency_key: object) -> None:
    app = Starlette(routes=chaos_routes(LatchTicketFaultGate(), enabled=True))

    with TestClient(app) as client:
        response = client.post(
            "/internal/chaos/ticket-after-commit/arm",
            json={"idempotencyKey": idempotency_key},
        )

    assert response.status_code == 400
    assert "idempotencyKey" not in response.text


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
        url = mysql.get_connection_url()
        subprocess.run(
            ["alembic", "upgrade", "head"],
            cwd=_SERVICE_ROOT,
            env={
                **os.environ,
                "AGENT_CAPABILITIES_MYSQL_URL": url,
                "PYTHONDONTWRITEBYTECODE": "1",
            },
            check=True,
            capture_output=True,
            text=True,
        )
        yield url


@pytest.mark.integration
def test_latch_blocks_only_selected_key_and_only_after_ticket_commit(
    mysql_url: str,
) -> None:
    async def exercise() -> None:
        service = TicketService.from_url(mysql_url)
        gate = LatchTicketFaultGate()
        await gate.arm("tool-call-selected")
        selected_result: list[TicketResult] = []

        async def create_selected() -> None:
            result = await run_sync_in_worker(
                partial(
                    service.create_or_read,
                    "tool-call-selected",
                    "Checkout pool exhausted",
                    "critical",
                    "corroborated timeout evidence",
                )
            )
            await gate.after_commit("tool-call-selected")
            selected_result.append(result)

        try:
            unselected = await run_sync_in_worker(
                partial(
                    service.create_or_read,
                    "tool-call-other",
                    "Checkout pool exhausted",
                    "critical",
                    "corroborated timeout evidence",
                )
            )
            with anyio.fail_after(5):
                async with anyio.create_task_group() as tasks:
                    tasks.start_soon(create_selected)
                    await gate.wait_until_blocked()
                    assert _ticket_exists(mysql_url, "tool-call-selected")
                    assert selected_result == []
                    assert unselected.ticket_id != ""
                    await gate.release()
            assert len(selected_result) == 1
        finally:
            service.close()

    anyio.run(exercise)


def _ticket_exists(mysql_url: str, idempotency_key: str) -> bool:
    engine = create_engine(mysql_url)
    try:
        with engine.connect() as connection:
            count = connection.execute(
                text(
                    """
                    SELECT COUNT(*)
                    FROM fake_ops.demo_ticket
                    WHERE idempotency_key = :idempotency_key
                    """
                ),
                {"idempotency_key": idempotency_key},
            ).scalar_one()
            return int(count) == 1
    finally:
        engine.dispose()
