#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
# shellcheck source=scripts/lib/demo-common.sh
source "$SCRIPT_DIR/lib/demo-common.sh"

export REAGENT_DEMO_PROFILES=demo-chaos
export AGENT_CAPABILITIES_ACCEPTANCE_ENABLED=true
export AGENT_CAPABILITIES_CHAOS_ENABLED=true
export DEEPSEEK_API_KEY=""

gate_armed=false
release_gate_on_exit() {
  if [[ "$gate_armed" == "true" ]]; then
    python_internal_request \
      POST \
      /internal/chaos/ticket-after-commit/release \
      '{}' >/dev/null 2>&1 || true
  fi
}
trap release_gate_on_exit EXIT

start_failover_stack
verify_schema_isolation

external_alert_id="$(new_external_alert_id failover)"
idempotency_key="$(ticket_call_id "$external_alert_id")"
arm_body="$(
  python3 -c '
import json
import sys

print(json.dumps({"idempotencyKey": sys.argv[1]}, separators=(",", ":")))
' "$idempotency_key"
)"
arm_response="$(
  python_internal_request \
    POST \
    /internal/chaos/ticket-after-commit/arm \
    "$arm_body"
)"
[[ "$(printf '%s' "$arm_response" | json_field armed)" == "true" ]] \
  || die "ticket fault gate did not arm"
gate_armed=true

task_id="$(trigger_alert "$external_alert_id")"
tool_call_id="$(wait_pending_approval "$task_id")"
[[ "$tool_call_id" == "$idempotency_key" ]] \
  || die "scripted ticket call ID did not match the armed key"
decide_approval \
  "$task_id" \
  "$tool_call_id" \
  APPROVE \
  "Task 14 committed-before-response failover approval"

wait_internal_json_field \
  /internal/chaos/ticket-after-commit/status \
  blocked \
  true

worker_a_container_id="$(compose ps -q reagent-worker-a)"
[[ "$worker_a_container_id" =~ ^[0-9a-f]{12,64}$ ]] \
  || die "could not resolve the Worker A container"
run_bounded 60 \
  docker kill --signal KILL "$worker_a_container_id" >/dev/null

release_response="$(
  python_internal_request \
    POST \
    /internal/chaos/ticket-after-commit/release \
    '{}'
)"
[[ "$(printf '%s' "$release_response" | json_field released)" == "true" ]] \
  || die "ticket fault gate did not release"
gate_armed=false

wait_worker_b_json_field \
  "/api/tasks/$task_id" \
  status \
  COMPLETED
evidence="$(worker_b_get "/api/acceptance/tasks/$task_id")"
summary="$(printf '%s' "$evidence" | assert_acceptance failover "$task_id")"

printf 'scenario=failover externalAlertId=%s taskId=%s\n' \
  "$external_alert_id" "$task_id"
printf 'workerAContainer=%s\n' "${worker_a_container_id:0:12}"
printf '%s\n' "$summary"
