#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1
  pwd -P
)"
# shellcheck source=scripts/lib/demo-common.sh
source "$SCRIPT_DIR/lib/demo-common.sh"

task7_context=""
cleanup_verification() {
  if [[ "$task7_context" == /tmp/reagent-task7.* \
      && -d "$task7_context" ]]; then
    rm -r -- "$task7_context"
  fi
  bash "$SCRIPT_DIR/demo-down.sh" >/dev/null 2>&1 || true
}
trap cleanup_verification EXIT

cd "$REPO_ROOT"
run_bounded 1200 ./mvnw -B test

cd "$REPO_ROOT/services/agent-capabilities"
run_bounded 1200 uv sync --locked --all-groups
run_bounded 600 uv run --locked ruff check .
run_bounded 600 uv run --locked pyright
mkdir -p build/reports
run_bounded 1200 \
  uv run --locked pytest \
  -m "not integration and not quality" \
  -q \
  --junitxml=build/reports/python-fast.xml

model_cache="$REPO_ROOT/.superpowers/sdd/hf-cache-task9"
mkdir -p "$model_cache"
run_bounded 1800 \
  env HF_HUB_CACHE="$model_cache" \
  uv run --locked python -c \
  "import os; from pathlib import Path; from agent_capabilities.rag.embedding import MiniLmEmbedding; MiniLmEmbedding(cache_dir=Path(os.environ['HF_HUB_CACHE']))"

cd "$REPO_ROOT"
run_bounded 3600 \
  docker build \
  --file services/agent-capabilities/Dockerfile \
  --tag reagent/agent-capabilities:local \
  --tag reagent-agent-capabilities:task9 \
  .

task7_context="$(mktemp -d /tmp/reagent-task7.XXXXXX)"
git archive b94930e services/agent-capabilities/src \
  | tar -x -C "$task7_context"
printf '%s\n' \
  'FROM reagent-agent-capabilities:task9' \
  'COPY --chown=app:app services/agent-capabilities/src /app/src' \
  > "$task7_context/Dockerfile"
run_bounded 600 \
  docker build \
  --file "$task7_context/Dockerfile" \
  --tag reagent-agent-capabilities:task7 \
  "$task7_context"
rm -r -- "$task7_context"
task7_context=""

run_bounded 2400 \
  env REAGENT_TEST_HF_CACHE="$model_cache" \
  ./mvnw -B -Pci verify

cd "$REPO_ROOT/services/agent-capabilities"
run_bounded 1800 \
  env HF_HUB_CACHE="$model_cache" \
  uv run --locked pytest \
  -m integration \
  -q \
  --junitxml=build/reports/python-integration.xml
run_bounded 2400 \
  env \
  HF_HOME="$model_cache" \
  HF_HUB_CACHE="$model_cache" \
  HF_HUB_OFFLINE=1 \
  TRANSFORMERS_OFFLINE=1 \
  uv run --locked pytest \
  -m quality \
  -q \
  --junitxml=build/reports/python-quality.xml

cd "$REPO_ROOT"
bash "$SCRIPT_DIR/demo-reset.sh"
seed_model_cache "$model_cache"
bash "$SCRIPT_DIR/demo-smoke.sh"
bash "$SCRIPT_DIR/demo-reject.sh"
bash "$SCRIPT_DIR/demo-failover.sh"
bash "$SCRIPT_DIR/demo-down.sh"

trap - EXIT
printf 'All Java, Python, Compose, and demo verification gates passed.\n'
