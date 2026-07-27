#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
# shellcheck source=scripts/lib/demo-common.sh
source "$SCRIPT_DIR/lib/demo-common.sh"

if (( $# > 1 )); then
  die "usage: scripts/demo-alert.sh [external-alert-id]"
fi

external_alert_id="${1:-$(new_external_alert_id alert)}"
validate_external_alert_id "$external_alert_id" \
  || die "invalid external alert ID"
task_id="$(trigger_alert "$external_alert_id")"

printf 'externalAlertId=%s\n' "$external_alert_id"
printf 'taskId=%s\n' "$task_id"
