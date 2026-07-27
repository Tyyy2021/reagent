import hashlib
import json
import os
import subprocess
from collections.abc import Generator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from testcontainers.mysql import MySqlContainer  # pyright: ignore[reportMissingTypeStubs]

from agent_capabilities.fake_ops.tickets import (
    IdempotencyConflict,
    TicketResult,
    TicketService,
    TicketValidationError,
    ticket_id_for,
)

pytestmark = pytest.mark.integration

_SERVICE_ROOT = Path(__file__).resolve().parents[1]
_TITLE = "Checkout connection pool exhausted"
_SEVERITY = "critical"
_EVIDENCE = "SQLTransientConnectionException from HikariPool-checkout"


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


@pytest.fixture(autouse=True)
def empty_tickets(mysql_url: str) -> Generator[None]:
    engine = create_engine(mysql_url)
    with engine.begin() as connection:
        connection.execute(text("DELETE FROM fake_ops.demo_ticket"))
    yield
    engine.dispose()


def test_sequential_replay_returns_one_stable_ticket_and_counts_attempts(
    mysql_url: str,
) -> None:
    service = TicketService.from_url(mysql_url)

    first = service.create_or_read("tool-call-sequential", _TITLE, _SEVERITY, _EVIDENCE)
    replay = service.create_or_read("tool-call-sequential", _TITLE, _SEVERITY, _EVIDENCE)

    expected_id = ticket_id_for("tool-call-sequential")
    assert first.ticket_id == expected_id
    assert first.deduplicated is False
    assert first.attempt_count == 1
    assert replay.ticket_id == expected_id
    assert replay.deduplicated is True
    assert replay.attempt_count == 2
    assert _rows(mysql_url) == [
        {
            "idempotency_key": "tool-call-sequential",
            "request_hash": _request_hash(_TITLE, _SEVERITY, _EVIDENCE),
            "ticket_id": expected_id,
            "title": _TITLE,
            "severity": _SEVERITY,
            "evidence": _EVIDENCE,
            "attempt_count": 2,
            "conflict_count": 0,
        }
    ]
    service.close()


def test_sixteen_concurrent_replays_create_one_row(mysql_url: str) -> None:
    service = TicketService.from_url(mysql_url, pool_size=16)

    def create_replay(_: int) -> TicketResult:
        return service.create_or_read(
            "tool-call-concurrent", _TITLE, _SEVERITY, _EVIDENCE
        )

    with ThreadPoolExecutor(max_workers=16) as executor:
        results = list(executor.map(create_replay, range(16)))

    assert {result.ticket_id for result in results} == {
        ticket_id_for("tool-call-concurrent")
    }
    assert sorted(result.attempt_count for result in results) == list(range(1, 17))
    assert sum(not result.deduplicated for result in results) == 1
    assert _rows(mysql_url)[0]["attempt_count"] == 16
    service.close()


def test_different_keys_create_different_stable_ticket_ids(mysql_url: str) -> None:
    service = TicketService.from_url(mysql_url)

    first = service.create_or_read("tool-call-a", _TITLE, _SEVERITY, _EVIDENCE)
    second = service.create_or_read("tool-call-b", _TITLE, _SEVERITY, _EVIDENCE)

    assert first.ticket_id == ticket_id_for("tool-call-a")
    assert second.ticket_id == ticket_id_for("tool-call-b")
    assert first.ticket_id != second.ticket_id
    assert len(_rows(mysql_url)) == 2
    service.close()


def test_same_key_with_different_payload_commits_conflict_without_overwrite(
    mysql_url: str,
) -> None:
    service = TicketService.from_url(mysql_url)
    original = service.create_or_read(
        "tool-call-conflict", _TITLE, _SEVERITY, _EVIDENCE
    )

    with pytest.raises(IdempotencyConflict):
        service.create_or_read(
            "tool-call-conflict",
            "A replacement title",
            "warning",
            "replacement evidence",
        )

    row = _rows(mysql_url)[0]
    assert row == {
        "idempotency_key": "tool-call-conflict",
        "request_hash": _request_hash(_TITLE, _SEVERITY, _EVIDENCE),
        "ticket_id": original.ticket_id,
        "title": _TITLE,
        "severity": _SEVERITY,
        "evidence": _EVIDENCE,
        "attempt_count": 2,
        "conflict_count": 1,
    }
    service.close()


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("idempotency_key", ""),
        ("idempotency_key", "k" * 256),
        ("title", ""),
        ("title", "t" * 256),
        ("severity", ""),
        ("severity", "s" * 17),
        ("evidence", ""),
        ("evidence", "é" * 8_388_608),
    ],
)
def test_ticket_input_bounds_are_enforced(
    mysql_url: str, field: str, value: str
) -> None:
    service = TicketService.from_url(mysql_url)
    values = {
        "idempotency_key": "tool-call-bounds",
        "title": _TITLE,
        "severity": _SEVERITY,
        "evidence": _EVIDENCE,
    }
    values[field] = value

    with pytest.raises(TicketValidationError):
        service.create_or_read(**values)

    assert _rows(mysql_url) == []
    service.close()


def test_ticket_id_is_uppercase_sha256_prefix() -> None:
    digest = hashlib.sha256(b"opaque-tool-call-id").hexdigest().upper()

    assert ticket_id_for("opaque-tool-call-id") == f"OPS-{digest[:12]}"


def _request_hash(title: str, severity: str, evidence: str) -> str:
    canonical = json.dumps(
        {"evidence": evidence, "severity": severity, "title": title},
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


def _rows(mysql_url: str) -> list[dict[str, object]]:
    engine = create_engine(mysql_url)
    try:
        with engine.connect() as connection:
            rows = connection.execute(
                text(
                    """
                    SELECT idempotency_key, request_hash, ticket_id, title, severity,
                           evidence, attempt_count, conflict_count
                    FROM fake_ops.demo_ticket
                    ORDER BY id
                    """
                )
            ).mappings()
            return [dict(row) for row in rows]
    finally:
        engine.dispose()
