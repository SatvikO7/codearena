#!/usr/bin/env bash
# =============================================================================
# CodeArena end-to-end: rate limiting, quotas and abuse controls.
#
# Verifies the abuse controls against the SHIPPED policy numbers rather than
# tightened test ones -- the integration suite proves the mechanism, this proves
# the configuration that is actually deployed.
#
# Includes a real Redis outage: the container is stopped, the fail-closed
# behaviour is asserted, and recovery is verified afterwards.
#
# Expects a running stack. Takes about eight minutes, including a deliberate wait
# for a token bucket to refill and a Redis restart.
#
#   scripts/e2e/rate-limiting.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/e2e/lib.sh
. scripts/e2e/lib.sh
# ---- 1. accounts ------------------------------------------------------------
step "1. Accounts, and a normal login still works"
clear_buckets
for u in boss alice bob; do
  code=$(register_user "$u"); [ "$code" = "201" ] || bad "register $u" "HTTP $code $(body)"
done
psqlq "UPDATE users SET role='ADMIN' WHERE username='boss$RS';" >/dev/null
for u in boss alice bob; do
  code=$(login "$u"); [ "$code" = "200" ] && ok "signed in $u" || bad "login $u" "HTTP $code"
done

# ---- 2. login throttling ----------------------------------------------------
step "2. Repeated password guessing is throttled"
clear_buckets
limited=$(exhaust guess /api/auth/login \
  "{\"identifier\":\"alice$RS\",\"password\":\"WRONG-PASSWORD-MARKER\"}" 30)
[ -n "$limited" ] && ok "guessing was refused after $limited attempts" \
                  || bad "login never throttled" "30 wrong passwords all answered 401"

retry=$(header 'Retry-After')
[ -n "$retry" ] && [ "$retry" -gt 0 ] 2>/dev/null \
  && ok "Retry-After is present and positive ($retry s)" || bad "Retry-After" "'$retry'"
code=$(jq_ 'd["error"]')
[ "$code" = "RATE_LIMITED" ] && ok "error code is RATE_LIMITED" || bad "error code" "$code"
msg=$(jq_ 'd["message"]')
if hasi "redis|bucket|token|policy|codearena:" "$msg"; then
  bad "the message leaks the mechanism" "$msg"
else
  ok "the message says nothing about the mechanism"
fi

step "3. The account itself is untouched -- a throttle, not a lockout"
enabled=$(psqlq "SELECT enabled FROM users WHERE username='alice$RS';")
[ "$enabled" = "t" ] && ok "alice's account is still enabled" || bad "account disabled" "$enabled"
ttl=$(rediscli EVAL "local k=redis.call('KEYS','codearena:rl:login-account:*'); if #k==0 then return -99 end; return redis.call('TTL',k[1])" 0)
[ "$ttl" -gt 0 ] 2>/dev/null && ok "the throttle expires by itself (TTL ${ttl}s)" || bad "throttle TTL" "$ttl"

step "4. Normal login resumes once the bucket refills"
printf '     waiting %ss for one token to regenerate...\n' "$retry"
sleep "$((retry + 2))"
code=$(login alice)
[ "$code" = "200" ] && ok "alice can sign in again after the window" || bad "recovery" "HTTP $code"

step "5. A real and an imaginary account are refused identically"
clear_buckets
for i in $(seq 1 12); do bad_login probe "alice$RS" >/dev/null; done
strip_ts() { printf '%s' "$1" | sed 's/"timestamp":"[^"]*",//'; }
real_status=$(bad_login probe "alice$RS"); real_body=$(strip_ts "$(body)")
for i in $(seq 1 12); do bad_login probe "ghost-$RS" >/dev/null; done
ghost_status=$(bad_login probe "ghost-$RS"); ghost_body=$(strip_ts "$(body)")
[ "$real_status" = "429" ] && [ "$ghost_status" = "429" ] \
  && ok "both are refused with 429" || bad "statuses differ" "$real_status vs $ghost_status"
[ "$real_body" = "$ghost_body" ] \
  && ok "the responses are identical apart from the clock: no enumeration oracle" \
  || bad "bodies differ" "$real_body | $ghost_body"

# ---- 6. registration --------------------------------------------------------
step "6. Account creation is rate limited"
clear_buckets
limited=""
for i in $(seq 1 25); do
  code=$(req fresh POST /api/auth/register \
    "{\"username\":\"flood$i$RS\",\"email\":\"flood$i$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
  [ "$code" = "000" ] && continue   # no response from the host; not an attempt
  if [ "$code" = "429" ]; then limited=$i; break; fi
done
[ -n "$limited" ] && ok "registration was refused after $limited attempts" \
                  || bad "registration never limited" "25 accounts created"
before=$(psqlq "SELECT count(*) FROM users;")
req fresh POST /api/auth/register \
  "{\"username\":\"rejected$RS\",\"email\":\"rejected$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}" >/dev/null
after=$(psqlq "SELECT count(*) FROM users;")
[ "$before" = "$after" ] && ok "a refused registration creates no account" || bad "account leaked" "$before -> $after"

step "7. Rate limiting has not replaced the uniqueness constraints"
clear_buckets
code=$(req dup POST /api/auth/register \
  "{\"username\":\"alice$RS\",\"email\":\"dup$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
[ "$code" = "409" ] && ok "a duplicate username is still 409" || bad "conflict handling" "HTTP $code"

# ---- 8. submissions ---------------------------------------------------------
step "8. Submissions"
read -r -d '' P <<JSON
{"title":"Limited $RS","slug":"limited-$RS","statement":"Print one.",
 "inputFormat":"None.","outputFormat":"1.","constraints":"Small.",
 "difficulty":"EASY","tags":["MATH"],"timeLimitMs":5000,"memoryLimitMb":256,
 "examples":[{"input":"","output":"1","explanation":"one"}],
 "testCases":[{"input":"","expectedOutput":"1","hidden":true,"weight":1}]}
JSON
code=$(req boss POST /api/admin/problems "$P")
[ "$code" = "201" ] && ok "problem created" || bad "create problem" "HTTP $code $(body)"
PROB=$(jq_ 'd["id"]')
req boss POST "/api/admin/problems/$PROB/publish" '{}' >/dev/null

SUB='{"language":"PYTHON","sourceCode":"print(1)"}'
clear_buckets
code=$(req alice POST "/api/problems/$PROB/submissions" "$SUB")
[ "$code" = "202" ] && ok "a normal submission is accepted" || bad "submit" "HTTP $code $(body)"

limit=$(header 'RateLimit-Limit'); remaining=$(header 'RateLimit-Remaining')
[ -n "$limit" ] && [ "$remaining" = "$((limit - 1))" ] \
  && ok "RateLimit headers on success (limit $limit, remaining $remaining)" \
  || bad "success headers" "limit='$limit' remaining='$remaining'"
[ -z "$(header 'Retry-After')" ] && ok "no Retry-After on a successful response" \
                                 || bad "Retry-After on success" "$(header 'Retry-After')"

step "9. Excessive submissions are refused before anything is queued"
limited=$(exhaust alice "/api/problems/$PROB/submissions" "$SUB")
[ -n "$limited" ] && ok "submissions refused after $limited more" || bad "submissions never limited" ""

# Every accepted submission becomes a row and every refused one does not, so the count
# must move by exactly the number accepted. True whatever a refill does mid-loop --
# unlike "the next request must be 429", which is a coin flip against a ten-second refill.
stored_before=$(psqlq "SELECT count(*) FROM submissions;")
accepted=0
for i in 1 2 3 4; do
  code=$(req alice POST "/api/problems/$PROB/submissions" "$SUB")
  [ "$code" = "202" ] && accepted=$((accepted + 1))
done
stored_after=$(psqlq "SELECT count(*) FROM submissions;")
[ "$((stored_after - stored_before))" = "$accepted" ] \
  && ok "only the accepted submissions became rows ($accepted of 4)" \
  || bad "a refused submission was stored" "$stored_before -> $stored_after, $accepted accepted"

step "10. An invalid body still spends allowance"
clear_buckets
limited=$(exhaust alice "/api/problems/$PROB/submissions" '{"language":"PYTHON"}')
[ -n "$limited" ] \
  && ok "malformed requests are limited too: validation is not a way round the limit" \
  || bad "invalid bodies are unlimited" "never refused"

step "10b. One user's allowance is their own"
clear_buckets
exhaust alice "/api/problems/$PROB/submissions" "$SUB" >/dev/null
code=$(req bob POST "/api/problems/$PROB/submissions" "$SUB")
[ "$code" = "202" ] && ok "bob is unaffected by alice's exhausted bucket" || bad "bucket isolation" "HTTP $code"

# ---- 11. contests -----------------------------------------------------------
step "11. Contest submissions share the practice allowance"
START=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat().replace('+00:00','Z'))")
END=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=4)).isoformat().replace('+00:00','Z'))")
code=$(req boss POST /api/admin/contests \
  "{\"title\":\"Limited Cup $RS\",\"slug\":\"limited-cup-$RS\",\"description\":\"A contest.\",\"startAt\":\"$START\",\"endAt\":\"$END\"}")
[ "$code" = "201" ] && ok "contest created" || bad "create contest" "HTTP $code $(body)"
CONTEST=$(jq_ 'd["id"]')
req boss POST "/api/admin/contests/$CONTEST/problems" "{\"problemId\":\"$PROB\",\"points\":100}" >/dev/null
req boss POST "/api/admin/contests/$CONTEST/publish" '{}' >/dev/null
code=$(req alice POST "/api/contests/$CONTEST/register" '{}')
[ "$code" = "200" ] && ok "alice registered for the contest" || bad "register" "HTTP $code $(body)"
psqlq "UPDATE contests SET start_at = now() - interval '1 hour', end_at = now() + interval '2 hours' WHERE public_id='$CONTEST';" >/dev/null

clear_buckets
exhaust alice "/api/problems/$PROB/submissions" "$SUB" >/dev/null
refused_within 3 alice "/api/contests/$CONTEST/problems/$PROB/submissions" "$SUB" \
  && ok "the contest endpoint is not a way round the practice limit" \
  || bad "contest bypass" "three contest submissions all accepted on a spent allowance"

# Every accepted submission becomes a row and every refused one does not, so the
# count has to move by exactly the number that were accepted -- true whatever the
# refill happens to do mid-loop.
scored_before=$(psqlq "SELECT count(*) FROM submissions WHERE contest_id IS NOT NULL;")
accepted=0
for i in 1 2 3 4; do
  code=$(req alice POST "/api/contests/$CONTEST/problems/$PROB/submissions" "$SUB")
  [ "$code" = "202" ] && accepted=$((accepted + 1))
done
scored_after=$(psqlq "SELECT count(*) FROM submissions WHERE contest_id IS NOT NULL;")
[ "$((scored_after - scored_before))" = "$accepted" ] \
  && ok "only the accepted contest submissions were stored ($accepted of 4)" \
  || bad "a refused contest submission was stored" \
     "$scored_before -> $scored_after with $accepted accepted"

# ---- 12. authorisation ordering --------------------------------------------
step "12. Rate limiting never replaces authorisation"
clear_buckets
# Through a cookie jar, so the request carries a CSRF token and is refused by
# authentication rather than by the CSRF filter.
anon_ok=1
for i in $(seq 1 15); do
  code=$(req anon POST "/api/problems/$PROB/submissions" "$SUB")
  [ "$code" = "401" ] || { anon_ok=0; bad "anonymous submit" "HTTP $code on attempt $i"; break; }
done
[ "$anon_ok" = "1" ] && ok "an anonymous caller is always 401, never 429"

user_ok=1
for i in $(seq 1 15); do
  code=$(get alice /api/admin/audit-events)
  [ "$code" = "403" ] || { user_ok=0; bad "non-admin admin read" "HTTP $code on attempt $i"; break; }
done
[ "$user_ok" = "1" ] && ok "a non-administrator is always 403, never 429"

step "13. Administrators are limited too"
clear_buckets

# First, the direct question: is an administrator COUNTED? An exempt caller would
# not be, so a remaining count below the limit settles it without racing anything.
code=$(get boss /api/admin/audit-events)
[ "$code" = "200" ] && ok "an administrator can read the audit log" || bad "admin read" "HTTP $code"
limit=$(header 'RateLimit-Limit')
remaining=$(header 'RateLimit-Remaining')
[ -n "$limit" ] && [ -n "$remaining" ] && [ "$remaining" -lt "$limit" ] 2>/dev/null \
  && ok "an administrator is counted like anyone else ($remaining of $limit left)" \
  || bad "admin not counted by the limiter" "limit='$limit' remaining='$remaining'"

# Then, that the refusal is genuinely reachable. Drained in parallel on purpose: the
# admin bucket refills once a second, so a sequential loop is racing the refill and
# whether it wins depends on how fast the host answers. Requests in flight together
# outrun it whatever the per-request latency.
BOSSJAR=$(jar boss)
CODES="$E2E_TMP/admin-codes"
: > "$CODES"
refused=0
for round in 1 2 3 4 5; do
  for i in $(seq 1 40); do
    # -b only, never -c: forty writers sharing one cookie jar would corrupt it.
    curl -s -o /dev/null -w '%{http_code}\n' -b "$BOSSJAR" \
      "$API/api/admin/audit-events" >> "$CODES" 2>/dev/null &
  done
  wait
  refused=$(grep -c '^429$' "$CODES" 2>/dev/null || true)
  [ "${refused:-0}" -gt 0 ] 2>/dev/null && break
done
sent=$(grep -c . "$CODES" 2>/dev/null || true)
[ "${refused:-0}" -gt 0 ] 2>/dev/null \
  && ok "and is refused once the allowance is spent ($refused of $sent concurrent reads)" \
  || bad "admin exempt from limiting" "$sent concurrent reads, none refused"

# ---- 14. bypass attempts ----------------------------------------------------
step "14. Bypass attempts"
clear_buckets
exhaust alice "/api/problems/$PROB/submissions" "$SUB" >/dev/null
refused_within 3 alice "/api/problems/$PROB/submissions" "$SUB" -H 'X-Forwarded-For: 10.9.9.9' \
  && ok "a forged X-Forwarded-For buys nothing" \
  || bad "forwarded-header bypass" "three requests accepted on a spent allowance"
refused_within 3 alice "/api/problems/$PROB/submissions?limit=9999&rateLimit=off" "$SUB" \
  && ok "client-supplied limit parameters are ignored" \
  || bad "parameter bypass" "three requests accepted on a spent allowance"
code=$(req alice POST "/api/problems/$PROB/submissions/" "$SUB")
[ "$code" = "429" ] || [ "$code" = "404" ] \
  && ok "a trailing slash is not a fresh allowance (HTTP $code)" || bad "path-variant bypass" "HTTP $code"
BOBID=$(psqlq "SELECT public_id FROM users WHERE username='bob$RS';")
refused_within 3 alice "/api/problems/$PROB/submissions?userId=$BOBID" "$SUB" \
  && ok "naming another user does not spend their allowance" \
  || bad "identity spoof" "three requests accepted on a spent allowance"

step "15. Ordinary browsing is not limited"
browse_ok=1
for i in $(seq 1 40); do
  code=$(get alice /api/problems)
  [ "$code" = "200" ] || { browse_ok=0; bad "catalogue browsing" "HTTP $code on request $i"; break; }
done
[ "$browse_ok" = "1" ] && ok "40 catalogue pages, all served"

# ---- 16. Redis state hygiene ------------------------------------------------
step "16. Rate-limit state in Redis"
keys=$(rediscli EVAL "local k=redis.call('KEYS','codearena:rl:*'); return #k" 0)
[ "$keys" -gt 0 ] 2>/dev/null && ok "buckets exist ($keys keys)" || bad "no buckets found" "$keys"
nottl=$(rediscli EVAL "local k=redis.call('KEYS','codearena:rl:*'); local n=0; for i=1,#k do if redis.call('TTL',k[i])<0 then n=n+1 end end; return n" 0)
[ "$nottl" = "0" ] && ok "every bucket expires on its own" || bad "keys without a TTL" "$nottl"
leak=$(rediscli EVAL "local k=redis.call('KEYS','codearena:rl:*'); local n=0; for i=1,#k do if string.find(k[i],ARGV[1],1,true) or string.find(k[i],'WRONG-PASSWORD',1,true) then n=n+1 end end; return n" 0 "alice$RS")
[ "$leak" = "0" ] && ok "no username or password is readable from the keyspace" || bad "KEY LEAK" "$leak keys"
long=$(rediscli EVAL "local k=redis.call('KEYS','codearena:rl:*'); local m=0; for i=1,#k do if #k[i]>m then m=#k[i] end end; return m" 0)
[ "$long" -lt 80 ] 2>/dev/null && ok "every key is bounded in length (longest $long)" || bad "unbounded key length" "$long"

# ---- 17. audit --------------------------------------------------------------
step "17. Rejections are audited, but bounded"
# Scoped to this run. The table is append-only by design, so an all-time count measures
# every run the database has ever seen rather than whether the cooldown is working.
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='RATE_LIMIT_EXCEEDED' AND occurred_at > now() - interval '20 minutes';")
[ "$n" -ge 1 ] && ok "RATE_LIMIT_EXCEEDED recorded ($n events this run)" \
              || bad "no rate-limit audit events" "$n"
[ "$n" -lt 40 ] && ok "far fewer events than the thousands of rejections: the cooldown holds" \
               || bad "an event per rejection" "$n events in 20 minutes"
leak=$(psqlq "SELECT count(*) FROM audit_events WHERE action='RATE_LIMIT_EXCEEDED' AND (metadata::text LIKE '%alice%' OR metadata::text LIKE '%WRONG-PASSWORD%' OR metadata::text LIKE '%172.%');")
[ "$leak" = "0" ] && ok "no identity or credential in the audit metadata" || bad "AUDIT LEAK" "$leak rows"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='RATE_LIMIT_EXCEEDED' AND metadata->>'policy'='login-account';")
[ "$n" = "0" ] && ok "the account-keyed policy writes no audit rows, as designed" || bad "unbounded audit source" "$n rows"

# ---- 18. frontend -----------------------------------------------------------
step "18. The deployed frontend handles 429"
bundle=$(curl -s "$WEB/" | grep -o '/assets/index-[A-Za-z0-9_-]*\.js' | head -1)
[ -n "$bundle" ] && ok "found the built bundle ($bundle)" || bad "no bundle" "could not parse index.html"
js=$(curl -s "$WEB$bundle")
has "Too many requests" "$js" && ok "the bundle reports rate limiting to the user" \
                              || bad "no 429 message in the bundle" ""
has "retry-after" "$js" && ok "the bundle reads Retry-After" || bad "Retry-After not used" ""

# ---- 19. judging and scoring still work ------------------------------------
step "19. Judging is unaffected"
clear_buckets
code=$(req bob POST "/api/problems/$PROB/submissions" "$SUB")
[ "$code" = "202" ] && ok "submission accepted for judging" || bad "submit" "HTTP $code $(body)"
SUBID=$(jq_ 'd["submissionId"]')
verdict=""
for i in $(seq 1 60); do
  get bob "/api/submissions/$SUBID" >/dev/null
  verdict=$(jq_ 'd["status"]')
  case "$verdict" in QUEUED|RUNNING|"") sleep 2 ;; *) break ;; esac
done
[ "$verdict" = "ACCEPTED" ] && ok "the judge returned ACCEPTED" || bad "verdict" "$verdict"

step "20. Contest scoring is unaffected"
clear_buckets
code=$(req alice POST "/api/contests/$CONTEST/problems/$PROB/submissions" "$SUB")
[ "$code" = "202" ] && ok "contest submission accepted" || bad "contest submit" "HTTP $code $(body)"
CSUB=$(jq_ 'd["submissionId"]')
for i in $(seq 1 60); do
  get alice "/api/submissions/$CSUB" >/dev/null
  verdict=$(jq_ 'd["status"]')
  case "$verdict" in QUEUED|RUNNING|"") sleep 2 ;; *) break ;; esac
done
[ "$verdict" = "ACCEPTED" ] && ok "the contest submission was judged ACCEPTED" || bad "contest verdict" "$verdict"
get alice "/api/contests/$CONTEST/standings" >/dev/null
score=$(body | python -c "
import json,sys
d=json.load(sys.stdin)
rows=[r for r in d['rows'] if r['username'].startswith('alice')]
print(rows[0]['score'] if rows else 'no row')")
[ "$score" = "100" ] && ok "alice scores 100 in the standings" || bad "standings score" "$score"

# ---- 21. Redis failure ------------------------------------------------------
step "21. Redis failure follows the documented policy"
docker compose stop redis >/dev/null 2>&1
code=$(req outage POST /api/auth/register \
  "{\"username\":\"outage$RS\",\"email\":\"outage$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
[ "$code" = "429" ] && ok "registration fails CLOSED while Redis is down (HTTP 429)" \
                    || bad "fail-closed" "HTTP $code"
n=$(psqlq "SELECT count(*) FROM users WHERE username='outage$RS';")
[ "$n" = "0" ] && ok "and no account was created" || bad "account created during outage" "$n"
if hasi "redis|connection|exception" "$(body)"; then
  bad "the outage response leaks infrastructure" "$(body)"
else
  ok "the outage response says nothing about the infrastructure"
fi

docker compose start redis >/dev/null 2>&1
for i in $(seq 1 30); do
  [ "$(rediscli PING)" = "PONG" ] && break
  sleep 1
done
ok "Redis is back"

recovered=""
for i in $(seq 1 30); do
  code=$(req recovered POST /api/auth/register \
    "{\"username\":\"recovered$RS\",\"email\":\"recovered$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
  if [ "$code" = "201" ]; then recovered=$i; break; fi
  sleep 1
done
[ -n "$recovered" ] && ok "registration works again after the restart (${recovered}s)" \
                    || bad "did not recover" "still HTTP $code after 30s"

clear_buckets
limited=""
for i in $(seq 1 25); do
  code=$(req recovered2 POST /api/auth/register \
    "{\"username\":\"post$i$RS\",\"email\":\"post$i$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
  [ "$code" = "000" ] && continue   # no response from the host; not an attempt
  if [ "$code" = "429" ]; then limited=$i; break; fi
done
[ -n "$limited" ] && ok "and the limit is enforced again, not merely reachable" \
                  || bad "limiting broken after restart" "25 registrations all allowed"

# ---- 22. no secrets in logs -------------------------------------------------
step "22. Logs"
logs=$(docker compose logs backend --since 40m 2>/dev/null)
has "WRONG-PASSWORD-MARKER" "$logs" && bad "PASSWORD IN LOGS" "" || ok "no password in the logs"
has "Str0ng-Passw0rd" "$logs" && bad "PASSWORD IN LOGS" "" || ok "no registration password in the logs"
has "RATE_LIMIT_REJECTED" "$logs" && ok "rejections are logged" || bad "no rejection logging" ""
n=$(printf '%s' "$logs" | grep -c "RATE_LIMIT_REJECTED")
[ "$n" -lt 60 ] && ok "rejection logging is bounded ($n lines for hundreds of rejections)" \
               || bad "log flood" "$n lines"

finish
