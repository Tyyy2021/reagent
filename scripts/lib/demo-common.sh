#!/usr/bin/env bash
set -Eeuo pipefail

DEMO_COMMON_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
REPO_ROOT="$(
  cd -- "$DEMO_COMMON_DIR/../.." >/dev/null 2>&1
  pwd -P
)"
readonly DEMO_COMMON_DIR REPO_ROOT
readonly COMPOSE_PROJECT_NAME="reagent-demo"
readonly COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

if [[ -f "$REPO_ROOT/.env" ]]; then
  DEMO_ENV_FILE="$REPO_ROOT/.env"
else
  DEMO_ENV_FILE="$REPO_ROOT/.env.example"
fi
readonly DEMO_ENV_FILE

readonly REAGENT_HTTP_PORT="${REAGENT_HTTP_PORT:-8080}"
readonly API_BASE_URL="http://127.0.0.1:${REAGENT_HTTP_PORT}"
readonly WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-600}"
readonly COMPOSE_TIMEOUT_SECONDS="${COMPOSE_TIMEOUT_SECONDS:-900}"
readonly HTTP_TIMEOUT_SECONDS="${HTTP_TIMEOUT_SECONDS:-10}"
readonly LOG_TAIL_LINES="${LOG_TAIL_LINES:-160}"

compose() {
  timeout --foreground "${COMPOSE_TIMEOUT_SECONDS}s" \
    docker compose \
    --project-name "$COMPOSE_PROJECT_NAME" \
    --env-file "$DEMO_ENV_FILE" \
    --file "$COMPOSE_FILE" \
    "$@"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  return 1
}

print_diagnostics() {
  printf '\nCompose status:\n' >&2
  compose ps --all >&2 || true
  printf '\nRecent Compose logs:\n' >&2
  compose logs --tail "$LOG_TAIL_LINES" >&2 || true
}

http_get() {
  local url="${1:?url is required}"
  curl \
    --fail-with-body \
    --silent \
    --show-error \
    --connect-timeout 3 \
    --max-time "$HTTP_TIMEOUT_SECONDS" \
    --header 'Accept: application/json' \
    "$url"
}

http_post_json() {
  local url="${1:?url is required}"
  local body="${2:?JSON body is required}"
  curl \
    --fail-with-body \
    --silent \
    --show-error \
    --connect-timeout 3 \
    --max-time "$HTTP_TIMEOUT_SECONDS" \
    --header 'Accept: application/json' \
    --header 'Content-Type: application/json' \
    --data-binary "$body" \
    "$url"
}

json_field() {
  local field="${1:?top-level JSON field is required}"
  python3 -c '
import json
import sys

field = sys.argv[1]
value = json.load(sys.stdin)
if not isinstance(value, dict) or field not in value:
    raise SystemExit("missing top-level JSON field")
value = value[field]
if isinstance(value, bool):
    print(str(value).lower())
elif value is None:
    print("")
elif isinstance(value, (str, int, float)):
    print(value)
else:
    print(json.dumps(value, separators=(",", ":")))
' "$field"
}

wait_http() {
  local url="${1:?url is required}"
  local timeout_seconds="${2:-$WAIT_TIMEOUT_SECONDS}"
  local deadline=$((SECONDS + timeout_seconds))

  while (( SECONDS < deadline )); do
    if http_get "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  printf 'Timed out waiting for HTTP readiness: %s\n' "$url" >&2
  print_diagnostics
  return 1
}

wait_json_field() {
  local url="${1:?url is required}"
  local field="${2:?field is required}"
  local expected="${3:?expected value is required}"
  local timeout_seconds="${4:-$WAIT_TIMEOUT_SECONDS}"
  local deadline=$((SECONDS + timeout_seconds))
  local response value

  while (( SECONDS < deadline )); do
    if response="$(http_get "$url" 2>/dev/null)" \
        && value="$(printf '%s' "$response" | json_field "$field" 2>/dev/null)" \
        && [[ "$value" == "$expected" ]]; then
      return 0
    fi
    sleep 2
  done

  printf 'Timed out waiting for %s=%s at %s\n' \
    "$field" "$expected" "$url" >&2
  print_diagnostics
  return 1
}

validate_external_alert_id() {
  local value="${1:?external alert ID is required}"
  [[ "$value" =~ ^ALERT-CHECKOUT-(ALERT|SMOKE|REJECT|FAILOVER)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]]
}

new_external_alert_id() {
  local scenario="${1:?scenario is required}"
  local stamp nonce value
  case "$scenario" in
    alert|smoke|reject|failover) ;;
    *)
      printf 'invalid scenario: %s\n' "$scenario" >&2
      return 2
      ;;
  esac
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  nonce="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
  value="ALERT-CHECKOUT-${scenario^^}-${stamp}-${nonce}"
  [[ "$value" =~ ^ALERT-CHECKOUT-(ALERT|SMOKE|REJECT|FAILOVER)-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$ ]]
  printf '%s\n' "$value"
}

ticket_call_id() {
  local external_alert_id="${1:?external alert ID is required}"
  python3 -c '
import hashlib
import sys

digest = hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest()
print("call-create-ticket-" + digest[:16])
' "$external_alert_id"
}

trigger_alert() {
  local external_alert_id="${1:?external alert ID is required}"
  local payload response task_id deduplicated
  validate_external_alert_id "$external_alert_id" \
    || die "invalid external alert ID"

  payload="$(
    compose exec -T agent-capabilities \
      python -m agent_capabilities.fake_ops.alerts "$external_alert_id"
  )"
  (( ${#payload} <= 4096 )) || die "generated alert payload is too large"
  response="$(
    http_post_json "$API_BASE_URL/api/incidents" "$payload"
  )"
  (( ${#response} <= 4096 )) || die "incident response is too large"
  task_id="$(printf '%s' "$response" | json_field taskId)"
  deduplicated="$(printf '%s' "$response" | json_field deduplicated)"
  [[ "$task_id" =~ ^[0-9a-f-]{36}$ ]] || die "incident response has invalid task ID"
  [[ "$deduplicated" == "false" ]] \
    || die "incident intake was not fresh"
  printf '%s\n' "$task_id"
}

wait_pending_approval() {
  local task_id="${1:?task ID is required}"
  local timeout_seconds="${2:-$WAIT_TIMEOUT_SECONDS}"
  local deadline=$((SECONDS + timeout_seconds))
  local response tool_call_id

  while (( SECONDS < deadline )); do
    if response="$(
      http_get "$API_BASE_URL/api/tasks/$task_id/approvals" 2>/dev/null
    )"; then
      tool_call_id="$(
        printf '%s' "$response" | python3 -c '
import json
import sys

items = json.load(sys.stdin)
pending = [
    item.get("toolCallId", "")
    for item in items
    if isinstance(item, dict) and item.get("status") == "PENDING"
]
if len(pending) != 1 or not pending[0]:
    raise SystemExit(3)
print(pending[0])
' 2>/dev/null
      )" || true
      if [[ -n "$tool_call_id" ]]; then
        printf '%s\n' "$tool_call_id"
        return 0
      fi
    fi
    sleep 2
  done

  printf 'Timed out waiting for task approval: %s\n' "$task_id" >&2
  print_diagnostics
  return 1
}

decide_approval() {
  local task_id="${1:?task ID is required}"
  local tool_call_id="${2:?tool call ID is required}"
  local decision="${3:?decision is required}"
  local reason="${4:?reason is required}"
  local body response status
  [[ "$decision" == "APPROVE" || "$decision" == "REJECT" ]] \
    || die "invalid approval decision"

  body="$(
    python3 -c '
import json
import sys

print(json.dumps(
    {"decision": sys.argv[1], "reason": sys.argv[2]},
    separators=(",", ":"),
))
' "$decision" "$reason"
  )"
  response="$(
    http_post_json \
      "$API_BASE_URL/api/tasks/$task_id/approvals/$tool_call_id/decision" \
      "$body"
  )"
  status="$(printf '%s' "$response" | json_field status)"
  if [[ "$decision" == "APPROVE" ]]; then
    [[ "$status" == "APPROVED" ]] || die "approval did not commit"
  else
    [[ "$status" == "REJECTED" ]] || die "rejection did not commit"
  fi
}

wait_task_completed() {
  local task_id="${1:?task ID is required}"
  wait_json_field \
    "$API_BASE_URL/api/tasks/$task_id" \
    status \
    COMPLETED \
    "${2:-$WAIT_TIMEOUT_SECONDS}"
}

python_internal_request() {
  local method="${1:?HTTP method is required}"
  local path="${2:?path is required}"
  local body="${3:-}"
  compose --profile failover exec -T agent-capabilities python -c '
import sys
import urllib.request

method, path, body = sys.argv[1:]
data = body.encode("utf-8") if body else None
request = urllib.request.Request(
    "http://127.0.0.1:8090" + path,
    data=data,
    method=method,
    headers={
        "Accept": "application/json",
        "Content-Type": "application/json",
    },
)
with urllib.request.urlopen(request, timeout=5) as response:
    payload = response.read(65537)
if len(payload) > 65536:
    raise SystemExit("internal response exceeded 65536 bytes")
sys.stdout.buffer.write(payload)
' "$method" "$path" "$body"
}

wait_internal_json_field() {
  local path="${1:?path is required}"
  local field="${2:?field is required}"
  local expected="${3:?expected value is required}"
  local timeout_seconds="${4:-$WAIT_TIMEOUT_SECONDS}"
  local deadline=$((SECONDS + timeout_seconds))
  local response value

  while (( SECONDS < deadline )); do
    if response="$(python_internal_request GET "$path" 2>/dev/null)" \
        && value="$(printf '%s' "$response" | json_field "$field" 2>/dev/null)" \
        && [[ "$value" == "$expected" ]]; then
      return 0
    fi
    sleep 1
  done

  printf 'Timed out waiting for internal %s=%s at %s\n' \
    "$field" "$expected" "$path" >&2
  print_diagnostics
  return 1
}

worker_b_get() {
  local path="${1:?path is required}"
  compose --profile failover exec -T reagent-worker-b \
    wget -q -O - "http://127.0.0.1:8080$path"
}

wait_worker_b_json_field() {
  local path="${1:?path is required}"
  local field="${2:?field is required}"
  local expected="${3:?expected value is required}"
  local timeout_seconds="${4:-$WAIT_TIMEOUT_SECONDS}"
  local deadline=$((SECONDS + timeout_seconds))
  local response value

  while (( SECONDS < deadline )); do
    if response="$(worker_b_get "$path" 2>/dev/null)" \
        && value="$(printf '%s' "$response" | json_field "$field" 2>/dev/null)" \
        && [[ "$value" == "$expected" ]]; then
      return 0
    fi
    sleep 2
  done

  printf 'Timed out waiting for Worker B %s=%s at %s\n' \
    "$field" "$expected" "$path" >&2
  print_diagnostics
  return 1
}

assert_acceptance() {
  local scenario="${1:?scenario is required}"
  local expected_task_id="${2:?task ID is required}"
  python3 -c '
import json
import re
import sys

scenario, expected_task_id = sys.argv[1:]
evidence = json.load(sys.stdin)

def require(condition, message):
    if not condition:
        raise SystemExit("acceptance failed: " + message)

require(evidence.get("contractVersion") == 1, "contract version")
require(evidence.get("taskId") == expected_task_id, "task scope")
require(evidence.get("profileId") == "incident-ops", "profile")
require(evidence.get("taskStatus") == "COMPLETED", "task status")
require(evidence.get("passed") is True, "runtime evidence")
require(bool(evidence.get("citationIds")), "citation IDs")
require(bool(evidence.get("citationSources")), "citation sources")
require(
    set(evidence.get("mcpTools", []))
    == {"query_metrics", "search_logs", "create_ticket"},
    "MCP tools",
)

decision = evidence.get("approvalDecision")
attempts = evidence.get("createTicketAttempts")
unique = evidence.get("uniqueTicketCount")
ticket = evidence.get("ticketId")
epochs = evidence.get("workerEpochs")
require(
    isinstance(epochs, list)
    and epochs
    and all(isinstance(value, int) and value > 0 for value in epochs),
    "worker epochs",
)

if scenario == "smoke":
    require(decision == "APPROVED", "happy approval")
    require(attempts == 1, "happy create attempt")
    require(unique == 1, "happy unique ticket")
    require(
        isinstance(ticket, str)
        and re.fullmatch(r"OPS-[A-Z0-9]{12}", ticket),
        "happy ticket ID",
    )
elif scenario == "reject":
    require(decision == "REJECTED", "reject decision")
    require(attempts == 0, "reject create attempts")
    require(unique == 0, "reject unique tickets")
    require(ticket == "", "reject ticket ID")
elif scenario == "failover":
    require(decision == "APPROVED", "failover approval")
    require(isinstance(attempts, int) and attempts >= 2, "failover replay")
    require(unique == 1, "failover unique ticket")
    require(len(epochs) >= 2 and max(epochs) > min(epochs), "higher epoch")
    require(
        isinstance(ticket, str)
        and re.fullmatch(r"OPS-[A-Z0-9]{12}", ticket),
        "failover ticket ID",
    )
else:
    raise SystemExit("unknown acceptance scenario")

print(
    "status={status} decision={decision} attempts={attempts} "
    "uniqueTickets={unique} epochs={epochs} ticket={ticket}".format(
        status=evidence["taskStatus"],
        decision=decision,
        attempts=attempts,
        unique=unique,
        epochs=",".join(str(value) for value in epochs),
        ticket=ticket or "none",
    )
)
' "$scenario" "$expected_task_id"
}

verify_schema_isolation() {
  local reagent_password="${REAGENT_DB_PASSWORD:-reagent-local-only}"
  local fake_ops_password="${FAKE_OPS_DB_PASSWORD:-fake-ops-local-only}"

  compose exec -T -e "MYSQL_PWD=$reagent_password" mysql \
    mysql -h 127.0.0.1 -u reagent_app \
    -e 'SELECT COUNT(*) FROM reagent.task' >/dev/null
  compose exec -T -e "MYSQL_PWD=$fake_ops_password" mysql \
    mysql -h 127.0.0.1 -u fake_ops_app \
    -e 'SELECT COUNT(*) FROM fake_ops.demo_ticket' >/dev/null

  if compose exec -T -e "MYSQL_PWD=$reagent_password" mysql \
      mysql -h 127.0.0.1 -u reagent_app \
      -e 'SELECT COUNT(*) FROM fake_ops.demo_ticket' >/dev/null 2>&1; then
    die "reagent_app unexpectedly reached fake_ops schema"
  fi
  if compose exec -T -e "MYSQL_PWD=$fake_ops_password" mysql \
      mysql -h 127.0.0.1 -u fake_ops_app \
      -e 'SELECT COUNT(*) FROM reagent.task' >/dev/null 2>&1; then
    die "fake_ops_app unexpectedly reached reagent schema"
  fi
  printf 'schemaIsolation=verified\n'
}

remove_failover_worker() {
  compose --profile failover stop reagent-worker-b >/dev/null 2>&1 || true
  compose --profile failover rm -f reagent-worker-b >/dev/null 2>&1 || true
}

seed_model_cache() {
  local source="${1:?model cache source is required}"
  local expected="$REPO_ROOT/.superpowers/sdd/hf-cache-task9"
  [[ "$source" == "$expected" ]] \
    || die "model cache source must be the fixed verification cache"
  [[ -s "$source/models--sentence-transformers--all-MiniLM-L6-v2/refs/main" ]] \
    || die "fixed verification model cache is incomplete"

  compose run --rm --no-deps \
    --user app \
    --entrypoint /bin/sh \
    --volume "$source:/source:ro" \
    agent-capabilities \
    -c 'mkdir -p /app/.cache/huggingface/hub \
      && cp -a --no-preserve=ownership \
        /source/. /app/.cache/huggingface/hub/'
}

start_normal_stack() {
  remove_failover_worker
  if ! compose up -d --build --remove-orphans; then
    print_diagnostics
    die "normal stack failed to start"
  fi
  wait_http "$API_BASE_URL/actuator/health/readiness"
  wait_json_field "$API_BASE_URL/api/readiness" ready true
}

start_failover_stack() {
  if ! compose --profile failover up -d --build --remove-orphans; then
    print_diagnostics
    die "failover stack failed to start"
  fi
  wait_http "$API_BASE_URL/actuator/health/readiness"
  wait_json_field "$API_BASE_URL/api/readiness" ready true
  wait_worker_b_json_field /api/readiness ready true
}

run_bounded() {
  local timeout_seconds="${1:?timeout is required}"
  shift
  timeout --foreground "${timeout_seconds}s" "$@"
}
