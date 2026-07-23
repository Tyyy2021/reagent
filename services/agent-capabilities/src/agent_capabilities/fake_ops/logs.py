from agent_capabilities.fake_ops.metrics import (
    DEMO_END_EXACT,
    DEMO_START_EXACT,
    ExactInstant,
    canonical_instant,
    parse_exact_instant,
)

_MAX_WINDOW_SECONDS = 15 * 60
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

    exact_start = parse_exact_instant(start)
    exact_end = parse_exact_instant(end)
    if exact_end <= exact_start:
        raise ValueError("log window must be ordered")
    if _window_exceeds_limit(exact_start, exact_end):
        raise ValueError("log window exceeds 15 minutes")
    if exact_start < DEMO_START_EXACT or exact_end > DEMO_END_EXACT:
        raise ValueError("log window is outside the checkout fixture")

    terms = _query_terms(query)
    entries = [
        dict(entry)
        for entry in _LOGS
        if exact_start
        <= parse_exact_instant(str(entry["timestamp"]))
        <= exact_end
        and all(term in str(entry["line"]).lower() for term in terms)
    ][:limit]
    return {
        "service": "checkout",
        "start": canonical_instant(exact_start),
        "end": canonical_instant(exact_end),
        "query": query,
        "entries": entries,
    }


def _window_exceeds_limit(start: ExactInstant, end: ExactInstant) -> bool:
    exact_limit = ExactInstant(
        start.whole_seconds + _MAX_WINDOW_SECONDS,
        start.fractional_digits,
    )
    return end > exact_limit


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
