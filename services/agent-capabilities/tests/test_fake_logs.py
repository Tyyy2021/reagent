from typing import cast

import pytest

from agent_capabilities.fake_ops.logs import search_logs

START = "2026-07-19T10:00:00Z"
END = "2026-07-19T10:15:00Z"


def test_search_logs_returns_timestamped_bounded_connection_timeout_lines() -> None:
    result = search_logs(
        "checkout",
        START,
        END,
        "exception:SQLTransientConnectionException pool:HikariPool-checkout",
        3,
    )

    assert result["service"] == "checkout"
    assert result["start"] == START
    assert result["end"] == END
    assert result["query"] == (
        "exception:SQLTransientConnectionException pool:HikariPool-checkout"
    )
    entries = cast(list[dict[str, object]], result["entries"])
    assert 1 <= len(entries) <= 3
    for entry in entries:
        assert set(entry) == {"timestamp", "line"}
        assert str(entry["timestamp"]).endswith("Z")
        line = str(entry["line"])
        assert len(line) <= 500
        assert "SQLTransientConnectionException" in line
        assert "Connection is not available, request timed out after 30000ms" in line
        assert "HikariPool-checkout" in line


def test_search_logs_applies_limit_without_returning_unbounded_data() -> None:
    result = search_logs(
        "checkout",
        START,
        END,
        "SQLTransientConnectionException",
        1,
    )

    assert len(result["entries"]) == 1  # type: ignore[arg-type]


def test_search_logs_filters_entries_to_requested_sub_window() -> None:
    result = search_logs(
        "checkout",
        "2026-07-19T10:05:00Z",
        "2026-07-19T10:06:00Z",
        "SQLTransientConnectionException",
        50,
    )

    entries = cast(list[dict[str, object]], result["entries"])
    assert [entry["timestamp"] for entry in entries] == [
        "2026-07-19T10:05:47.443Z"
    ]


def test_search_logs_includes_entries_at_both_window_boundaries() -> None:
    result = search_logs(
        "checkout",
        "2026-07-19T10:03:12.120Z",
        "2026-07-19T10:05:47.443Z",
        "SQLTransientConnectionException",
        50,
    )

    entries = cast(list[dict[str, object]], result["entries"])
    assert [entry["timestamp"] for entry in entries] == [
        "2026-07-19T10:03:12.120Z",
        "2026-07-19T10:05:47.443Z",
    ]


def test_search_logs_excludes_fixture_before_sub_microsecond_start() -> None:
    result = search_logs(
        "checkout",
        "2026-07-19T10:03:12.1200001Z",
        "2026-07-19T10:03:13Z",
        "SQLTransientConnectionException",
        50,
    )

    assert result["entries"] == []


def test_search_logs_canonicalizes_fractional_window_without_precision_loss() -> None:
    result = search_logs(
        "checkout",
        "2026-07-19T18:03:12.12000010+08:00",
        "2026-07-19T05:05:47.4430000-05:00",
        "SQLTransientConnectionException",
        50,
    )

    assert result["start"] == "2026-07-19T10:03:12.1200001Z"
    assert result["end"] == "2026-07-19T10:05:47.443Z"


@pytest.mark.parametrize("limit", [0, 51])
def test_search_logs_rejects_limit_outside_one_to_fifty(limit: int) -> None:
    with pytest.raises(ValueError, match="limit"):
        search_logs("checkout", START, END, "timeout", limit)


@pytest.mark.parametrize(
    ("service", "start", "end"),
    [
        ("payments", START, END),
        ("checkout", END, START),
        ("checkout", START, "2026-07-19T10:16:00Z"),
        ("checkout", "2026-07-19T10:00:00", END),
    ],
)
def test_search_logs_rejects_unknown_service_reversed_or_oversized_window(
    service: str, start: str, end: str
) -> None:
    with pytest.raises(ValueError):
        search_logs(service, start, end, "timeout", 10)


@pytest.mark.parametrize(
    "query",
    [
        "",
        "message:timeout.*",
        "/SQLTransient.*/",
        "host:checkout-1",
        "unknown:value",
        "x" * 129,
    ],
)
def test_search_logs_rejects_unbounded_syntax_and_unknown_fields(query: str) -> None:
    with pytest.raises(ValueError, match="query"):
        search_logs("checkout", START, END, query, 10)
