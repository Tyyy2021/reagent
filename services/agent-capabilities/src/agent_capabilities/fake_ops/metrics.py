import calendar
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from functools import total_ordering

DEMO_START = datetime(2026, 7, 19, 10, 0, tzinfo=UTC)
DEMO_END = datetime(2026, 7, 19, 10, 15, tzinfo=UTC)
DEMO_START_TEXT = "2026-07-19T10:00:00Z"
DEMO_END_TEXT = "2026-07-19T10:15:00Z"
_RFC3339_INSTANT = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})",
    re.ASCII,
)


@total_ordering
@dataclass(frozen=True, slots=True)
class ExactInstant:
    whole_seconds: int
    fractional_digits: str

    def __post_init__(self) -> None:
        if self.fractional_digits.endswith("0") or any(
            digit not in "0123456789" for digit in self.fractional_digits
        ):
            raise ValueError("fractional digits must be normalized ASCII decimal")

    def __lt__(self, other: object) -> bool:
        if not isinstance(other, ExactInstant):
            return NotImplemented
        if self.whole_seconds != other.whole_seconds:
            return self.whole_seconds < other.whole_seconds

        digit_count = max(
            len(self.fractional_digits), len(other.fractional_digits)
        )
        for index in range(digit_count):
            left_digit = (
                self.fractional_digits[index]
                if index < len(self.fractional_digits)
                else "0"
            )
            right_digit = (
                other.fractional_digits[index]
                if index < len(other.fractional_digits)
                else "0"
            )
            if left_digit != right_digit:
                return left_digit < right_digit
        return False


DEMO_START_EXACT = ExactInstant(calendar.timegm(DEMO_START.utctimetuple()), "")
DEMO_END_EXACT = ExactInstant(calendar.timegm(DEMO_END.utctimetuple()), "")


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


def parse_exact_instant(value: str) -> ExactInstant:
    parsed = parse_instant(value)
    whole_seconds = calendar.timegm(parsed.replace(microsecond=0).utctimetuple())
    if value[19] != ".":
        return ExactInstant(whole_seconds, "")
    fraction_end = len(value) - (1 if value.endswith("Z") else 6)
    digits = value[20:fraction_end].rstrip("0")
    return ExactInstant(whole_seconds, digits)


def canonical_instant(value: ExactInstant) -> str:
    utc = datetime(1970, 1, 1, tzinfo=UTC) + timedelta(
        seconds=value.whole_seconds
    )
    base = (
        f"{utc.year:04d}-{utc.month:02d}-{utc.day:02d}"
        f"T{utc.hour:02d}:{utc.minute:02d}:{utc.second:02d}"
    )
    if not value.fractional_digits:
        return f"{base}Z"
    return f"{base}.{value.fractional_digits}Z"
