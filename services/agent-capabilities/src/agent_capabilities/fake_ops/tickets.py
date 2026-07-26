import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import Engine, create_engine, text
from agent_capabilities.observability import safe_attributes, traced

_MEDIUMTEXT_MAX_BYTES = 16_777_215


class TicketValidationError(ValueError):
    """A ticket request cannot fit the bounded persistence contract."""


class IdempotencyConflict(RuntimeError):
    """An idempotency key was replayed with a different canonical request."""


@dataclass(frozen=True, slots=True)
class TicketResult:
    ticket_id: str
    deduplicated: bool
    attempt_count: int


@dataclass(frozen=True, slots=True)
class TicketAcceptanceSnapshot:
    attempt_count: int
    unique_count: int
    ticket_ids: tuple[str, ...]


def ticket_id_for(idempotency_key: str) -> str:
    digest = hashlib.sha256(idempotency_key.encode("utf-8")).hexdigest().upper()
    return f"OPS-{digest[:12]}"


class TicketService:
    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    @classmethod
    def from_url(cls, mysql_url: str, *, pool_size: int = 5) -> "TicketService":
        return cls(
            create_engine(
                mysql_url,
                pool_size=pool_size,
                max_overflow=0,
                pool_pre_ping=True,
            )
        )

    def create_or_read(
        self,
        idempotency_key: str,
        title: str,
        severity: str,
        evidence: str,
    ) -> TicketResult:
        call_id_hash = hashlib.sha256(idempotency_key.encode("utf-8")).hexdigest()
        with traced(
            "ticket.insert_or_read",
            {
                "mcp.tool": "create_ticket",
                "mcp.tool_call_id_hash": call_id_hash,
            },
        ) as span:
            _validate_text("idempotency_key", idempotency_key, max_characters=255)
            _validate_text("title", title, max_characters=255)
            _validate_text("severity", severity, max_characters=16)
            _validate_text(
                "evidence",
                evidence,
                max_bytes=_MEDIUMTEXT_MAX_BYTES,
            )
            request_hash = _request_hash(title, severity, evidence)
            timestamp = datetime.now(UTC).replace(tzinfo=None)

            with self._engine.begin() as connection:
                connection.execute(
                    text(
                        """
                        INSERT INTO fake_ops.demo_ticket (
                            idempotency_key, request_hash, ticket_id, title, severity,
                            evidence, attempt_count, conflict_count, created_at, updated_at
                        ) VALUES (
                            :idempotency_key, :request_hash, :ticket_id, :title, :severity,
                            :evidence, 1, 0, :created_at, :updated_at
                        )
                        ON DUPLICATE KEY UPDATE
                            id = LAST_INSERT_ID(id),
                            attempt_count = attempt_count + 1,
                            conflict_count = conflict_count
                                + IF(request_hash <> VALUES(request_hash), 1, 0),
                            updated_at = VALUES(updated_at)
                        """
                    ),
                    {
                        "idempotency_key": idempotency_key,
                        "request_hash": request_hash,
                        "ticket_id": ticket_id_for(idempotency_key),
                        "title": title,
                        "severity": severity,
                        "evidence": evidence,
                        "created_at": timestamp,
                        "updated_at": timestamp,
                    },
                )
                stored = (
                    connection.execute(
                        text(
                            """
                            SELECT request_hash, ticket_id, attempt_count
                            FROM fake_ops.demo_ticket
                            WHERE idempotency_key = :idempotency_key
                            """
                        ),
                        {"idempotency_key": idempotency_key},
                    )
                    .mappings()
                    .one()
                )
                conflict = stored["request_hash"] != request_hash
                result = TicketResult(
                    ticket_id=str(stored["ticket_id"]),
                    deduplicated=int(stored["attempt_count"]) > 1,
                    attempt_count=int(stored["attempt_count"]),
                )

            span.set_attributes(
                safe_attributes({"ticket.deduplicated": result.deduplicated})
            )
            if conflict:
                raise IdempotencyConflict(
                    "idempotency key was reused with a different request"
                )
            return result

    def close(self) -> None:
        self._engine.dispose()

    def acceptance_snapshot(
        self,
        idempotency_key: str | None = None,
    ) -> TicketAcceptanceSnapshot:
        with self._engine.connect() as connection:
            if idempotency_key is not None:
                stored = (
                    connection.execute(
                        text(
                            """
                            SELECT attempt_count, ticket_id
                            FROM fake_ops.demo_ticket
                            WHERE idempotency_key = :idempotency_key
                            """
                        ),
                        {"idempotency_key": idempotency_key},
                    )
                    .mappings()
                    .one_or_none()
                )
                if stored is None:
                    return TicketAcceptanceSnapshot(0, 0, ())
                return TicketAcceptanceSnapshot(
                    int(stored["attempt_count"]),
                    1,
                    (str(stored["ticket_id"]),),
                )

            totals = connection.execute(
                text(
                    """
                    SELECT COALESCE(SUM(attempt_count), 0) AS attempt_count,
                           COUNT(*) AS unique_count
                    FROM fake_ops.demo_ticket
                    """
                )
            ).mappings().one()
            ticket_ids = connection.execute(
                text(
                    """
                    SELECT ticket_id
                    FROM fake_ops.demo_ticket
                    ORDER BY ticket_id
                    LIMIT 16
                    """
                )
            ).scalars()
            return TicketAcceptanceSnapshot(
                int(totals["attempt_count"]),
                int(totals["unique_count"]),
                tuple(str(ticket_id) for ticket_id in ticket_ids),
            )


def _request_hash(title: str, severity: str, evidence: str) -> str:
    canonical = json.dumps(
        {"title": title, "severity": severity, "evidence": evidence},
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _validate_text(
    name: str,
    value: str,
    *,
    max_characters: int | None = None,
    max_bytes: int | None = None,
) -> None:
    if not value:
        raise TicketValidationError(f"{name} must be a non-empty string")
    if max_characters is not None and len(value) > max_characters:
        raise TicketValidationError(f"{name} exceeds {max_characters} characters")
    if max_bytes is not None and len(value.encode("utf-8")) > max_bytes:
        raise TicketValidationError(f"{name} exceeds {max_bytes} UTF-8 bytes")
