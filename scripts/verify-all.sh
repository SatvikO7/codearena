#!/usr/bin/env bash
# =============================================================================
# CodeArena full verification.
#
#   ./scripts/verify-all.sh              everything (about 40 minutes)
#   ./scripts/verify-all.sh --quick      skip the slow fault-injection suites
#   ./scripts/verify-all.sh --keep-up    leave the stack running afterwards
#   ./scripts/verify-all.sh --help       options and stage list
#
# Run this from a clean checkout and it will tell you whether CodeArena is
# healthy. It starts what it needs, waits for it properly, verifies it, and then
# puts things back.
#
# WHAT IT WILL NOT DO
#
# It never runs `docker compose down -v`. The developer's database and Redis
# volumes are theirs; a verification script that wipes them to get a clean run is
# a script people stop trusting. Test data is created with a random per-run suffix
# instead, so runs neither collide with each other nor with real data.
#
# It does restart services during the fault-injection stage -- that is the point
# of that stage -- so do not run the full suite against a stack somebody is using.
# `--quick` skips it.
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"

# --------------------------------------------------------------------------- options
QUICK=0
KEEP_UP=0
for arg in "$@"; do
  case "$arg" in
    --quick)   QUICK=1 ;;
    --keep-up) KEEP_UP=1 ;;
    --help|-h)
      sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      printf 'unknown option: %s (try --help)\n' "$arg" >&2
      exit 2 ;;
  esac
done

# --------------------------------------------------------------------------- output
BOLD=''; DIM=''; RESET=''
if [ -t 1 ]; then BOLD=$'\033[1m'; DIM=$'\033[2m'; RESET=$'\033[0m'; fi

LOG_DIR="$ROOT/.verify-logs"
mkdir -p "$LOG_DIR"

STAGE_NAMES=()
STAGE_RESULTS=()
STAGE_DETAIL=()
CURRENT=0
FAILED=0
STARTED_AT=$(date +%s)

# The stack was already running before we started, or we started it. Decides
# whether to stop it at the end: a script should put back what it changed and
# nothing else.
STACK_WAS_UP=0

# The working tree as it was when this run began. Stage 13 compares against THIS,
# not against the last commit: the question is whether verification changed
# anything, and a developer running this with work in progress must not be told
# their own edits are a failure.
TREE_BEFORE="$LOG_DIR/tree-before.txt"
git status --porcelain 2>/dev/null | grep -v "^??" | sort > "$TREE_BEFORE" || true

TOTAL_STAGES=14
[ "$QUICK" -eq 1 ] && TOTAL_STAGES=11

banner() {
  printf '\n%s========================================%s\n' "$BOLD" "$RESET"
  printf '%s%s%s\n' "$BOLD" "$1" "$RESET"
  printf '%s========================================%s\n' "$BOLD" "$RESET"
}

stage() {
  CURRENT=$((CURRENT + 1))
  printf '\n%s[%d/%d] %s%s\n' "$BOLD" "$CURRENT" "$TOTAL_STAGES" "$1" "$RESET"
}

pass() {
  STAGE_NAMES+=("$1"); STAGE_RESULTS+=("PASS"); STAGE_DETAIL+=("${2:-}")
  printf '      %sPASS%s  %s\n' "$BOLD" "$RESET" "${2:-}"
}

fail() {
  STAGE_NAMES+=("$1"); STAGE_RESULTS+=("FAIL"); STAGE_DETAIL+=("${2:-}")
  FAILED=1
  printf '      %sFAIL%s  %s\n' "$BOLD" "$RESET" "${2:-}"
}

skipped() {
  STAGE_NAMES+=("$1"); STAGE_RESULTS+=("SKIP"); STAGE_DETAIL+=("${2:-}")
  printf '      SKIP  %s\n' "${2:-}"
}

note() { printf '      %s%s%s\n' "$DIM" "$1" "$RESET"; }

# Prints the tail of a log, bounded, so a failure is diagnosable without dumping
# a hundred megabytes into somebody's terminal.
show_tail() {
  local file=$1 lines=${2:-60}
  [ -f "$file" ] || return 0
  printf '      %s--- last %d lines of %s ---%s\n' "$DIM" "$lines" "${file#"$ROOT/"}" "$RESET"
  tail -n "$lines" "$file" | sed 's/^/      /'
}

# Bounded wait for an arbitrary condition. Never an unconditional sleep: a fixed
# sleep is either too short to be reliable or too long to be pleasant, and it
# cannot tell "ready" from "broken but we waited anyway".
await() {
  local description=$1 limit=$2; shift 2
  local waited=0
  while [ "$waited" -lt "$limit" ]; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
    waited=$((waited + 2))
  done
  printf '      timed out after %ds waiting for %s\n' "$limit" "$description"
  return 1
}

# Collected when a stage fails, so the reason is on screen rather than in
# somebody's next twenty minutes. Deliberately bounded and free of secrets:
# container logs are filtered, not dumped.
diagnostics() {
  printf '\n      %s--- diagnostics ---%s\n' "$DIM" "$RESET"
  docker compose ps 2>/dev/null | sed 's/^/      /' || true
  for service in backend worker executor postgres redis; do
    local health
    health=$(docker compose ps "$service" --format '{{.Health}}' 2>/dev/null || true)
    [ -n "$health" ] && printf '      %-9s %s\n' "$service" "$health"
  done
  printf '      %s--- recent errors (bounded) ---%s\n' "$DIM" "$RESET"
  docker compose logs --since 10m --tail 400 2>/dev/null \
    | grep -E "ERROR|Exception|FATAL" \
    | tail -n 40 \
    | sed 's/^/      /' || true
}

# --------------------------------------------------------------------------- 1
stage "Repository and prerequisites"
{
  MISSING=""
  for tool in docker curl git python awk; do
    command -v "$tool" >/dev/null 2>&1 || MISSING="$MISSING $tool"
  done
  command -v node >/dev/null 2>&1 || MISSING="$MISSING node"
  command -v npm  >/dev/null 2>&1 || MISSING="$MISSING npm"

  if [ -n "$MISSING" ]; then
    fail "Repository" "missing required commands:$MISSING"
  elif ! docker compose version >/dev/null 2>&1; then
    fail "Repository" "docker compose (v2) is not available"
  elif ! docker info >/dev/null 2>&1; then
    fail "Repository" "the Docker daemon is not reachable — is Docker running?"
  else
    JAVA_VERSION=$("$ROOT/mvnw" -v 2>/dev/null | awk '/Java version/ {print $3}' | tr -d ',')
    NODE_VERSION=$(node --version 2>/dev/null)
    JAVA_MAJOR=${JAVA_VERSION%%.*}

    if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
      fail "Repository" "Java 21 or newer is required (found '${JAVA_VERSION:-none}')"
    elif [ ! -f "$ROOT/.env" ]; then
      fail "Repository" ".env is missing — copy .env.example and set POSTGRES_PASSWORD and EXECUTOR_TOKEN"
    elif ! docker compose config >/dev/null 2>"$LOG_DIR/compose-config.log"; then
      fail "Repository" "docker compose config is invalid"
      show_tail "$LOG_DIR/compose-config.log" 20
    elif git ls-files --error-unmatch .env >/dev/null 2>&1; then
      fail "Repository" ".env is tracked by git — it must never be committed"
    else
      note "java $JAVA_VERSION · node $NODE_VERSION · docker compose config valid"
      pass "Repository" "prerequisites present, compose config valid, no .env tracked"
    fi
  fi
}

# --------------------------------------------------------------------------- 2
stage "Backend build and tests"
if [ "$FAILED" -eq 1 ]; then
  skipped "Backend" "prerequisites failed"
else
  # The backend suite runs real containers -- Testcontainers for PostgreSQL and Redis,
  # and the judge and sandbox suites against the live daemon. A compose worker that is
  # already up is doing exactly the same thing at the same time, and under that
  # contention container creation starts timing out: the symptom is a judge test
  # failing with SYSTEM_ERROR, which looks like a product bug and is not.
  #
  # So the two judging services are paused for the duration. Stage 4 starts the whole
  # stack again regardless, so this costs nothing and removes a class of false failure.
  if [ -n "$(docker compose ps --services --filter status=running 2>/dev/null | grep -E "worker|executor")" ]; then
    note "pausing the compose worker and executor (they compete for the Docker daemon)"
    docker compose stop worker executor > "$LOG_DIR/compose-pause.log" 2>&1 || true
  fi

  # The full reactor: unit tests, Testcontainers integration tests against real
  # PostgreSQL and Redis, and the Docker-backed sandbox and judge suites.
  if "$ROOT/mvnw" clean verify > "$LOG_DIR/backend.log" 2>&1; then
    COUNTS=$(python - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=s=0
for path in glob.glob("*/target/*-reports/TEST-*.xml"):
    r = ET.parse(path).getroot()
    t += int(r.get("tests")); f += int(r.get("failures"))
    e += int(r.get("errors")); s += int(r.get("skipped"))
print(f"{t} tests, {f} failures, {e} errors, {s} skipped")
PY
)
    pass "Backend" "$COUNTS"
  else
    fail "Backend" "./mvnw clean verify returned non-zero"
    grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)|^\[ERROR\] /" \
      "$LOG_DIR/backend.log" | head -n 25 | sed 's/^/      /'
    show_tail "$LOG_DIR/backend.log" 40
  fi
fi

# --------------------------------------------------------------------------- 3
stage "Frontend checks"
if [ "$FAILED" -eq 1 ]; then
  skipped "Frontend" "an earlier stage failed"
else
  cd "$ROOT/frontend" || exit 1
  FRONTEND_OK=1
  FRONTEND_DETAIL=""

  if [ ! -d node_modules ]; then
    note "installing dependencies (npm ci)"
    npm ci > "$LOG_DIR/frontend-install.log" 2>&1 || FRONTEND_OK=0
    [ "$FRONTEND_OK" -eq 0 ] && FRONTEND_DETAIL="npm ci failed"
  fi

  if [ "$FRONTEND_OK" -eq 1 ]; then
    npx tsc --noEmit > "$LOG_DIR/frontend-typecheck.log" 2>&1 \
      || { FRONTEND_OK=0; FRONTEND_DETAIL="typecheck failed"; }
  fi
  if [ "$FRONTEND_OK" -eq 1 ]; then
    npm run lint > "$LOG_DIR/frontend-lint.log" 2>&1 \
      || { FRONTEND_OK=0; FRONTEND_DETAIL="lint failed"; }
  fi
  if [ "$FRONTEND_OK" -eq 1 ]; then
    npm run test > "$LOG_DIR/frontend-test.log" 2>&1 \
      || { FRONTEND_OK=0; FRONTEND_DETAIL="tests failed"; }
  fi
  if [ "$FRONTEND_OK" -eq 1 ]; then
    npm run build > "$LOG_DIR/frontend-build.log" 2>&1 \
      || { FRONTEND_OK=0; FRONTEND_DETAIL="production build failed"; }
  fi

  cd "$ROOT" || exit 1
  if [ "$FRONTEND_OK" -eq 1 ]; then
    TESTS=$(grep -oE "Tests +[0-9]+ passed" "$LOG_DIR/frontend-test.log" | tail -1)
    pass "Frontend" "typecheck, lint, ${TESTS:-tests}, production build"
  else
    fail "Frontend" "$FRONTEND_DETAIL"
    for candidate in typecheck lint test build install; do
      [ -f "$LOG_DIR/frontend-$candidate.log" ] || continue
      case "$FRONTEND_DETAIL" in *"$candidate"*) show_tail "$LOG_DIR/frontend-$candidate.log" 30 ;; esac
    done
    show_tail "$LOG_DIR/frontend-typecheck.log" 20
  fi
fi

# --------------------------------------------------------------------------- 4
stage "Docker stack"
if [ "$FAILED" -eq 1 ]; then
  skipped "Docker" "an earlier stage failed"
else
  RUNNING=$(docker compose ps --services --filter status=running 2>/dev/null | grep -c . || true)
  [ "${RUNNING:-0}" -gt 0 ] && STACK_WAS_UP=1

  # The sandbox images are NOT compose services -- they are what the executor creates
  # throwaway containers from, and those are created with `--pull never`. On a clean
  # checkout they do not exist, and the symptom is every submission returning
  # SYSTEM_ERROR rather than anything that names the cause. Build them if absent.
  SANDBOX_IMAGES=$(docker images --filter "reference=codearena/sandbox-*" --format "{{.Repository}}" | grep -c . || true)
  if [ "${SANDBOX_IMAGES:-0}" -lt 3 ]; then
    note "building sandbox images (found ${SANDBOX_IMAGES:-0} of 3)"
    if ! ./sandbox/build-images.sh > "$LOG_DIR/sandbox-images.log" 2>&1; then
      fail "Docker" "could not build the sandbox images"
      show_tail "$LOG_DIR/sandbox-images.log" 30
    fi
  else
    note "sandbox images present"
  fi

  if [ "$FAILED" -eq 0 ]; then
    note "building service images and starting the stack"
    if docker compose up -d --build > "$LOG_DIR/compose-up.log" 2>&1; then
      pass "Docker" "sandbox images present; six services built and started"
    else
      fail "Docker" "docker compose up failed"
      show_tail "$LOG_DIR/compose-up.log" 40
    fi
  fi
fi

# --------------------------------------------------------------------------- 5
stage "Service health"
if [ "$FAILED" -eq 1 ]; then
  skipped "Services" "an earlier stage failed"
else
  service_healthy() {
    [ "$(docker compose ps "$1" --format '{{.Health}}' 2>/dev/null | tr -d '\r')" = "healthy" ]
  }

  UNHEALTHY=""
  for service in postgres redis backend worker executor frontend; do
    if await "$service" 240 service_healthy "$service"; then
      note "$service healthy"
    else
      UNHEALTHY="$UNHEALTHY $service"
    fi
  done

  if [ -z "$UNHEALTHY" ]; then
    pass "Services" "postgres, redis, backend, worker, executor, frontend all healthy"
  else
    fail "Services" "never became healthy:$UNHEALTHY"
    diagnostics
  fi
fi

# --------------------------------------------------------------------------- 6
stage "Database migrations"
if [ "$FAILED" -eq 1 ]; then
  skipped "Migrations" "an earlier stage failed"
else
  # Compared against the repository rather than a hard-coded list, so adding a
  # migration cannot leave this stage quietly checking yesterday's schema.
  EXPECTED=$(ls backend/src/main/resources/db/migration/ 2>/dev/null \
             | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tr '\n' ' ')
  APPLIED=$(docker compose exec -T postgres psql -U codearena -d codearena -tAc \
            "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank;" \
            2>/dev/null | tr -d ' \r' | tr '\n' ' ')
  FAILEDMIG=$(docker compose exec -T postgres psql -U codearena -d codearena -tAc \
            "SELECT count(*) FROM flyway_schema_history WHERE success = false;" 2>/dev/null | tr -d ' \r')

  if [ "$EXPECTED" = "$APPLIED" ] && [ "${FAILEDMIG:-1}" = "0" ]; then
    pass "Migrations" "V$(echo "$EXPECTED" | tr ' ' ',' | sed 's/,$//') applied in order, none failed"
  else
    fail "Migrations" "expected [$EXPECTED] but the database reports [$APPLIED], failed=$FAILEDMIG"
    diagnostics
  fi
fi

# --------------------------------------------------------------------------- 7
stage "Redis"
if [ "$FAILED" -eq 1 ]; then
  skipped "Redis" "an earlier stage failed"
else
  PONG=$(docker compose exec -T redis redis-cli PING 2>/dev/null | tr -d '\r')
  PERSIST=$(docker compose exec -T redis redis-cli CONFIG GET appendonly 2>/dev/null | tr -d '\r' | tail -1)
  if [ "$PONG" = "PONG" ] && [ "$PERSIST" = "yes" ]; then
    pass "Redis" "answering, append-only persistence enabled"
  else
    fail "Redis" "ping='$PONG' appendonly='$PERSIST'"
    diagnostics
  fi
fi

# --------------------------------------------------------------------------- 8
stage "Core end-to-end flow"
if [ "$FAILED" -eq 1 ]; then
  skipped "E2E" "an earlier stage failed"
else
  if bash scripts/e2e/core-flow.sh > "$LOG_DIR/e2e-core.log" 2>&1; then
    pass "E2E" "$(grep -oE 'PASS [0-9]+ +FAIL [0-9]+' "$LOG_DIR/e2e-core.log" | tail -1)"
  else
    fail "E2E" "core flow failed"
    grep -B 1 -A 2 "^  FAIL" "$LOG_DIR/e2e-core.log" | head -n 40 | sed 's/^/      /'
    diagnostics
  fi
fi

# --------------------------------------------------------------------------- 9
stage "Audit logging"
if [ "$FAILED" -eq 1 ]; then
  skipped "Audit" "an earlier stage failed"
else
  if bash scripts/e2e/audit.sh > "$LOG_DIR/e2e-audit.log" 2>&1; then
    pass "Audit" "$(grep -oE 'PASS [0-9]+ +FAIL [0-9]+' "$LOG_DIR/e2e-audit.log" | tail -1)"
  else
    fail "Audit" "audit suite failed"
    grep -B 1 -A 2 "^  FAIL" "$LOG_DIR/e2e-audit.log" | head -n 40 | sed 's/^/      /'
  fi
fi

# --------------------------------------------------------------------------- 10
stage "Ratings and rankings"
if [ "$FAILED" -eq 1 ]; then
  skipped "Ratings" "an earlier stage failed"
else
  # Runs in --quick as well. It restarts nothing and injects no faults; its only
  # slow part is waiting for the background sweeper, and that is polled rather
  # than slept through.
  if bash scripts/e2e/ratings.sh > "$LOG_DIR/e2e-ratings.log" 2>&1; then
    pass "Ratings" "$(grep -oE 'PASS [0-9]+ +FAIL [0-9]+' "$LOG_DIR/e2e-ratings.log" | tail -1)"
  else
    fail "Ratings" "ratings suite failed"
    grep -B 1 -A 2 "^  FAIL" "$LOG_DIR/e2e-ratings.log" | head -n 40 | sed 's/^/      /'
  fi
fi

# --------------------------------------------------------------------------- 11
stage "Rate limiting"
if [ "$FAILED" -eq 1 ]; then
  skipped "Rate limiting" "an earlier stage failed"
elif [ "$QUICK" -eq 1 ]; then
  skipped "Rate limiting" "--quick (this suite stops Redis and waits for a bucket to refill)"
else
  if bash scripts/e2e/rate-limiting.sh > "$LOG_DIR/e2e-ratelimit.log" 2>&1; then
    pass "Rate limiting" "$(grep -oE 'PASS [0-9]+ +FAIL [0-9]+' "$LOG_DIR/e2e-ratelimit.log" | tail -1)"
  else
    fail "Rate limiting" "rate-limiting suite failed"
    grep -B 1 -A 2 "^  FAIL" "$LOG_DIR/e2e-ratelimit.log" | head -n 40 | sed 's/^/      /'
  fi
fi

# --------------------------------------------------------------------------- 12
stage "Observability and fault recovery"
if [ "$FAILED" -eq 1 ]; then
  skipped "Observability" "an earlier stage failed"
elif [ "$QUICK" -eq 1 ]; then
  skipped "Observability" "--quick (this suite restarts the worker, executor, Redis and API)"
else
  if bash scripts/e2e/observability.sh > "$LOG_DIR/e2e-observability.log" 2>&1; then
    pass "Observability" "$(grep -oE 'PASS [0-9]+ +FAIL [0-9]+' "$LOG_DIR/e2e-observability.log" | tail -1)"
  else
    fail "Observability" "observability suite failed"
    grep -B 1 -A 2 "^  FAIL" "$LOG_DIR/e2e-observability.log" | head -n 40 | sed 's/^/      /'
    diagnostics
  fi
fi

# --------------------------------------------------------------------------- 13
stage "Sandbox and repository hygiene"
if [ "$FAILED" -eq 1 ]; then
  skipped "Cleanup" "an earlier stage failed"
else
  # The judge creates a container and a volume per execution and removes both. A
  # number that only grows is a leak; a handful during active judging is not.
  STRAY_C=$(docker ps -a --filter "name=codearena-sandbox" --format '{{.Names}}' | grep -c . || true)
  STRAY_V=$(docker volume ls --filter "name=codearena-ws-" --format '{{.Name}}' | grep -c . || true)
  TRACKED_ENV=$(git ls-files | grep -cE '(^|/)\.env$' || true)

  if [ "${STRAY_C:-0}" -le 2 ] && [ "${STRAY_V:-0}" -le 2 ] && [ "${TRACKED_ENV:-0}" -eq 0 ]; then
    pass "Cleanup" "sandbox containers=${STRAY_C:-0} volumes=${STRAY_V:-0}, no .env tracked"
  else
    fail "Cleanup" "stray containers=${STRAY_C:-0} volumes=${STRAY_V:-0} tracked .env=${TRACKED_ENV:-0}"
    docker ps -a --filter "name=codearena-sandbox" --format '      {{.Names}} {{.Status}}' || true
  fi
fi

# --------------------------------------------------------------------------- 14
stage "Final repository state"
if [ "$FAILED" -eq 1 ]; then
  skipped "Repository state" "an earlier stage failed"
else
  # Verification must not modify tracked files. Anything that changed DURING the run
  # is a bug in a test or a build that writes into the working tree -- as distinct
  # from whatever the developer already had in progress, which is none of this
  # stage's business.
  TREE_AFTER="$LOG_DIR/tree-after.txt"
  git status --porcelain 2>/dev/null | grep -v "^??" | sort > "$TREE_AFTER" || true
  CHANGED=$(comm -13 "$TREE_BEFORE" "$TREE_AFTER" | grep -c . || true)

  if [ "${CHANGED:-0}" -eq 0 ]; then
    PREEXISTING=$(grep -c . "$TREE_BEFORE" || true)
    if [ "${PREEXISTING:-0}" -gt 0 ]; then
      pass "Repository state" "verification changed nothing (${PREEXISTING} file(s) were already modified before it started)"
    else
      pass "Repository state" "verification changed nothing, and the tree was clean"
    fi
  else
    fail "Repository state" "${CHANGED} tracked file(s) were modified BY verification"
    comm -13 "$TREE_BEFORE" "$TREE_AFTER" | head -n 20 | sed 's/^/      /'
  fi
fi

# --------------------------------------------------------------------------- teardown
if [ "$KEEP_UP" -eq 1 ]; then
  note "leaving the stack running (--keep-up)"
elif [ "$STACK_WAS_UP" -eq 1 ]; then
  note "leaving the stack running: it was already up before this run"
else
  note "stopping the stack this run started (volumes are left intact)"
  docker compose stop > "$LOG_DIR/compose-stop.log" 2>&1 || true
fi

# --------------------------------------------------------------------------- summary
DURATION=$(( $(date +%s) - STARTED_AT ))
banner "Summary"
printf '\n'
for i in "${!STAGE_NAMES[@]}"; do
  printf '  %-22s %s\n' "${STAGE_NAMES[$i]}" "${STAGE_RESULTS[$i]}"
done
printf '\n  %-22s %dm %02ds\n' "Duration" "$((DURATION / 60))" "$((DURATION % 60))"
printf '  %-22s %s\n' "Logs" "${LOG_DIR#"$ROOT/"}/"

printf '\n%s========================================%s\n' "$BOLD" "$RESET"
if [ "$FAILED" -eq 0 ]; then
  printf '%sCODEARENA VERIFICATION: PASS%s\n' "$BOLD" "$RESET"
  printf '%s========================================%s\n\n' "$BOLD" "$RESET"
  exit 0
else
  printf '%sCODEARENA VERIFICATION: FAIL%s\n' "$BOLD" "$RESET"
  printf '%s========================================%s\n\n' "$BOLD" "$RESET"
  exit 1
fi
