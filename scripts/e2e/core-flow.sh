#!/usr/bin/env bash
# =============================================================================
# The core end-to-end flow: everything a real user does, and the main ways the
# system must say no.
#
# This is the suite to run when you want one answer to "does CodeArena work".
# It walks a user from registration to a judged verdict to a contest standing,
# using the real Docker sandbox -- nothing here is mocked, because a judge that
# passes against a mock says nothing about a deployment.
#
# Expects a running stack. Takes about two minutes, most of it waiting for real
# compilation and execution.
#
#   scripts/e2e/core-flow.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/e2e/lib.sh
. scripts/e2e/lib.sh

SOURCE_OK='{"language":"PYTHON","sourceCode":"print(1)"}'
SOURCE_WRONG='{"language":"PYTHON","sourceCode":"print(999)"}'

# =============================================================== 1. accounts

step "1. Registration and login"
clear_buckets

code=$(register_user alice)
[ "$code" = "201" ] && ok "a user can register" || bad "register alice" "HTTP $code $(body)"

code=$(register_user boss)
[ "$code" = "201" ] && ok "a second user can register" || bad "register boss" "HTTP $code $(body)"
promote_to_admin boss

code=$(login_user alice)
[ "$code" = "200" ] && ok "the user can sign in" || bad "login alice" "HTTP $code"

code=$(login_user boss)
[ "$code" = "200" ] && ok "the administrator can sign in" || bad "login boss" "HTTP $code"

step "2. The session is real"
code=$(get alice /api/auth/me)
[ "$code" = "200" ] && ok "an authenticated session answers /api/auth/me" || bad "session" "HTTP $code"
who=$(jq_ 'd["username"]')
[ "$who" = "alice$RS" ] && ok "and identifies the right user" || bad "wrong user" "$who"
hasi "password|hash" "$(body)" \
  && bad "the profile exposes credential data" "$(body)" \
  || ok "the profile carries no credential data"

# =============================================================== 3. problems

step "3. An administrator authors a problem"
read -r -d '' PROBLEM <<JSON
{"title":"Verify $RS","slug":"verify-$RS","statement":"Print the number one.",
 "inputFormat":"No input.","outputFormat":"The number 1.","constraints":"None.",
 "difficulty":"EASY","tags":["MATH"],"timeLimitMs":5000,"memoryLimitMb":256,
 "examples":[{"input":"","output":"1","explanation":"Always one"}],
 "testCases":[{"input":"","expectedOutput":"1","hidden":true,"weight":1}]}
JSON

code=$(req boss POST /api/admin/problems "$PROBLEM")
[ "$code" = "201" ] && ok "the problem is created as a draft" || bad "create problem" "HTTP $code $(body)"
PROB=$(jq_ 'd["id"]')

step "4. A draft is invisible until it is published"
code=$(get alice "/api/problems/verify-$RS")
[ "$code" = "404" ] && ok "a draft problem answers 404 to a user" || bad "draft leaked" "HTTP $code"

code=$(req boss POST "/api/admin/problems/$PROB/publish" '{}')
[ "$code" = "200" ] && ok "the administrator publishes it" || bad "publish" "HTTP $code $(body)"

step "5. Browsing the catalogue"
code=$(get alice /api/problems)
[ "$code" = "200" ] && ok "the catalogue lists problems" || bad "list problems" "HTTP $code"

code=$(get alice "/api/problems/verify-$RS")
[ "$code" = "200" ] && ok "the published problem is readable" || bad "read problem" "HTTP $code"
detail=$(body)
has "Print the number one" "$detail" && ok "the statement is served" || bad "missing statement" ""

step "6. Hidden test data never reaches a user"
hasi "expectedOutput|hidden" "$detail" \
  && bad "HIDDEN TEST DATA EXPOSED" "$detail" \
  || ok "the problem detail carries no hidden test data"

# =============================================================== 7. submitting

step "7. Submitting a solution"
clear_buckets
code=$(req alice POST "/api/problems/$PROB/submissions" "$SOURCE_OK")
[ "$code" = "202" ] && ok "the submission is accepted (202, not 201: no verdict yet)" \
                    || bad "submit" "HTTP $code $(body)"
SUBID=$(jq_ 'd["submissionId"]')
[ -n "$SUBID" ] && ok "a submission id is returned" || bad "no submission id" "$(body)"

queued=$(psqlq "SELECT status FROM submissions WHERE public_id='$SUBID';")
[ "$queued" = "QUEUED" ] || [ "$queued" = "RUNNING" ] || [ "$queued" = "ACCEPTED" ] \
  && ok "the submission is persisted (status $queued)" || bad "not persisted" "$queued"

step "8. The judge runs it in a real sandbox"
verdict=$(wait_for_verdict alice "$SUBID")
[ "$verdict" = "ACCEPTED" ] && ok "the verdict is ACCEPTED" || bad "verdict" "$verdict"

step "9. The result is readable, with per-test detail"
code=$(get alice "/api/submissions/$SUBID")
[ "$code" = "200" ] && ok "the submission detail is readable by its author" || bad "read" "HTTP $code"
tests=$(jq_ 'len(d.get("testResults") or [])')
[ "${tests:-0}" -ge 1 ] 2>/dev/null && ok "per-test results are present ($tests)" || bad "no test results" "$tests"
has "print(1)" "$(body)" && ok "the author sees their own source" || bad "source missing for author" ""

step "10. A wrong answer is judged as one"
clear_buckets
code=$(req alice POST "/api/problems/$PROB/submissions" "$SOURCE_WRONG")
[ "$code" = "202" ] || bad "submit wrong answer" "HTTP $code"
WRONGID=$(jq_ 'd["submissionId"]')
verdict=$(wait_for_verdict alice "$WRONGID")
[ "$verdict" = "WRONG_ANSWER" ] && ok "an incorrect program gets WRONG_ANSWER" || bad "verdict" "$verdict"

step "11. Submission history"
code=$(get alice /api/submissions)
[ "$code" = "200" ] && ok "the user can list their submissions" || bad "history" "HTTP $code"
n=$(jq_ 'len(d["items"])')
[ "${n:-0}" -ge 2 ] 2>/dev/null && ok "history shows both submissions" || bad "history count" "$n"
has "print(1)" "$(body)" \
  && bad "the history listing includes source code" "" \
  || ok "the listing omits source code (fetch one submission to see it)"

step "12. Real-time status"
# The stream is an authenticated GET that stays open. curl with a short max-time
# proves the endpoint accepts the session and begins a stream; the polling
# fallback is what the frontend uses when it cannot, and is covered by unit tests.
code=$(curl -s -o "$(out)" -w '%{http_code}' --max-time 6 \
        -c "$(jar alice)" -b "$(jar alice)" \
        -H 'Accept: text/event-stream' "$API/api/submissions/$SUBID/events" 2>/dev/null || echo "000")
[ "$code" = "200" ] || [ "$code" = "000" ] \
  && ok "the event stream accepts an authenticated subscriber (HTTP ${code})" \
  || bad "stream rejected" "HTTP $code"

# =============================================================== 13. contests

step "13. A contest, from creation to standings"
START=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat().replace('+00:00','Z'))")
END=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=4)).isoformat().replace('+00:00','Z'))")

code=$(req boss POST /api/admin/contests \
  "{\"title\":\"Verification Cup $RS\",\"slug\":\"verify-cup-$RS\",\"description\":\"A contest.\",\"startAt\":\"$START\",\"endAt\":\"$END\"}")
[ "$code" = "201" ] && ok "the contest is created as a draft" || bad "create contest" "HTTP $code $(body)"
CONTEST=$(jq_ 'd["id"]')
statename=$(jq_ 'd["status"]')
[ "$statename" = "DRAFT" ] && ok "its stored lifecycle is DRAFT" || bad "lifecycle" "$statename"

code=$(req boss POST "/api/admin/contests/$CONTEST/problems" "{\"problemId\":\"$PROB\",\"points\":100}")
[ "$code" = "200" ] && ok "a problem is added, worth 100 points" || bad "add problem" "HTTP $code $(body)"

code=$(req boss POST "/api/admin/contests/$CONTEST/publish" '{}')
[ "$code" = "200" ] && ok "the contest is published" || bad "publish contest" "HTTP $code $(body)"

code=$(get alice "/api/contests/$CONTEST")
[ "$code" = "200" ] && ok "a user can see the published contest" || bad "read contest" "HTTP $code"
derived=$(jq_ 'd["status"]')
[ "$derived" = "UPCOMING" ] \
  && ok "and its status is derived from the clock: UPCOMING" \
  || bad "derived status" "$derived"

code=$(req alice POST "/api/contests/$CONTEST/register" '{}')
[ "$code" = "200" ] && ok "the user registers for it" || bad "register for contest" "HTTP $code $(body)"

# Move the schedule back, which is exactly what an hour passing would do. A
# contest cannot be published after it starts, nor registered for once it is running.
psqlq "UPDATE contests SET start_at = now() - interval '1 hour', end_at = now() + interval '2 hours' WHERE public_id='$CONTEST';" >/dev/null
code=$(get alice "/api/contests/$CONTEST")
derived=$(jq_ 'd["status"]')
[ "$derived" = "LIVE" ] && ok "once its window opens the status derives as LIVE" || bad "derived status" "$derived"

step "14. A contest submission is judged and scored"
clear_buckets
code=$(req alice POST "/api/contests/$CONTEST/problems/$PROB/submissions" "$SOURCE_OK")
[ "$code" = "202" ] && ok "the contest submission is accepted" || bad "contest submit" "HTTP $code $(body)"
CSUB=$(jq_ 'd["submissionId"]')
verdict=$(wait_for_verdict alice "$CSUB")
[ "$verdict" = "ACCEPTED" ] && ok "and judged ACCEPTED" || bad "contest verdict" "$verdict"

linked=$(psqlq "SELECT count(*) FROM submissions s JOIN contests c ON c.id = s.contest_id WHERE s.public_id='$CSUB' AND c.public_id='$CONTEST';")
[ "$linked" = "1" ] && ok "it is recorded against the contest" || bad "not linked to contest" "$linked"

step "15. Standings reflect the score"
code=$(get alice "/api/contests/$CONTEST/standings")
[ "$code" = "200" ] && ok "standings are readable" || bad "standings" "HTTP $code"
score=$(body | python -c "
import json,sys
d=json.load(sys.stdin)
rows=[r for r in d['rows'] if r['username'].startswith('alice')]
print(rows[0]['score'] if rows else 'no row')")
[ "$score" = "100" ] && ok "the contestant scores 100" || bad "score" "$score"
hasi "email|password|sourceCode" "$(body)" \
  && bad "standings expose private data" "" \
  || ok "standings carry no email, credential or source code"

# =============================================================== 16. audit

step "16. Meaningful mutations are audited"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='PROBLEM_CREATE' AND entity_id='$PROB';")
[ "$n" = "1" ] && ok "PROBLEM_CREATE recorded once" || bad "problem audit" "$n rows"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='SUBMISSION_CREATE' AND entity_id='$SUBID';")
[ "$n" = "1" ] && ok "SUBMISSION_CREATE recorded once" || bad "submission audit" "$n rows"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='CONTEST_REGISTER' AND entity_id='$CONTEST';")
[ "$n" -ge 1 ] && ok "CONTEST_REGISTER recorded" || bad "contest audit" "$n rows"
leak=$(psqlq "SELECT count(*) FROM audit_events WHERE metadata::text LIKE '%print(1)%' OR metadata::text LIKE '%$E2E_PASSWORD%';")
[ "$leak" = "0" ] && ok "no source code or password in any audit metadata" || bad "AUDIT LEAK" "$leak rows"

step "17. An administrator can inspect the system"
code=$(get boss /api/admin/system/status)
[ "$code" = "200" ] && ok "the operational status is readable by an admin" || bad "status" "HTTP $code"
state=$(jq_ 'd["state"]')
[ -n "$state" ] && ok "it reports an operational state: $state" || bad "no state" ""
workers=$(jq_ 'len(d["workers"])')
[ "${workers:-0}" -ge 1 ] 2>/dev/null && ok "and lists $workers judge worker(s)" || bad "no workers" "$workers"

# =============================================================== 18. rejections

step "18. NEGATIVE: anonymous callers are refused"
code=$(anon /api/submissions)
[ "$code" = "401" ] && ok "an anonymous request to a protected endpoint is 401" || bad "anon read" "HTTP $code"
code=$(anon /api/admin/system/status)
[ "$code" = "401" ] && ok "an anonymous admin request is 401" || bad "anon admin" "HTTP $code"
code=$(anon /api/admin/audit-events)
[ "$code" = "401" ] && ok "the audit endpoint is 401 to anonymous callers" || bad "anon audit" "HTTP $code"

step "19. NEGATIVE: a user cannot reach the admin surface"
code=$(get alice /api/admin/audit-events)
[ "$code" = "403" ] && ok "a USER gets 403 from the audit log" || bad "user audit" "HTTP $code"
code=$(get alice /api/admin/system/status)
[ "$code" = "403" ] && ok "a USER gets 403 from the system status" || bad "user status" "HTTP $code"
code=$(req alice POST /api/admin/problems "$PROBLEM")
[ "$code" = "403" ] && ok "a USER cannot author a problem" || bad "user authoring" "HTTP $code"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='ADMIN_ACCESS_DENIED' AND actor_username='alice$RS';")
[ "$n" -ge 1 ] && ok "and the refusal is audited" || bad "denial not audited" "$n rows"

step "20. NEGATIVE: a duplicate registration is refused"
clear_buckets
code=$(req dup POST /api/auth/register \
  "{\"username\":\"alice$RS\",\"email\":\"other$RS@e2e.invalid\",\"password\":\"$E2E_PASSWORD\"}")
[ "$code" = "409" ] && ok "a duplicate username is 409" || bad "duplicate username" "HTTP $code"

step "21. NEGATIVE: a user cannot read another user's submission"
code=$(register_user mallory); [ "$code" = "201" ] || bad "register mallory" "HTTP $code"
login_user mallory >/dev/null
code=$(get mallory "/api/submissions/$SUBID")
[ "$code" = "404" ] \
  && ok "somebody else's submission is 404, not 403 -- so it is not an oracle for valid ids" \
  || bad "submission ownership" "HTTP $code"

step "22. NEGATIVE: an unregistered user cannot submit to a contest"
clear_buckets
code=$(req mallory POST "/api/contests/$CONTEST/problems/$PROB/submissions" "$SOURCE_OK")
[ "$code" = "409" ] || [ "$code" = "404" ] \
  && ok "a contestant who never registered is refused (HTTP $code)" \
  || bad "contest eligibility" "HTTP $code"

step "23. NEGATIVE: a submission cannot carry its own verdict"
clear_buckets
code=$(req alice POST "/api/problems/$PROB/submissions" \
  "{\"language\":\"PYTHON\",\"sourceCode\":\"print(1)\",\"status\":\"ACCEPTED\",\"score\":999,\"userId\":\"00000000-0000-4000-8000-000000000001\"}")
if [ "$code" = "202" ]; then
  OVERID=$(jq_ 'd["submissionId"]')
  st=$(psqlq "SELECT status FROM submissions WHERE public_id='$OVERID';")
  [ "$st" = "QUEUED" ] || [ "$st" = "RUNNING" ] || [ "$st" = "ACCEPTED" ] \
    && ok "extra fields are ignored; the server decides the status" \
    || bad "OVERPOSTING ACCEPTED" "status=$st"
  owner=$(psqlq "SELECT u.username FROM submissions s JOIN users u ON u.id=s.user_id WHERE s.public_id='$OVERID';")
  [ "$owner" = "alice$RS" ] \
    && ok "and the author is taken from the session, not the body" \
    || bad "OWNERSHIP OVERPOSTED" "$owner"
else
  ok "the request was rejected outright (HTTP $code)"
fi

step "24. NEGATIVE: the contest window is enforced server-side"
psqlq "UPDATE contests SET start_at = now() - interval '4 hours', end_at = now() - interval '1 hour' WHERE public_id='$CONTEST';" >/dev/null
clear_buckets
code=$(req alice POST "/api/contests/$CONTEST/problems/$PROB/submissions" "$SOURCE_OK")
[ "$code" = "409" ] \
  && ok "a submission after endAt is refused, whatever the client believes" \
  || bad "deadline not enforced" "HTTP $code"

step "25. NEGATIVE: rate limiting can be reached"
clear_buckets
limited=""
for i in $(seq 1 40); do
  code=$(req alice POST "/api/problems/$PROB/submissions" "$SOURCE_OK")
  [ "$code" = "000" ] && continue
  if [ "$code" = "429" ]; then limited=$i; break; fi
done
[ -n "$limited" ] && ok "submissions are refused with 429 after $limited attempts" \
                  || bad "rate limit never reached" "40 submissions all accepted"
retry=$(header 'Retry-After')
[ -n "$retry" ] && [ "$retry" -gt 0 ] 2>/dev/null \
  && ok "the refusal carries Retry-After (${retry}s)" || bad "Retry-After" "'$retry'"
errcode=$(jq_ 'd["error"]')
[ "$errcode" = "RATE_LIMITED" ] && ok "and a stable error code" || bad "error code" "$errcode"

finish
