#!/usr/bin/env bash
# =============================================================================
# CodeArena end-to-end: audit logging and system governance.
#
# Verifies the append-only audit log: that the right things are recorded, that
# nothing can alter or remove an event, that a success event cannot outlive a
# rolled-back change, and that no credential, source code or hidden test data
# ever reaches an audit row.
#
# Also checks the curated operational status endpoint and that Actuator withholds
# component detail from anonymous callers.
#
# Expects a running stack. Takes about two minutes.
#
#   scripts/e2e/audit.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/e2e/lib.sh
. scripts/e2e/lib.sh
# ---- 1. accounts ------------------------------------------------------------
step "1. Accounts"
for u in boss alice; do
  code=$(register_user "$u"); [ "$code" = "201" ] || bad "register $u" "HTTP $code $(body)"
done
psqlq "UPDATE users SET role='ADMIN' WHERE username='boss$RS';" >/dev/null
for u in boss alice; do
  code=$(login "$u"); [ "$code" = "200" ] && ok "signed in $u" || bad "login $u" "HTTP $code"
done

step "2. Registration and login were audited"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='AUTH_REGISTER' AND actor_username='alice$RS';")
[ "$n" = "1" ] && ok "AUTH_REGISTER recorded for alice" || bad "register audit" "$n rows"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='AUTH_LOGIN' AND actor_username='alice$RS';")
[ "$n" -ge 1 ] && ok "AUTH_LOGIN recorded" || bad "login audit" "$n rows"

step "3. A failed login is recorded without the password"
# A fresh jar, so nobody is signed in -- the realistic case, and the one where the actor
# is genuinely unknown. A failed login from an ALREADY authenticated session is correctly
# attributed to that session (and covered by AuditApiIT).
req nobody POST /api/auth/login "{\"identifier\":\"alice$RS\",\"password\":\"WRONG-PASSWORD-MARKER\"}" >/dev/null
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='AUTH_LOGIN_FAILURE';")
[ "$n" -ge 1 ] && ok "AUTH_LOGIN_FAILURE recorded" || bad "failure audit" "$n rows"
leak=$(psqlq "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%WRONG-PASSWORD-MARKER%';")
[ "$leak" = "0" ] && ok "the attempted password is not in the audit log" || bad "PASSWORD LEAK" "$leak rows"
actor=$(psqlq "SELECT actor_type FROM audit_events WHERE action='AUTH_LOGIN_FAILURE' ORDER BY id DESC LIMIT 1;")
[ "$actor" = "ANONYMOUS" ] && ok "failed login is attributed to ANONYMOUS" || bad "actor type" "$actor"

# ---- 4. immutability --------------------------------------------------------
step "4. The log is append-only, enforced by the database"
out=$(docker compose exec -T postgres psql -U codearena -d codearena -c \
      "UPDATE audit_events SET action='AUTH_LOGIN' WHERE id=(SELECT max(id) FROM audit_events);" 2>&1)
has "append-only" "$out" && ok "UPDATE is refused" || bad "UPDATE allowed" "$out"
out=$(docker compose exec -T postgres psql -U codearena -d codearena -c \
      "DELETE FROM audit_events WHERE id=(SELECT max(id) FROM audit_events);" 2>&1)
has "append-only" "$out" && ok "DELETE is refused" || bad "DELETE allowed" "$out"
out=$(docker compose exec -T postgres psql -U codearena -d codearena -c "DELETE FROM audit_events;" 2>&1)
has "append-only" "$out" && ok "a blanket DELETE is refused" || bad "blanket DELETE allowed" "$out"

# ---- 5. admin mutations -----------------------------------------------------
step "5. Administrative mutations are audited"
read -r -d '' P <<JSON
{"title":"Audited $RS","slug":"audited-$RS","statement":"Add two numbers.",
 "inputFormat":"Two integers.","outputFormat":"Their sum.","constraints":"Small.",
 "difficulty":"EASY","tags":["MATH"],"timeLimitMs":5000,"memoryLimitMb":256,
 "examples":[{"input":"2 3","output":"5","explanation":"2+3"}],
 "testCases":[{"input":"SECRET-TEST-IN","expectedOutput":"SECRET-TEST-OUT","hidden":true,"weight":1}]}
JSON
code=$(req boss POST /api/admin/problems "$P")
[ "$code" = "201" ] && ok "problem created" || bad "create" "HTTP $code $(body)"
PROB=$(jq_ 'd["id"]')
req boss POST "/api/admin/problems/$PROB/publish" '{}' >/dev/null
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='PROBLEM_CREATE' AND entity_id='$PROB';")
[ "$n" = "1" ] && ok "PROBLEM_CREATE recorded once" || bad "create audit" "$n rows"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='PROBLEM_PUBLISH' AND entity_id='$PROB';")
[ "$n" = "1" ] && ok "PROBLEM_PUBLISH recorded once" || bad "publish audit" "$n rows"
prev=$(psqlq "SELECT metadata->>'previousStatus' FROM audit_events WHERE action='PROBLEM_PUBLISH' AND entity_id='$PROB';")
[ "$prev" = "DRAFT" ] && ok "the transition is recorded (DRAFT -> PUBLISHED)" || bad "transition" "$prev"

step "6. Hidden test data never reaches the audit log"
leak=$(psqlq "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%SECRET-TEST-%';")
[ "$leak" = "0" ] && ok "no hidden test data in audit metadata" || bad "TEST DATA LEAK" "$leak rows"

step "7. A denied admin request is recorded"
code=$(req alice POST /api/admin/problems "$P")
[ "$code" = "403" ] && ok "a normal user gets 403" || bad "authz" "HTTP $code"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='ADMIN_ACCESS_DENIED' AND actor_username='alice$RS';")
[ "$n" -ge 1 ] && ok "ADMIN_ACCESS_DENIED recorded" || bad "denial audit" "$n rows"

step "8. Ordinary reads produce no audit events"
before=$(psqlq "SELECT count(*) FROM audit_events;")
for _ in 1 2 3 4 5; do get alice /api/contests >/dev/null; get alice /api/problems >/dev/null; done
after=$(psqlq "SELECT count(*) FROM audit_events;")
[ "$before" = "$after" ] && ok "10 reads produced 0 events" || bad "read noise" "$before -> $after"

# ---- 9. the API -------------------------------------------------------------
step "9. Audit API authorisation"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/admin/audit-events")
[ "$code" = "401" ] && ok "anonymous gets 401" || bad "anon" "HTTP $code"
code=$(get alice /api/admin/audit-events)
[ "$code" = "403" ] && ok "a normal user gets 403" || bad "user" "HTTP $code"
code=$(get boss /api/admin/audit-events)
[ "$code" = "200" ] && ok "an administrator gets 200" || bad "admin" "HTTP $code"

step "10. Filtering, ordering and the sort whitelist"
get boss "/api/admin/audit-events?action=PROBLEM_PUBLISH" >/dev/null
n=$(jq_ 'len(d["items"])')
[ "$n" -ge 1 ] && ok "filter by action works" || bad "action filter" "$n"
get boss "/api/admin/audit-events?outcome=DENIED" >/dev/null
allDenied=$(body | python -c "import json,sys
d=json.load(sys.stdin)
print(all(i['outcome']=='DENIED' for i in d['items']) and len(d['items'])>0)")
[ "$allDenied" = "True" ] && ok "filter by outcome works" || bad "outcome filter" "$allDenied"
get boss "/api/admin/audit-events?size=5" >/dev/null
sorted=$(body | python -c "import json,sys
d=json.load(sys.stdin)
t=[i['occurredAt'] for i in d['items']]
print(t==sorted(t,reverse=True))")
[ "$sorted" = "True" ] && ok "newest first by default" || bad "ordering" "$sorted"
for s in actorUsername id metadata "occurredAt;DROP" ; do
  code=$(get boss "/api/admin/audit-events?sort=$s")
  [ "$code" = "400" ] || bad "sort=$s accepted" "HTTP $code"
done
ok "every sort outside the whitelist is refused"
code=$(get boss "/api/admin/audit-events?action=NOT_A_REAL_ACTION")
[ "$code" = "400" ] && ok "an unknown action filter is refused" || bad "action validation" "HTTP $code"
code=$(get boss "/api/admin/audit-events?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z")
[ "$code" = "400" ] && ok "an inverted time range is refused" || bad "range validation" "HTTP $code"

step "11. Audit responses expose nothing private"
get boss "/api/admin/audit-events?size=200" >/dev/null
if hasi "@e2e\.invalid|@t\.dev|passwordHash|sourceCode|SECRET-TEST-" "$(body)"; then
  bad "audit response leaks private data" "found"
else
  ok "no email, hash, source or test data in the response"
fi

step "12. Request correlation"
rid=$(psqlq "SELECT request_id FROM audit_events ORDER BY id DESC LIMIT 1;")
has '^[A-Za-z0-9_-]\{1,64\}$' "$rid" && ok "events carry a safe request id" || bad "request id" "$rid"
hdr=$(curl -s -D - -o /dev/null "$API/api/system/info" | grep -i '^x-request-id:' | tr -d '\r')
[ -n "$hdr" ] && ok "the server echoes a request id header" || bad "no request id header" ""
# A hostile id must not be echoed back verbatim.
echoed=$(curl -s -D - -o /dev/null -H 'X-Request-Id: abc
INJECTED' "$API/api/system/info" 2>/dev/null | grep -i '^x-request-id:' | tr -d '\r')
has "INJECTED" "$echoed" && bad "a hostile request id was echoed" "$echoed" || ok "a hostile request id is replaced"

# ---- 13. system status ------------------------------------------------------
step "13. Operational status"
code=$(get alice /api/admin/system/status)
[ "$code" = "403" ] && ok "a normal user cannot read system status" || bad "status authz" "HTTP $code"
code=$(get boss /api/admin/system/status)
[ "$code" = "200" ] && ok "an administrator can" || bad "status" "HTTP $code"
up=$(jq_ 'd["database"]["up"] and d["redis"]["up"]')
[ "$up" = "True" ] && ok "database and Redis report up" || bad "dependencies" "$up"
if hasi "password|jdbc:|docker.sock|EXECUTOR_TOKEN|/var/run" "$(body)"; then
  bad "system status leaks configuration" "found"
else
  ok "no credentials, paths or configuration in system status"
fi

step "14. Actuator health no longer leaks detail anonymously"
anon=$(curl -s "$API/actuator/health")
has '"status":"UP"' "$anon" && ok "status is still public for health checks" || bad "health broken" "$anon"
if hasi '"components"|version|PostgreSQL|/app' "$anon"; then
  bad "anonymous health still discloses component detail" "$anon"
else
  ok "component detail is withheld from anonymous callers"
fi
for probe in liveness readiness; do
  s=$(curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health/$probe")
  [ "$s" = "200" ] && ok "$probe probe still answers" || bad "$probe probe" "HTTP $s"
done

step "15. Logs disclose no secrets"
logs=$(docker compose logs --no-color --tail 4000 backend 2>/dev/null)
for marker in WRONG-PASSWORD-MARKER SECRET-TEST-IN SECRET-TEST-OUT; do
  if has "$marker" "$logs"; then bad "logs contain $marker" "leak"; else ok "logs do not contain $marker"; fi
done

finish
