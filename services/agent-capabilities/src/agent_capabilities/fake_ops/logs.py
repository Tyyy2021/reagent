from datetime import datetime, timedelta

from agent_capabilities.fake_ops.metrics import (
    DEMO_END,
    DEMO_END_TEXT,
    DEMO_START,
    DEMO_START_TEXT,
    parse_instant,
)

_MAX_WINDOW = timedelta(minutes=15)
_QUERY_FIELDS = {"message", "exception", "pool"}
_REGEX_META = frozenset("*+?[]{}()|^$\\/")
_LOGS = (
    {
        "timestamp": "2026-07-19T10:03:12.120Z",
        "line": (
            "checkout ERROR HikariPool-checkout SQLTransientConnectionException: "
            "HikariPool-checkout - Connection is not available, request timed out "
            "after 30000ms"
        ),
    },
    {
        "timestamp": "2026-07-19T10:05:47.443Z",
        "line": (
            "checkout ERROR HikariPool-checkout SQLTransientConnectionException: "
            "HikariPool-checkout - Connection is not available, request timed out "
            "after 30000ms; active=40 idle=0 pending=27"
        ),
    },
    {
        "timestamp": "2026-07-19T10:09:01.008Z",
        "line": (
            "checkout WARN HikariPool-checkout SQLTransientConnectionException: "
            "HikariPool-checkout - Connection is not available, request timed out "
            "after 30000ms; acquisitionTimeoutCount=83"
        ),
    },
)


def search_logs(
    service: str,
    start: str,
    end: str,
    query: str,
    limit: int,
) -> dict[str, object]:
    if service != "checkout":
        raise ValueError("unsupported service")
    if type(limit) is not int or not 1 <= limit <= 50:
        raise ValueError("limit must be between 1 and 50")

    parsed_start = parse_instant(start)
    parsed_end = parse_instant(end)
    if parsed_end <= parsed_start:
        raise ValueError("log window must be ordered")
    if parsed_end - parsed_start > _MAX_WINDOW:
        raise ValueError("log window exceeds 15 minutes")
    if parsed_start < DEMO_START or parsed_end > DEMO_END:
        raise ValueError("log window is outside the checkout fixture")

    terms = _query_terms(query)
    entries = [
        dict(entry)
        for entry in _LOGS
        if parsed_start <= parse_instant(str(entry["timestamp"])) <= parsed_end
        and all(term in str(entry["line"]).lower() for term in terms)
    ][:limit]
    return {
        "service": "checkout",
        "start": _canonical_window_endpoint(parsed_start),
        "end": _canonical_window_endpoint(parsed_end),
        "query": query,
        "entries": entries,
    }


def _query_terms(query: str) -> tuple[str, ...]:
    if not query or len(query) > 128:
        raise ValueError("query must contain 1 to 128 characters")
    if any(character in _REGEX_META for character in query):
        raise ValueError("query contains unbounded syntax")

    terms: list[str] = []
    for token in query.split():
        if ":" in token:
            field, value = token.split(":", 1)
            if field not in _QUERY_FIELDS or not value:
                raise ValueError("query contains an unknown field")
            terms.append(value.lower())
        else:
            terms.append(token.lower())
    return tuple(terms)


def _canonical_window_endpoint(value: datetime) -> str:
    if value == DEMO_START:
        return DEMO_START_TEXT
    if value == DEMO_END:
        return DEMO_END_TEXT
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")
