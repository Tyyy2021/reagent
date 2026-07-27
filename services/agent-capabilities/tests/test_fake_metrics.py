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


def test_query_metrics_rejects_non_rfc3339_timestamp_grammar() -> None:
    invalid_starts = [
        "2026-07-19T10:00:00",
        "2026-07-19 10:00:00+00:00",
        "20260719T100000Z",
        "2026-07-19T10:00:00+0000",
        "2026-07-19T10:00:00+00",
        "2026-07-19T10:00:00Ztrailing",
        "2026-02-30T10:00:00Z",
        "2026-07-19T10:00:00+24:00",
    ]
    accepted: list[str] = []

    for start in invalid_starts:
        try:
            query_metrics("checkout", start, END)
        except ValueError:
            continue
        accepted.append(start)

    assert accepted == []


def test_query_metrics_accepts_fractional_seconds_and_numeric_offsets() -> None:
    result = query_metrics(
        "checkout",
        "2026-07-19T18:00:00.000000+08:00",
        "2026-07-19T05:15:00.0-05:00",
    )

    assert result["start"] == START
    assert result["end"] == END


def test_query_metrics_accepts_long_zero_fraction_at_exact_boundary() -> None:
    result = query_metrics(
        "checkout",
        "2026-07-19T10:00:00.0000000Z",
        "2026-07-19T10:15:00.0000000Z",
    )

    assert result["start"] == START
    assert result["end"] == END


def test_query_metrics_accepts_five_thousand_zero_fraction_at_exact_boundary() -> (
    None
):
    zeros = "0" * 5000

    result = query_metrics(
        "checkout",
        f"2026-07-19T10:00:00.{zeros}Z",
        f"2026-07-19T10:15:00.{zeros}Z",
    )

    assert result["start"] == START
    assert result["end"] == END


def test_query_metrics_compares_seventh_digit_exactly_with_offset_equivalence() -> None:
    seventh_digit_accepted = False
    try:
        query_metrics(
            "checkout",
            "2026-07-19T10:00:00.0000001Z",
            END,
        )
    except ValueError:
        pass
    else:
        seventh_digit_accepted = True

    exact_zero_offset = query_metrics(
        "checkout",
        "2026-07-19T18:00:00.0000000+08:00",
        "2026-07-19T05:15:00.0000000-05:00",
    )

    assert {
        "seventhDigitAccepted": seventh_digit_accepted,
        "start": exact_zero_offset["start"],
        "end": exact_zero_offset["end"],
    } == {
        "seventhDigitAccepted": False,
        "start": START,
        "end": END,
    }


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
