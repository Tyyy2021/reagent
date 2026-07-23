import pytest

from agent_capabilities.fake_ops.metrics import query_metrics

START = "2026-07-19T10:00:00Z"
END = "2026-07-19T10:15:00Z"


def test_query_metrics_returns_exact_bounded_checkout_fixture() -> None:
    assert query_metrics("checkout", START, END) == {
        "service": "checkout",
        "start": START,
        "end": END,
        "requestRatePerSecond": 128.4,
        "errorRatePercent": 14.2,
        "p95Seconds": 1.8,
        "connectionPool": {
            "max": 40,
            "active": 40,
            "idle": 0,
            "pending": 27,
            "acquisitionTimeoutCount": 83,
        },
    }


def test_query_metrics_accepts_equivalent_timezone_aware_instants() -> None:
    result = query_metrics(
        "checkout",
        "2026-07-19T18:00:00+08:00",
        "2026-07-19T18:15:00+08:00",
    )

    assert result["start"] == START
    assert result["end"] == END


@pytest.mark.parametrize(
    ("service", "start", "end"),
    [
        ("payments", START, END),
        ("checkout", "2026-07-19T10:00:00", END),
        ("checkout", START, "2026-07-19T10:30:00Z"),
        ("checkout", END, START),
        ("checkout", "not-a-time", END),
    ],
)
def test_query_metrics_rejects_any_request_outside_fixed_window(
    service: str, start: str, end: str
) -> None:
    with pytest.raises(ValueError):
        query_metrics(service, start, end)
