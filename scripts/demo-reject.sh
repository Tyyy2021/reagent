#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
# shellcheck source=scripts/lib/demo-common.sh
source "$SCRIPT_DIR/lib/demo-common.sh"

export REAGENT_DEMO_PROFILES=demo-smoke
export AGENT_CAPABILITIES_ACCEPTANCE_ENABLED=true
export AGENT_CAPABILITIES_CHAOS_ENABLED=false
export DEEPSEEK_API_KEY=""

start_normal_stack
verify_schema_isolation

external_alert_id="$(new_external_alert_id reject)"
task_id="$(trigger_alert "$external_alert_id")"
tool_call_id="$(wait_pending_approval "$task_id")"
decide_approval \
  "$task_id" \
  "$tool_call_id" \
  REJECT \
  "Task 14 deterministic rejection proof"
wait_task_completed "$task_id"

evidence="$(
  http_get "$API_BASE_URL/api/acceptance/tasks/$task_id"
)"
summary="$(printf '%s' "$evidence" | assert_acceptance reject "$task_id")"

printf 'scenario=reject externalAlertId=%s taskId=%s\n' \
  "$external_alert_id" "$task_id"
printf '%s\n' "$summary"
