#!/usr/bin/env bash
# =============================================================================
# CodeArena end-to-end: observability, correlation and fault recovery.
#
# Verifies the metrics surface, the correlation chain and the health semantics --
# and then breaks things on purpose.
#
# The worker, the executor, Redis and the API are each restarted while work is
# queued, and every recovery claim is made by watching what happens rather than
# by reading the code that would handle it.
#
# Expects a running stack. Takes about ten minutes, most of it service restarts.
# This suite DELIBERATELY restarts containers; do not run it against anything you
# are relying on at the time.
#
#   scripts/e2e/observability.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/e2e/lib.sh
. scripts/e2e/lib.sh
# ---- 1. accounts ------------------------------------------------------------
step "1. Accounts"
clear_buckets
for u in boss alice; do
  code=$(req "$u" POST /api/auth/register \
    "{\"username\":\"$u$RS\",\"email\":\"$u$RS@t.dev\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
  [ "$code" = "201" ] || bad "register $u" "HTTP $code $(body)"
done
psqlq "UPDATE users SET role='ADMIN' WHERE username='boss$RS';" >/dev/null
for u in boss alice; do
  code=$(req "$u" POST /api/auth/login "{\"identifier\":\"$u$RS\",\"password\":\"Str0ng-Passw0rd-$RS\"}")
  [ "$code" = "200" ] && ok "signed in $u" || bad "login $u" "HTTP $code"
done

# ---- 2. metrics exposure ----------------------------------------------------
step "2. The metrics endpoint is administrative"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: */*' "$API/actuator/prometheus")
[ "$code" = "401" ] && ok "anonymous gets 401" || bad "anon metrics" "HTTP $code"
code=$(getx alice /actuator/prometheus)
[ "$code" = "403" ] && ok "a normal user gets 403" || bad "user metrics" "HTTP $code"
code=$(getx boss /actuator/prometheus)
[ "$code" = "200" ] && ok "an administrator gets 200" || bad "admin metrics" "HTTP $code"
METRICS=$(body)

step "3. Metrics carry no secret and no identifier"
hasi "Str0ng-Passw0rd" "$METRICS" && bad "PASSWORD IN METRICS" "" || ok "no password in the metrics"
has "jdbc:" "$METRICS" && bad "CONNECTION STRING IN METRICS" "" || ok "no connection string"
has "alice$RS" "$METRICS" && bad "USERNAME IN A METRIC LABEL" "" || ok "no username in any label"
has "docker.sock" "$METRICS" && bad "HOST PATH IN METRICS" "" || ok "no host path"
has "/var/run" "$METRICS" && bad "HOST PATH IN METRICS" "" || ok "no filesystem path"

step "4. The dangerous actuator endpoints do not exist"
allgone=1
for e in env configprops heapdump threaddump loggers mappings beans caches scheduledtasks; do
  code=$(getx boss "/actuator/$e")
  [ "$code" = "404" ] || { allgone=0; bad "/actuator/$e reachable" "HTTP $code"; }
done
[ "$allgone" = "1" ] && ok "all nine are 404, even for an administrator"

step "5. The business metrics exist"
for m in codearena_submissions_accepted codearena_auth_attempts codearena_queue_depth \
         codearena_workers codearena_audit_writes codearena_ratelimit_decisions \
         http_server_requests_seconds hikaricp_connections; do
  has "$m" "$METRICS" && ok "$m" || bad "missing metric" "$m"
done

# ---- 6. health semantics ----------------------------------------------------
step "6. Three kinds of health, kept apart"
live=$(curl -s "$API/actuator/health/liveness")
hasi "UP" "$live" && ok "liveness is UP" || bad "liveness" "$live"
hasi "components|PostgreSQL|version" "$live" \
  && bad "liveness leaks component detail to anonymous callers" "$live" \
  || ok "liveness discloses nothing to an anonymous caller"
ready=$(curl -s "$API/actuator/health/readiness")
hasi "UP" "$ready" && ok "readiness is UP" || bad "readiness" "$ready"
getx boss /actuator/health/readiness >/dev/null
detail=$(body)
has "\"db\"" "$detail" && has "\"redis\"" "$detail" \
  && ok "readiness includes the database and Redis" \
  || bad "readiness components" "$detail"

# ---- 7. worker heartbeat ----------------------------------------------------
step "7. The worker register"
n=$(status_field 'len(d["workers"])')
[ "$n" -ge 1 ] 2>/dev/null && ok "$n worker(s) registered" || bad "no workers registered" "$n"
healthy=$(status_field 'sum(1 for w in d["workers"] if w["healthy"])')
[ "$healthy" -ge 1 ] 2>/dev/null && ok "$healthy reporting as healthy" || bad "no healthy worker" "$healthy"
ver=$(status_field 'd["workers"][0]["version"]')
[ "$ver" != "development" ] && ok "the worker reports its build ($ver)" || bad "worker version" "$ver"
state=$(status_field 'd["state"]')
[ "$state" = "READY" ] && ok "state is READY" || bad "state" "$state"

step "8. The status view carries no secret or host detail"
get boss /api/admin/system/status >/dev/null
S=$(body)
for forbidden in password secret token jdbc: docker /var/run seccomp apparmor; do
  hasi "$forbidden" "$S" && bad "STATUS LEAK" "$forbidden" || ok "no '$forbidden' in the status view"
done

# ---- 9. queue health --------------------------------------------------------
step "9. Queue health reports age, not only depth"
for f in pending processing oldestPendingAgeSeconds retrying systemErrorsLastHour; do
  v=$(status_field "d[\"queue\"][\"$f\"]")
  [ -n "$v" ] && ok "queue.$f = $v" || bad "missing queue field" "$f"
done

# ---- 10. correlation --------------------------------------------------------
step "10. A request can be followed from the response into the logs and the audit log"
RID="phase10corr$RS"
req boss POST /api/admin/problems "$(cat <<JSON
{"title":"Observed $RS","slug":"observed-$RS","statement":"Print one.",
 "inputFormat":"None.","outputFormat":"1.","constraints":"Small.",
 "difficulty":"EASY","tags":["MATH"],"timeLimitMs":5000,"memoryLimitMb":256,
 "examples":[{"input":"","output":"1","explanation":"one"}],
 "testCases":[{"input":"","expectedOutput":"1","hidden":true,"weight":1}]}
JSON
)" -H "X-Request-Id: $RID" >/dev/null
PROB=$(jq_ 'd["id"]')
[ -n "$PROB" ] && ok "problem created" || bad "create problem" "$(body)"

echoed=$(header 'X-Request-Id')
[ "$echoed" = "$RID" ] && ok "the request id is echoed back" || bad "request id not echoed" "$echoed"

n=$(psqlq "SELECT count(*) FROM audit_events WHERE request_id='$RID';")
[ "$n" -ge 1 ] && ok "the audit event records the request id" || bad "audit correlation" "$n rows"

logs=$(docker compose logs backend --since 5m 2>/dev/null)
has "req=$RID" "$logs" \
  && ok "the log line carries the request id (the Phase 8 gap, now closed)" \
  || bad "request id missing from logs" "no 'req=$RID' in the backend log"

req boss POST "/api/admin/problems/$PROB/publish" '{}' >/dev/null

# ---- 11. the judging pipeline ----------------------------------------------
step "11. A submission is judged, and traceable across every service"
SUB='{"language":"PYTHON","sourceCode":"print(1)"}'
clear_buckets
code=$(req alice POST "/api/problems/$PROB/submissions" "$SUB")
[ "$code" = "202" ] && ok "submission accepted" || bad "submit" "HTTP $code $(body)"
SUBID=$(jq_ 'd["submissionId"]')
verdict=$(wait_for_verdict alice "$SUBID")
[ "$verdict" = "ACCEPTED" ] && ok "the judge returned ACCEPTED" || bad "verdict" "$verdict"

wlogs=$(docker compose logs worker --since 5m 2>/dev/null)
has "$SUBID" "$wlogs" && ok "the worker logged this submission id" || bad "worker correlation" ""
has "sub=$SUBID" "$wlogs" && ok "and carries it in the MDC on every line" || bad "worker MDC" ""
xlogs=$(docker compose logs executor --since 5m 2>/dev/null)
has "sub=$SUBID" "$xlogs" \
  && ok "the executor logs the same id: the chain reaches the container" \
  || bad "executor correlation" "no 'sub=$SUBID' in the executor log"

step "12. Accepted and judged are both counted"
getx boss /actuator/prometheus >/dev/null
M=$(body)
has "codearena_submissions_accepted" "$M" && ok "accepted is counted by the API" || bad "accepted metric" ""
jn=$(docker compose exec -T worker sh -c 'wget -qO- http://127.0.0.1:8081/actuator/prometheus 2>/dev/null' | grep -c "codearena_judge_submissions" || echo 0)
[ "$jn" -gt 0 ] 2>/dev/null && ok "judged is counted by the worker" || bad "worker metric" "$jn"

# ---- 13. fault injection: the worker ---------------------------------------
step "13. FAULT: the worker is restarted mid-flight"
clear_buckets
for i in 1 2 3; do req alice POST "/api/problems/$PROB/submissions" "$SUB" >/dev/null; done
LASTSUB=$(jq_ 'd["submissionId"]')
docker compose restart worker >/dev/null 2>&1
ok "worker restarted"
verdict=$(wait_for_verdict alice "$LASTSUB")
[ "$verdict" = "ACCEPTED" ] || [ "$verdict" = "WRONG_ANSWER" ] \
  && ok "the submission was still judged ($verdict): nothing was lost" \
  || bad "submission lost across a worker restart" "$verdict"

for _ in $(seq 1 30); do
  n=$(status_field 'sum(1 for w in d["workers"] if w["healthy"])')
  [ "${n:-0}" -ge 1 ] 2>/dev/null && break
  sleep 2
done
[ "${n:-0}" -ge 1 ] && ok "the worker re-registered after its restart" || bad "no heartbeat after restart" "$n"

step "14. FAULT: the worker is stopped, and the system says so"
docker compose stop worker >/dev/null 2>&1
sleep 3
n=$(status_field 'len(d["workers"])')
[ "$n" = "0" ] && ok "a clean stop deregisters: 0 workers, not a stale one" || bad "deregistration" "$n workers"

clear_buckets
req alice POST "/api/problems/$PROB/submissions" "$SUB" >/dev/null

# Polled, not slept. The status view measures the queue at most every five seconds --
# QueueHealth caches deliberately, so that a metrics scrape does not re-read the world
# once per gauge -- and a fixed two-second sleep was racing that documented staleness
# window and losing about half the time. Waiting for the transition tests the
# behaviour; sleeping for less than the cache tests the clock.
state=""
for _ in $(seq 1 15); do
  state=$(status_field 'd["state"]')
  [ "$state" = "DEGRADED" ] && break
  sleep 2
done
[ "$state" = "DEGRADED" ] \
  && ok "work waiting with no worker reports DEGRADED" \
  || bad "degraded detection" "state=$state after 30s"

ready=$(curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health/readiness")
[ "$ready" = "200" ] \
  && ok "and readiness stays UP: a judging outage is not a total one" \
  || bad "readiness wrongly failed" "HTTP $ready"

docker compose start worker >/dev/null 2>&1
for _ in $(seq 1 40); do
  state=$(status_field 'd["state"]')
  [ "$state" = "READY" ] && break
  sleep 3
done
[ "$state" = "READY" ] && ok "returns to READY once the worker is back" || bad "did not recover" "state=$state"

# ---- 15. fault injection: the executor --------------------------------------
step "15. FAULT: the executor is restarted"
docker compose restart executor >/dev/null 2>&1
for _ in $(seq 1 30); do
  has healthy "$(docker compose ps executor --format '{{.Health}}' 2>/dev/null)" && break
  sleep 2
done
ok "executor restarted and healthy"
clear_buckets
code=$(req alice POST "/api/problems/$PROB/submissions" "$SUB")
SUBID=$(jq_ 'd["submissionId"]')
verdict=$(wait_for_verdict alice "$SUBID")
[ "$verdict" = "ACCEPTED" ] && ok "judging works again after an executor restart" || bad "verdict" "$verdict"

# ---- 16. fault injection: Redis ---------------------------------------------
step "16. FAULT: Redis is restarted"
before=$(rediscli LLEN codearena:submissions:pending)
docker compose restart redis >/dev/null 2>&1
for _ in $(seq 1 30); do [ "$(rediscli PING)" = "PONG" ] && break; sleep 2; done
ok "Redis is back"
after=$(rediscli LLEN codearena:submissions:pending)
ok "queue survived the restart (append-only file): $before -> $after"

for _ in $(seq 1 40); do
  n=$(rediscli EVAL "return #redis.call('KEYS','codearena:workers:*')" 0)
  [ "${n:-0}" -ge 1 ] 2>/dev/null && break
  sleep 2
done
[ "${n:-0}" -ge 1 ] && ok "the worker re-registered itself within seconds" || bad "heartbeat did not return" "$n"

step "17. Judging works after a Redis restart"
clear_buckets
for u in boss alice; do
  req "$u" POST /api/auth/login "{\"identifier\":\"$u$RS\",\"password\":\"Str0ng-Passw0rd-$RS\"}" >/dev/null
done
code=$(req alice POST "/api/problems/$PROB/submissions" "$SUB")
[ "$code" = "202" ] && ok "submission accepted after the restart" || bad "submit" "HTTP $code $(body)"
SUBID=$(jq_ 'd["submissionId"]')
verdict=$(wait_for_verdict alice "$SUBID")
[ "$verdict" = "ACCEPTED" ] && ok "and judged: $verdict" || bad "verdict" "$verdict"

# ---- 18. fault injection: the API -------------------------------------------
step "18. FAULT: the API is restarted while work is queued"
clear_buckets
for i in 1 2 3; do req alice POST "/api/problems/$PROB/submissions" "$SUB" >/dev/null; done
QUEUEDSUB=$(jq_ 'd["submissionId"]')
docker compose restart backend >/dev/null 2>&1
wait_for_backend && ok "the API restarted and is ready" || bad "API did not come back" ""
for u in boss alice; do
  req "$u" POST /api/auth/login "{\"identifier\":\"$u$RS\",\"password\":\"Str0ng-Passw0rd-$RS\"}" >/dev/null
done
verdict=$(wait_for_verdict alice "$QUEUEDSUB")
[ "$verdict" = "ACCEPTED" ] \
  && ok "work queued before the restart was still judged" \
  || bad "work lost across an API restart" "$verdict"

step "19. Nothing was lost and nothing gave up"
sys=$(psqlq "SELECT count(*) FROM submissions WHERE status='SYSTEM_ERROR' AND finished_at > now() - interval '20 minutes';")
[ "$sys" -le 2 ] 2>/dev/null \
  && ok "system errors across all that fault injection: $sys" \
  || bad "too many system errors" "$sys"
stuck=$(psqlq "SELECT count(*) FROM submissions WHERE status IN ('QUEUED','RUNNING') AND created_at < now() - interval '10 minutes';")
[ "$stuck" = "0" ] && ok "no submission is stuck" || bad "stuck submissions" "$stuck"

# ---- 20. sandbox cleanup ----------------------------------------------------
step "20. Sandbox cleanup after all of that"
c=$(docker ps -a --filter "name=codearena-sandbox" --format '{{.Names}}' | grep -c . || true)
v=$(docker volume ls --filter "name=codearena-ws-" --format '{{.Name}}' | grep -c . || true)
[ "${c:-0}" -le 2 ] && ok "stray sandbox containers: ${c:-0}" || bad "container leak" "${c:-0}"
[ "${v:-0}" -le 2 ] && ok "stray sandbox volumes: ${v:-0}" || bad "volume leak" "${v:-0}"

# ---- 21. logs ---------------------------------------------------------------
step "21. Logs"
all=$(docker compose logs --since 20m 2>/dev/null)
has "Str0ng-Passw0rd" "$all" && bad "PASSWORD IN LOGS" "" || ok "no password in any service log"
has "print(1)" "$all" && bad "SOURCE CODE IN LOGS" "" || ok "no submitted source in any log"
hasi "EXECUTOR_TOKEN=|X-CodeArena-Executor-Token: [A-Za-z0-9]" "$all" \
  && bad "EXECUTOR TOKEN IN LOGS" "" || ok "no executor token in any log"
# Matched as a log LEVEL -- a timestamp followed by the level field -- and not as a
# word. The bare word appears in Tomcat's own informational line, "further occurrences
# of HTTP request parsing errors will be logged at DEBUG level", which this suite
# provokes by sending deliberately malformed requests. Matching the word reported a
# message ABOUT debug logging as debug logging being switched on.
dbg=$(printf '%s' "$all" | grep -cE '[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3} DEBUG' || true)
[ "${dbg:-0}" -eq 0 ] 2>/dev/null \
  && ok "no service is logging at DEBUG" \
  || bad "DEBUG LOGGING ENABLED" "$dbg debug-level lines"

# ---- 22. resource ceilings --------------------------------------------------
step "22. Resource ceilings are actually applied"
unlimited=0
for s in postgres redis backend worker executor frontend; do
  cid=$(docker compose ps -q "$s" 2>/dev/null)
  [ -z "$cid" ] && continue
  lim=$(docker inspect -f '{{.HostConfig.Memory}}' "$cid" 2>/dev/null)
  [ "${lim:-0}" -gt 0 ] 2>/dev/null || { unlimited=1; bad "$s has no memory limit" "$lim"; }
done
[ "$unlimited" = "0" ] && ok "every service has a memory ceiling"

step "23. nginx does not run as root"
whoami_out=$(docker compose exec -T frontend whoami 2>/dev/null | tr -d '\r')
[ "$whoami_out" != "root" ] && ok "the web server runs as '$whoami_out'" || bad "nginx runs as root" ""

finish
