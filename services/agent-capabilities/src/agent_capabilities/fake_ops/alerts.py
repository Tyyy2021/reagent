import copy
import json
import re
import sys
from datetime import datetime

_ALERT_ID = re.compile(
    r"ALERT-CHECKOUT-(?:ALERT|SMOKE|REJECT|FAILOVER)-"
    r"(?P<utc>\d{8}T\d{6}Z)-[0-9a-f]{12}"
)
_CHECKOUT_INCIDENT: dict[str, object] = {
    "source": "fake-alertmanager",
    "externalAlertId": "ALERT-CHECKOUT-001",
    "service": "checkout",
    "severity": "critical",
    "title": "Checkout error rate is above threshold",
    "summary": "5xx error rate exceeded 10% for five minutes",
    "startedAt": "2026-07-19T10:00:00Z",
    "labels": {
        "environment": "demo",
        "region": "cn-east",
    },
}


def checkout_alert(external_alert_id: str) -> dict[str, object]:
    match = _ALERT_ID.fullmatch(external_alert_id)
    if match is None:
        raise ValueError("invalid external alert ID")
    try:
        datetime.strptime(match.group("utc"), "%Y%m%dT%H%M%SZ")
    except ValueError as error:
        raise ValueError("invalid external alert ID") from error

    incident = copy.deepcopy(_CHECKOUT_INCIDENT)
    incident["externalAlertId"] = external_alert_id
    return incident


def _main(arguments: list[str]) -> int:
    if len(arguments) != 1:
        raise SystemExit("usage: python -m agent_capabilities.fake_ops.alerts <id>")
    json.dump(checkout_alert(arguments[0]), sys.stdout, separators=(",", ":"))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
