import calendar
import re
from datetime import UTC, datetime, timedelta
from fractions import Fraction

DEMO_START = datetime(2026, 7, 19, 10, 0, tzinfo=UTC)
DEMO_END = datetime(2026, 7, 19, 10, 15, tzinfo=UTC)
DEMO_START_TEXT = "2026-07-19T10:00:00Z"
DEMO_END_TEXT = "2026-07-19T10:15:00Z"
DEMO_START_EXACT = Fraction(calendar.timegm(DEMO_START.utctimetuple()))
DEMO_END_EXACT = Fraction(calendar.timegm(DEMO_END.utctimetuple()))
_RFC3339_INSTANT = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})",
    re.ASCII,
)


def query_metrics(service: str, start: str, end: str) -> dict[str, object]:
    if service != "checkout":
        raise ValueError("unsupported service")
    if (
        parse_exact_instant(start) != DEMO_START_EXACT
        or parse_exact_instant(end) != DEMO_END_EXACT
    ):
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


def parse_exact_instant(value: str) -> Fraction:
    parsed = parse_instant(value)
    whole_seconds = calendar.timegm(parsed.replace(microsecond=0).utctimetuple())
    if value[19] != ".":
        return Fraction(whole_seconds)
    fraction_end = len(value) - (1 if value.endswith("Z") else 6)
    digits = value[20:fraction_end]
    return Fraction(whole_seconds) + Fraction(int(digits), 10 ** len(digits))


def canonical_instant(value: Fraction) -> str:
    whole_seconds = value.numerator // value.denominator
    fraction = value - whole_seconds
    utc = datetime(1970, 1, 1, tzinfo=UTC) + timedelta(seconds=whole_seconds)
    base = (
        f"{utc.year:04d}-{utc.month:02d}-{utc.day:02d}"
        f"T{utc.hour:02d}:{utc.minute:02d}:{utc.second:02d}"
    )
    if fraction == 0:
        return f"{base}Z"

    denominator = fraction.denominator
    terminating_denominator = denominator
    while terminating_denominator % 2 == 0:
        terminating_denominator //= 2
    while terminating_denominator % 5 == 0:
        terminating_denominator //= 5
    if terminating_denominator != 1:
        raise ValueError("instant fraction is not a terminating decimal")

    remainder = fraction.numerator
    digits: list[str] = []
    while remainder:
        digit, remainder = divmod(remainder * 10, denominator)
        digits.append(str(digit))
    return f"{base}.{''.join(digits)}Z"
