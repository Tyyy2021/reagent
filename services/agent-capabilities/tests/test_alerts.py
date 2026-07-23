import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from agent_capabilities.fake_ops.alerts import checkout_alert

_REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
_INCIDENT_FIXTURE = _REPOSITORY_ROOT / "contracts" / "incident-intake-v1.example.json"
_VALID_ID = "ALERT-CHECKOUT-ALERT-20260719T100000Z-012345abcdef"


@pytest.mark.parametrize("scenario", ["ALERT", "SMOKE", "REJECT", "FAILOVER"])
def test_checkout_alert_replaces_only_external_id(scenario: str) -> None:
    external_id = f"ALERT-CHECKOUT-{scenario}-20260719T100000Z-012345abcdef"
    expected = json.loads(_INCIDENT_FIXTURE.read_text())
    expected["externalAlertId"] = external_id

    actual = checkout_alert(external_id)

    assert _compact_without_external_id(actual) == _compact_without_external_id(expected)
    assert actual == expected


@pytest.mark.parametrize(
    "external_id",
    [
        "",
        "ALERT-CHECKOUT-OTHER-20260719T100000Z-012345abcdef",
        "ALERT-CHECKOUT-ALERT-20260719T100000-012345abcdef",
        "ALERT-CHECKOUT-ALERT-20260719T100000Z-ABCDEF012345",
        "ALERT-CHECKOUT-ALERT-20261319T100000Z-012345abcdef",
        "ALERT-CHECKOUT-ALERT-20260719T100000Z-012345abcdef-extra",
    ],
)
def test_checkout_alert_rejects_invalid_external_ids(external_id: str) -> None:
    with pytest.raises(ValueError, match="external alert ID"):
        checkout_alert(external_id)


def test_checkout_alert_returns_independent_deep_copies() -> None:
    first = checkout_alert(_VALID_ID)
    second = checkout_alert(_VALID_ID)

    first["labels"]["environment"] = "mutated"  # type: ignore[index]
    first["title"] = "mutated"

    assert second["labels"] == {"environment": "demo", "region": "cn-east"}
    assert second["title"] == "Checkout error rate is above threshold"


def test_alert_cli_writes_one_compact_json_object() -> None:
    completed = subprocess.run(
        [sys.executable, "-m", "agent_capabilities.fake_ops.alerts", _VALID_ID],
        cwd=Path(__file__).resolve().parents[1],
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        check=True,
        capture_output=True,
        text=True,
    )

    assert completed.stderr == ""
    assert completed.stdout == (
        json.dumps(checkout_alert(_VALID_ID), separators=(",", ":")) + "\n"
    )


def _compact_without_external_id(value: dict[str, object]) -> bytes:
    without_external_id = {key: item for key, item in value.items() if key != "externalAlertId"}
    return json.dumps(without_external_id, separators=(",", ":")).encode()
