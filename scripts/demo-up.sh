#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
# shellcheck source=scripts/lib/demo-common.sh
source "$SCRIPT_DIR/lib/demo-common.sh"

export REAGENT_DEMO_PROFILES=""
export AGENT_CAPABILITIES_ACCEPTANCE_ENABLED=false
export AGENT_CAPABILITIES_CHAOS_ENABLED=false

start_normal_stack
printf 'ReAgent normal stack is ready: %s\n' "$API_BASE_URL"
printf 'Jaeger UI: http://127.0.0.1:%s\n' "${JAEGER_UI_PORT:-16686}"
