import re
from datetime import UTC, datetime

DEMO_START = datetime(2026, 7, 19, 10, 0, tzinfo=UTC)
DEMO_END = datetime(2026, 7, 19, 10, 15, tzinfo=UTC)
DEMO_START_TEXT = "2026-07-19T10:00:00Z"
DEMO_END_TEXT = "2026-07-19T10:15:00Z"
_RFC3339_INSTANT = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})",
    re.ASCII,
)


def query_metrics(service: str, start: str, end: str) -> dict[str, object]:
    if service != "checkout":
        raise ValueError("unsupported service")
    if parse_instant(start) != DEMO_START or parse_instant(end) != DEMO_END:
        raise ValueError("unsupported metrics window")

    return {
        "service": "checkout",
        "start": DEMO_START_TEXT,
        "end": DEMO_END_TEXT,
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


def parse_instant(value: str) -> datetime:
    if _RFC3339_INSTANT.fullmatch(value) is None:
        raise ValueError("timestamp must be RFC3339")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("timestamp must be RFC3339") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("timestamp must include a UTC offset")
    return parsed.astimezone(UTC)
