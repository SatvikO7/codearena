#!/usr/bin/env bash
# =============================================================================
# Shared helpers for the end-to-end suites.
#
# Sourced, never executed. Every suite under scripts/e2e/ uses these so that an
# HTTP call, a cookie jar or a pattern match behaves identically everywhere --
# three copies of a CSRF helper is three places for it to drift.
#
# These talk to a RUNNING stack over real HTTP. Nothing here mocks anything: a
# suite that passed against a mock would say nothing about a deployment.
# =============================================================================

API="${API:-http://localhost:8080}"
WEB="${WEB:-http://localhost:5173}"

PASS=0
FAIL=0

# A per-suite scratch area for cookie jars and response bodies.
E2E_TMP="$(mktemp -d)"
trap 'rm -rf "$E2E_TMP"' EXIT

ok()   { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  FAIL  %s\n     -> %s\n' "$1" "${2:-}"; }
step() { printf '\n== %s\n' "$1"; }

# -----------------------------------------------------------------------------
# Pattern matching that is safe under `set -o pipefail`.
#
# `printf ... | grep -q` is a trap these suites are full of opportunities for:
# grep -q exits the moment it matches, the writer takes SIGPIPE, and pipefail
# turns that into a non-zero pipeline status. The result is that a MATCH reports
# as a failure -- which silently inverts every negative check, and the negative
# checks here are the security ones ("no password in the logs").
#
# grep -c reads all of its input and cannot do this.
# -----------------------------------------------------------------------------
has()  { [ "$(printf '%s' "$2" | grep -c -- "$1")" -gt 0 ]; }
hasi() { [ "$(printf '%s' "$2" | grep -ciE -- "$1")" -gt 0 ]; }

# -----------------------------------------------------------------------------
# A browser-shaped HTTP client: one cookie jar per named identity, CSRF echoed
# back in the header a real browser would use.
# -----------------------------------------------------------------------------
jar() { echo "$E2E_TMP/$1.jar"; }
hdr() { echo "$E2E_TMP/headers"; }
out() { echo "$E2E_TMP/body"; }

# Primes a jar with a CSRF token, the way opening a page in a browser does.
#
# Retried, and loud when it cannot. The naive version returns an empty string if
# the priming request fails for any reason -- a stack that has just come up, a
# connection reset, a moment of load -- and an empty token produces a POST with no
# X-XSRF-TOKEN header, which the server answers 403 ACCESS_DENIED. The suite then
# reports a security-shaped failure for what was actually a blip, and every
# subsequent step fails because the account was never created. A helper that turns
# an infrastructure problem into a misleading authorisation failure is worse than
# one that stops.
csrf() {
  local who=$1 attempt token
  for attempt in 1 2 3 4 5; do
    curl -s -c "$(jar "$who")" -b "$(jar "$who")" "$API/api/system/info" >/dev/null 2>&1
    token=$(awk '$6=="XSRF-TOKEN"{print $7}' "$(jar "$who")" 2>/dev/null | tail -1)
    if [ -n "$token" ]; then printf '%s' "$token"; return 0; fi
    sleep 1
  done
  printf 'FATAL: could not obtain a CSRF token for %s after 5 attempts (is %s up?)
'     "$who" "$API" >&2
  return 1
}

# req <identity> <METHOD> <path> [json-body] [extra curl args...]
# Prints the status code. Body in $(out), headers in $(hdr).
req() {
  local who=$1 method=$2 path=$3 body=${4:-}; shift 4 2>/dev/null || shift 3
  local token; token=$(csrf "$who")
  if [ -n "$body" ]; then
    curl -s -o "$(out)" -D "$(hdr)" -w '%{http_code}' -X "$method" "$API$path" \
      -c "$(jar "$who")" -b "$(jar "$who")" \
      -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $token" \
      --data-binary "$body" "$@"
  else
    curl -s -o "$(out)" -D "$(hdr)" -w '%{http_code}' -X "$method" "$API$path" \
      -c "$(jar "$who")" -b "$(jar "$who")" -H "X-XSRF-TOKEN: $token" "$@"
  fi
}

get() {
  curl -s -o "$(out)" -D "$(hdr)" -w '%{http_code}' \
    -c "$(jar "$1")" -b "$(jar "$1")" "$API$2" "${@:3}"
}

# For endpoints that do not serve JSON -- the metrics endpoint is Prometheus text,
# and asking it for JSON is a 406.
getx() {
  curl -s -o "$(out)" -D "$(hdr)" -w '%{http_code}' -H 'Accept: */*' \
    -c "$(jar "$1")" -b "$(jar "$1")" "$API$2"
}

anon() { curl -s -o "$(out)" -D "$(hdr)" -w '%{http_code}' "$API$1" "${@:2}"; }

body()   { cat "$(out)"; }
header() { grep -i "^$1:" "$(hdr)" | tail -1 | cut -d' ' -f2- | tr -d '\r'; }

# Reads a field out of the last JSON response. `jq` is not assumed to be present;
# Python is already required by the load test.
jq_() { body | python -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

# -----------------------------------------------------------------------------
# Infrastructure access. Through compose, so the suites need no published ports
# for PostgreSQL or Redis -- which deliberately have none.
# -----------------------------------------------------------------------------
psqlq()    { docker compose exec -T postgres psql -U codearena -d codearena -tAc "$1" | tr -d ' \r'; }
rediscli() { docker compose exec -T redis redis-cli "$@" 2>/dev/null | tr -d '\r'; }

# Clears the rate limiter's own keys, leaving sessions and the judge queue alone.
# Targeted on purpose: FLUSHALL would sign everyone out and make the reset itself
# the cause of a later failure.
clear_buckets() {
  rediscli EVAL \
    "local k=redis.call('KEYS',ARGV[1]); for i=1,#k do redis.call('DEL',k[i]) end; return #k" \
    0 'codearena:rl*' >/dev/null
}

# -----------------------------------------------------------------------------
# Waiting, always bounded.
# -----------------------------------------------------------------------------

# wait_for_verdict <identity> <submission-id> [max-seconds]
# Prints the terminal status, or the last non-terminal one if it ran out of time.
wait_for_verdict() {
  local who=$1 id=$2 limit=${3:-180} waited=0 verdict=""
  while [ "$waited" -lt "$limit" ]; do
    get "$who" "/api/submissions/$id" >/dev/null
    verdict=$(jq_ 'd["status"]')
    case "$verdict" in
      QUEUED|RUNNING|"") sleep 2; waited=$((waited + 2)) ;;
      *) break ;;
    esac
  done
  printf '%s' "$verdict"
}

wait_for_backend() {
  local limit=${1:-120} waited=0
  while [ "$waited" -lt "$limit" ]; do
    curl -sf "$API/actuator/health/readiness" >/dev/null 2>&1 && return 0
    sleep 2; waited=$((waited + 2))
  done
  return 1
}

# Every suite here talks to a running stack, so none of them should begin until
# there is one. Without this, a suite started a moment too early reports dozens of
# confusing assertion failures instead of one clear sentence -- and the first of
# them is usually a 401 or a 403, which reads like a security finding rather than
# a stack that is not up yet.
#
# Costs nothing when the stack is already running: the first check succeeds.
if ! wait_for_backend 120; then
  printf 'FATAL: %s is not answering /actuator/health/readiness.
' "$API" >&2
  printf 'Start the stack first:  docker compose up -d
' >&2
  exit 1
fi

# -----------------------------------------------------------------------------
# Test accounts.
#
# Every run uses a fresh random suffix so repeated runs never collide, and every
# identity is obviously synthetic. The password is a fixed non-secret constant:
# it exists only inside a throwaway account on a local stack, and hard-coding a
# recognisable one is what lets the log scans assert it never appears anywhere.
# -----------------------------------------------------------------------------
RS="${RS:-$RANDOM$RANDOM}"

# Unique per run, and deliberately a recognisable literal: the log and metric leak
# scans assert this exact string never appears anywhere, which only works if every
# suite uses the same one.
E2E_PASSWORD="Str0ng-Passw0rd-$RS"

register_user() {
  req "$1" POST /api/auth/register \
    "{\"username\":\"$1$RS\",\"email\":\"$1$RS@e2e.invalid\",\"password\":\"$E2E_PASSWORD\"}"
}

login_user() {
  req "$1" POST /api/auth/login \
    "{\"identifier\":\"$1$RS\",\"password\":\"$E2E_PASSWORD\"}"
}

promote_to_admin() {
  psqlq "UPDATE users SET role='ADMIN' WHERE username='$1$RS';" >/dev/null
}

# The suites read more naturally as `login boss` than `login_user boss`.
login() { login_user "$1"; }

# A login that is meant to fail, against a named account.
bad_login() {
  req "$1" POST /api/auth/login "{\"identifier\":\"$2\",\"password\":\"WRONG-PASSWORD-MARKER\"}"
}

# ---------------------------------------------------------------------------
# Rate-limit helpers.
#
# An exhausted token bucket is not a frozen one: it regenerates on a timer, and a
# docker exec between two steps takes long enough for a token to appear. So neither
# of these asserts on a single request -- one drains until refused, the other asks
# whether a refusal arrives QUICKLY, which is what distinguishes drawing on a spent
# allowance from having been given a fresh one.
# ---------------------------------------------------------------------------

# exhaust <identity> <path> <body> [max] -> prints the attempt number that was refused
exhaust() {
  local who=$1 path=$2 payload=$3 max=${4:-40} i code
  for i in $(seq 1 "$max"); do
    code=$(req "$who" POST "$path" "$payload")
    [ "$code" = "000" ] && continue   # no response from the host; not an attempt
    if [ "$code" = "429" ]; then printf '%s' "$i"; return 0; fi
  done
  return 1
}

# refused_within <n> <identity> <path> <body> [curl args...]
refused_within() {
  local n=$1 who=$2 path=$3 payload=$4; shift 4
  local i code
  for i in $(seq 1 "$n"); do
    code=$(req "$who" POST "$path" "$payload" "$@")
    [ "$code" = "429" ] && return 0
  done
  return 1
}

# One field out of the admin status view, as the administrator jar named `boss`.
status_field() { get boss /api/admin/system/status >/dev/null; jq_ "$1"; }

# -----------------------------------------------------------------------------
# The suite's verdict. Exits non-zero on any failure, so a caller can just check
# the exit status.
# -----------------------------------------------------------------------------
finish() {
  printf '\n===============================\n'
  printf '  PASS %s   FAIL %s\n' "$PASS" "$FAIL"
  printf '===============================\n'
  [ "$FAIL" -eq 0 ]
}
