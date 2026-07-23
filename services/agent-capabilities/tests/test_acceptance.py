import json
from collections.abc import Generator
from pathlib import Path

import anyio
import pytest
from starlette.testclient import TestClient
from testcontainers.mysql import MySqlContainer  # pyright: ignore[reportMissingTypeStubs]

from agent_capabilities.app import create_app
from agent_capabilities.config import Settings
from agent_capabilities.database import run_migrations
from agent_capabilities.fake_ops.acceptance import AcceptanceTracker
from agent_capabilities.fake_ops.tickets import TicketService, ticket_id_for

_REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
_CONTRACT = _REPOSITORY_ROOT / "contracts" / "acceptance-v1.response.json"


def test_shared_acceptance_fixture_is_exact_and_bounded() -> None:
    fixture = json.loads(_CONTRACT.read_text())

    assert fixture == {
        "contractVersion": 1,
        "scope": "idempotency-key",
        "toolAttempts": {
            "query_metrics": 0,
            "search_logs": 0,
            "create_ticket": 2,
        },
        "createTicketAttempts": 2,
        "uniqueTicketCount": 1,
        "ticketIds": ["OPS-0123456789AB"],
        "faultGateState": "released",
    }


def test_acceptance_route_is_absent_when_disabled() -> None:
    configured = _settings(
        "mysql+pymysql://sensitive:sensitive@mysql.invalid/fake_ops",
        acceptance_enabled=False,
    )

    with TestClient(create_app(configured)) as client:
        response = client.get("/internal/acceptance")

    assert response.status_code == 404
    assert configured.mysql_url not in response.text


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
        run_migrations(url)
        yield url


@pytest.mark.integration
def test_acceptance_reports_global_matching_and_absent_key_state(mysql_url: str) -> None:
    service = TicketService.from_url(mysql_url)
    matching_key = "acceptance-matching-tool-call"
    other_key = "acceptance-other-tool-call"
    service.create_or_read(matching_key, "Checkout pool", "critical", "evidence")
    service.create_or_read(matching_key, "Checkout pool", "critical", "evidence")
    service.create_or_read(other_key, "Other ticket", "warning", "other evidence")

    tracker = AcceptanceTracker()

    async def record_reads() -> None:
        await tracker.record("query_metrics")
        await tracker.record("query_metrics")
        await tracker.record("search_logs")

    anyio.run(record_reads)
    configured = _settings(mysql_url, acceptance_enabled=True)
    app = create_app(
        configured,
        ticket_service_factory=lambda: service,
        migration_runner=lambda _: None,
        acceptance_tracker=tracker,
    )

    with TestClient(app) as client:
        global_response = client.get("/internal/acceptance")
        matching_response = client.get(
            "/internal/acceptance",
            params={"idempotencyKey": matching_key},
        )
        absent_response = client.get(
            "/internal/acceptance",
            params={"idempotencyKey": "absent-tool-call"},
        )

    matching_id = ticket_id_for(matching_key)
    other_id = ticket_id_for(other_key)
    assert global_response.status_code == 200
    assert global_response.json() == {
        "contractVersion": 1,
        "scope": "all",
        "toolAttempts": {
            "query_metrics": 2,
            "search_logs": 1,
            "create_ticket": 3,
        },
        "createTicketAttempts": 3,
        "uniqueTicketCount": 2,
        "ticketIds": sorted([matching_id, other_id]),
        "faultGateState": "disabled",
    }
    assert matching_response.json() == {
        "contractVersion": 1,
        "scope": "idempotency-key",
        "toolAttempts": {
            "query_metrics": 0,
            "search_logs": 0,
            "create_ticket": 2,
        },
        "createTicketAttempts": 2,
        "uniqueTicketCount": 1,
        "ticketIds": [matching_id],
        "faultGateState": "disabled",
    }
    assert absent_response.json() == {
        "contractVersion": 1,
        "scope": "idempotency-key",
        "toolAttempts": {
            "query_metrics": 0,
            "search_logs": 0,
            "create_ticket": 0,
        },
        "createTicketAttempts": 0,
        "uniqueTicketCount": 0,
        "ticketIds": [],
        "faultGateState": "disabled",
    }
    combined = global_response.text + matching_response.text + absent_response.text
    assert matching_key not in combined
    assert other_key not in combined
    assert mysql_url not in combined
    assert "evidence" not in combined


@pytest.mark.integration
@pytest.mark.parametrize("key", ["", "k" * 256])
def test_acceptance_rejects_invalid_scoped_key_without_echo(
    mysql_url: str, key: str
) -> None:
    service = TicketService.from_url(mysql_url)
    configured = _settings(mysql_url, acceptance_enabled=True)
    app = create_app(
        configured,
        ticket_service_factory=lambda: service,
        migration_runner=lambda _: None,
    )

    with TestClient(app) as client:
        response = client.get(
            "/internal/acceptance",
            params={"idempotencyKey": key},
        )

    assert response.status_code == 400
    assert response.json() == {"code": "INVALID_REQUEST"}
    if key:
        assert key not in response.text


def _settings(mysql_url: str, *, acceptance_enabled: bool) -> Settings:
    return Settings.model_validate(
        {
            "env": "test",
            "mysql_url": mysql_url,
            "acceptance_enabled": acceptance_enabled,
            "chaos_enabled": False,
            "knowledge_root": Path("/unused"),
        }
    )
