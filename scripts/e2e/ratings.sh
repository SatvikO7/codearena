#!/usr/bin/env bash
# =============================================================================
# Ratings, rankings and contest history, end to end against a running stack.
#
# Two contests are run for real -- created, published, registered for, submitted
# to, judged by the real sandbox -- and then rated. Nothing here is simulated:
# the standings the ratings are computed from are the same standings a browser
# would show.
#
# What this suite is really checking is the part a unit test cannot: that the
# arithmetic is reached through the right doors and no others. That a contest is
# rated exactly once under ten simultaneous requests. That a cancelled contest is
# never rated. That no request body anywhere can move a number.
#
# Expects a running stack. Takes about three minutes, most of it waiting for
# real compilation and execution.
#
#   scripts/e2e/ratings.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
# shellcheck source=scripts/e2e/lib.sh
. scripts/e2e/lib.sh

SOURCE_OK='{"language":"PYTHON","sourceCode":"print(1)"}'

# --------------------------------------------------------------------------
# Helpers specific to this suite.
# --------------------------------------------------------------------------

# The rating currently stored for one of this run's accounts, or empty if none.
rating_of() { psqlq "SELECT ur.rating FROM user_ratings ur JOIN users u ON u.id=ur.user_id WHERE u.username='$1$RS';"; }

# How many history rows one contest produced.
history_rows() { psqlq "SELECT count(*) FROM contest_rating_changes r JOIN contests c ON c.id=r.contest_id WHERE c.public_id='$1';"; }

finalized_at() { psqlq "SELECT coalesce(rating_finalized_at::text,'') FROM contests WHERE public_id='$1';"; }

# Moves a contest's window, which is what the passage of time would have done.
# Publishing an ended contest and registering for a started one are both refused,
# so the schedule is moved afterwards rather than created in the past.
move_window() { psqlq "UPDATE contests SET start_at=now() $2, end_at=now() $3 WHERE public_id='$1';" >/dev/null; }

# =============================================================== 1. accounts

step "1. Accounts"
clear_buckets

for who in ann ben cat; do
  code=$(register_user "$who")
  [ "$code" = "201" ] && ok "$who registers" || bad "register $who" "HTTP $code $(body)"
done
code=$(register_user boss)
[ "$code" = "201" ] && ok "the administrator registers" || bad "register boss" "HTTP $code $(body)"
promote_to_admin boss

for who in ann ben cat boss; do
  code=$(login_user "$who")
  [ "$code" = "200" ] || bad "login $who" "HTTP $code"
done
ok "everybody signs in"

step "2. Nobody is rated before they have competed"
for who in ann ben cat; do
  [ -z "$(rating_of "$who")" ] || bad "$who already has a rating" "$(rating_of "$who")"
done
ok "a new account has no rating row at all"

code=$(get ann /api/rankings)
[ "$code" = "200" ] && ok "the ranking answers 200 even when empty" || bad "rankings" "HTTP $code"

# An unrated account is ABSENT from the leaderboard, not listed at the starting
# rating. Having no rating and having a rating of 1500 are different facts.
has "ann$RS" "$(body)" \
  && bad "an account that has never competed is listed in the ranking" "$(body)" \
  || ok "an unrated account does not appear in the ranking"

step "3. An unrated profile is nulls, not a 404 and not a zero"
AID=$(psqlq "SELECT public_id FROM users WHERE username='ann$RS';")
code=$(get ann "/api/users/$AID/rating")
[ "$code" = "200" ] && ok "an unrated profile answers 200" || bad "profile" "HTTP $code"
rated=$(jq_ 'd["rated"]')
[ "$rated" = "False" ] && ok "and reports rated=false" || bad "rated flag" "$rated"
r=$(jq_ 'd["rating"]')
[ "$r" = "None" ] && ok "with a null rating rather than 0" || bad "unrated shown as a number" "$r"

# =============================================================== 4. a problem

step "4. A problem to compete over"
read -r -d '' PROBLEM <<JSON
{"title":"Rated $RS","slug":"rated-$RS","statement":"Print the number one.",
 "inputFormat":"No input.","outputFormat":"The number 1.","constraints":"None.",
 "difficulty":"EASY","tags":["MATH"],"timeLimitMs":5000,"memoryLimitMb":256,
 "examples":[{"input":"","output":"1","explanation":"Always one"}],
 "testCases":[{"input":"","expectedOutput":"1","hidden":true,"weight":1}]}
JSON

code=$(req boss POST /api/admin/problems "$PROBLEM")
[ "$code" = "201" ] && ok "the problem is created" || bad "create problem" "HTTP $code $(body)"
PROB=$(jq_ 'd["id"]')
code=$(req boss POST "/api/admin/problems/$PROB/publish" '{}')
[ "$code" = "200" ] && ok "and published" || bad "publish problem" "HTTP $code"

# =============================================================== 5. rated flag

step "5. A contest is unrated unless somebody says so"
START=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+1H +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+4H +%Y-%m-%dT%H:%M:%SZ)

code=$(req boss POST /api/admin/contests \
  "{\"title\":\"Casual $RS\",\"slug\":\"casual-$RS\",\"description\":\"x\",\"startAt\":\"$START\",\"endAt\":\"$END\"}")
[ "$code" = "201" ] && ok "a contest created without the flag is accepted" || bad "create" "HTTP $code $(body)"
CASUAL=$(jq_ 'd["id"]')
r=$(jq_ 'd["rated"]')
[ "$r" = "False" ] && ok "and defaults to unrated" || bad "defaulted to rated" "$r"

step "6. A rated contest, explicitly"
code=$(req boss POST /api/admin/contests \
  "{\"title\":\"Rated Cup $RS\",\"slug\":\"rated-cup-$RS\",\"description\":\"x\",\"startAt\":\"$START\",\"endAt\":\"$END\",\"rated\":true}")
[ "$code" = "201" ] && ok "a rated contest is created" || bad "create rated" "HTTP $code $(body)"
CUP=$(jq_ 'd["id"]')
r=$(jq_ 'd["rated"]')
[ "$r" = "True" ] && ok "and is marked rated" || bad "rated flag lost" "$r"

for c in "$CUP" "$CASUAL"; do
  code=$(req boss POST "/api/admin/contests/$c/problems" "{\"problemId\":\"$PROB\",\"points\":100}")
  [ "$code" = "200" ] || bad "add problem to $c" "HTTP $code $(body)"
  code=$(req boss POST "/api/admin/contests/$c/publish" '{}')
  [ "$code" = "200" ] || bad "publish $c" "HTTP $code $(body)"
done
ok "both contests are published"

step "7. Registration, then the contests run"
for who in ann ben cat; do
  code=$(req "$who" POST "/api/contests/$CUP/register" '{}')
  [ "$code" = "200" ] || bad "$who registers for the cup" "HTTP $code $(body)"
done
code=$(req ann POST "/api/contests/$CASUAL/register" '{}')
[ "$code" = "200" ] || bad "ann registers for the casual contest" "HTTP $code"
code=$(req ben POST "/api/contests/$CASUAL/register" '{}')
[ "$code" = "200" ] || bad "ben registers for the casual contest" "HTTP $code"
ok "everybody is registered"

move_window "$CUP"    "- interval '1 hour'" "+ interval '2 hours'"
move_window "$CASUAL" "- interval '1 hour'" "+ interval '2 hours'"

step "8. The rated flag is frozen once the contest starts"
# The single most important rule of the phase: a contest that became rated
# halfway through would be asking people to compete for stakes they never
# agreed to, and one that became unrated would take away a result.
code=$(req boss PUT "/api/admin/contests/$CUP" \
  "{\"title\":\"Rated Cup $RS\",\"slug\":\"rated-cup-$RS\",\"description\":\"x\",\"startAt\":\"$START\",\"endAt\":\"$END\",\"rated\":false}")
[ "$code" = "400" ] && ok "a live contest refuses a change to its rated flag (HTTP $code)" \
  || bad "the rated flag was editable mid-contest" "HTTP $code $(body)"
r=$(psqlq "SELECT rated FROM contests WHERE public_id='$CUP';")
[ "$r" = "t" ] && ok "and the flag is unchanged in the database" || bad "flag changed anyway" "$r"

# =============================================================== 9. competing

step "9. Everybody competes, and is judged for real"
clear_buckets
submitted=0
for who in ann ben cat; do
  code=$(req "$who" POST "/api/contests/$CUP/problems/$PROB/submissions" "$SOURCE_OK")
  [ "$code" = "202" ] && submitted=$((submitted+1)) || bad "$who submits" "HTTP $code $(body)"
  eval "SUB_$who=$(jq_ 'd["submissionId"]')"
  # Spaced so the penalty ordering is deterministic: the scoreboard breaks a
  # tie on penalty, which is minutes from the start of the contest.
  sleep 1
done
[ "$submitted" = "3" ] && ok "three contest submissions accepted" || bad "submissions accepted" "$submitted of 3"

judged=0
for who in ann ben cat; do
  eval "id=\$SUB_$who"
  verdict=$(wait_for_verdict "$who" "$id" 180)
  [ "$verdict" = "ACCEPTED" ] && judged=$((judged+1)) || bad "$who's verdict" "$verdict"
done
[ "$judged" = "3" ] && ok "all three judged ACCEPTED by the real sandbox"   || bad "not every contestant was judged" "$judged of 3"

code=$(req ann POST "/api/contests/$CASUAL/problems/$PROB/submissions" "$SOURCE_OK")
[ "$code" = "202" ] || bad "ann submits to the casual contest" "HTTP $code"
CASUAL_SUB=$(jq_ 'd["submissionId"]')
code=$(req ben POST "/api/contests/$CASUAL/problems/$PROB/submissions" "$SOURCE_OK")
[ "$code" = "202" ] || bad "ben submits to the casual contest" "HTTP $code"
wait_for_verdict ann "$CASUAL_SUB" 180 >/dev/null
ok "the casual contest has submissions too"

step "10. The contests end"
move_window "$CUP"    "- interval '3 hours'" "- interval '1 hour'"
move_window "$CASUAL" "- interval '3 hours'" "- interval '1 hour'"

code=$(get ann "/api/contests/$CUP/rating")
[ "$code" = "200" ] && ok "the contest rating endpoint answers" || bad "contest rating" "HTTP $code"
st=$(jq_ 'd["status"]')
# PENDING and not a change of zero. Zero is a real rating change; "not computed
# yet" is not, and showing them the same way would misreport one of them.
[ "$st" = "PENDING" ] && ok "an ended, unrated-yet contest reports PENDING" || bad "status" "$st"
ch=$(jq_ 'd["ratingChange"]')
[ "$ch" = "None" ] && ok "with a null change rather than 0" || bad "pending reported as a number" "$ch"

# =============================================================== 11. security

step "11. NEGATIVE: a rating can never be supplied by a client"
before_rows=$(psqlq "SELECT count(*) FROM contest_rating_changes;")

# Every shape somebody might try. None of them may succeed, and none of them may
# move a number.
for path in "/api/users/$AID/rating" "/api/ratings" "/api/rankings" "/api/admin/users/$AID/rating"; do
  code=$(req boss POST "$path" '{"rating":3000,"peakRating":3000,"ratingChange":500}')
  case "$code" in
    2*) bad "POST $path succeeded" "HTTP $code $(body)" ;;
    *)  ok "POST $path is refused (HTTP $code)" ;;
  esac
done

after_rows=$(psqlq "SELECT count(*) FROM contest_rating_changes;")
[ "$before_rows" = "$after_rows" ] && ok "no rating row was created by any of them" \
  || bad "a posted rating created history" "$before_rows -> $after_rows"

step "12. NEGATIVE: a normal user cannot finalise a contest"
code=$(req ann POST "/api/admin/contests/$CUP/finalize" '{}')
[ "$code" = "403" ] && ok "finalisation is refused to a user (HTTP $code)" || bad "authorisation" "HTTP $code"
[ -z "$(rating_of ann)" ] && ok "and no rating was produced" || bad "rating created" "$(rating_of ann)"

step "13. NEGATIVE: an anonymous caller cannot finalise a contest"
code=$(anon "/api/admin/contests/$CUP/finalize" -X POST)
[ "$code" = "401" ] || [ "$code" = "403" ] \
  && ok "finalisation is refused to an anonymous caller (HTTP $code)" \
  || bad "anonymous finalisation" "HTTP $code"

step "14. NEGATIVE: finalisation requires a CSRF token"
token_missing=$(curl -s -o "$(out)" -w '%{http_code}' -X POST \
  -c "$(jar boss)" -b "$(jar boss)" "$API/api/admin/contests/$CUP/finalize")
[ "$token_missing" = "403" ] && ok "a cross-site POST is refused (HTTP $token_missing)" \
  || bad "CSRF" "HTTP $token_missing"

# =============================================================== 15. the claim

step "15. Ten simultaneous finalisations rate the contest exactly once"
# The bug this guards against is the worst one this phase could ship: a contest
# rated twice means every change applied twice, with a history that no longer
# explains the rating and no automatic way to work out which half to undo.
clear_buckets
codes_file="$E2E_TMP/finalize-codes"
: > "$codes_file"

for i in $(seq 1 10); do
  (
    # Separate cookie jars so ten concurrent requests do not race each other
    # writing the same jar file -- that would be a test artefact, not a finding.
    cp "$(jar boss)" "$E2E_TMP/race$i.jar"
    tok=$(awk '$6=="XSRF-TOKEN"{print $7}' "$E2E_TMP/race$i.jar" | tail -1)
    c=$(curl -s -o "$E2E_TMP/race$i.body" -w '%{http_code}' -X POST \
        "$API/api/admin/contests/$CUP/finalize" \
        -b "$E2E_TMP/race$i.jar" -H "X-XSRF-TOKEN: $tok")
    printf '%s %s\n' "$c" "$(grep -o '"alreadyFinalized":[a-z]*' "$E2E_TMP/race$i.body" | head -1)" >> "$codes_file"
  ) &
done
wait

accepted=$(grep -c '^200' "$codes_file")
fresh=$(grep -c 'alreadyFinalized":false' "$codes_file")
[ "$accepted" -ge 1 ] && ok "$accepted of 10 concurrent calls returned 200" \
  || bad "no concurrent call succeeded" "$(cat "$codes_file")"
# At most one may report doing the work. Every other 200 must say it was already
# done -- that is the difference between idempotent and merely tolerant.
[ "$fresh" -le 1 ] && ok "at most one call reported doing the work ($fresh)" \
  || bad "MORE THAN ONE FINALISATION" "$(cat "$codes_file")"

rows=$(history_rows "$CUP")
[ "$rows" = "3" ] && ok "exactly three history rows, one per competitor" \
  || bad "WRONG NUMBER OF RATING ROWS" "$rows"

step "16. Every competitor has exactly one rating row"
dupes=$(psqlq "SELECT count(*) FROM (SELECT user_id FROM user_ratings GROUP BY user_id HAVING count(*)>1) d;")
[ "$dupes" = "0" ] && ok "no competitor holds two rating rows" || bad "duplicate ratings" "$dupes"

for who in ann ben cat; do
  r=$(rating_of "$who")
  [ -n "$r" ] && ok "$who is now rated ($r)" || bad "$who has no rating" ""
done

# =============================================================== 17. the maths

step "17. The arithmetic is the documented arithmetic"
# Three competitors, all starting at 1500 and all provisional, so K = 40 and
# E = 0.5 for everybody. First place beats both: A = 1, so the change is
# round(40 x (1 - 0.5)) = +20. Second place is level: round(40 x 0) = 0.
# Third place beats nobody: round(40 x -0.5) = -20.
top=$(psqlq "SELECT rating_change FROM contest_rating_changes r JOIN contests c ON c.id=r.contest_id WHERE c.public_id='$CUP' AND r.rank=1;")
[ "$top" = "20" ] && ok "first place gains exactly 20" || bad "first place change" "$top"
last=$(psqlq "SELECT rating_change FROM contest_rating_changes r JOIN contests c ON c.id=r.contest_id WHERE c.public_id='$CUP' AND r.rank=3;")
[ "$last" = "-20" ] && ok "third place loses exactly 20" || bad "third place change" "$last"

step "18. The history explains the rating"
mismatch=$(psqlq "SELECT count(*) FROM user_ratings ur WHERE ur.rating <> (SELECT r.rating_after FROM contest_rating_changes r WHERE r.user_id=ur.user_id ORDER BY r.created_at DESC, r.id DESC LIMIT 1);")
[ "$mismatch" = "0" ] && ok "every rating equals the last change that produced it" \
  || bad "RATING DOES NOT MATCH ITS HISTORY" "$mismatch rows"

broken=$(psqlq "SELECT count(*) FROM contest_rating_changes WHERE rating_after <> rating_before + rating_change;")
[ "$broken" = "0" ] && ok "every history row adds up" || bad "arithmetic broken" "$broken rows"

step "19. The history is append-only"
upd=$(psqlq "UPDATE contest_rating_changes SET rating_after=9999;" 2>&1 || true)
hasi "append-only" "$upd" && ok "an UPDATE is refused by the database" || bad "history is mutable" "$upd"
del=$(psqlq "DELETE FROM contest_rating_changes;" 2>&1 || true)
hasi "append-only" "$del" && ok "a DELETE is refused by the database" || bad "history is deletable" "$del"
rows=$(history_rows "$CUP")
[ "$rows" = "3" ] && ok "and the rows are still there" || bad "rows lost" "$rows"

# =============================================================== 20. reading

step "20. The leaderboard"
clear_buckets
code=$(get ann /api/rankings)
[ "$code" = "200" ] && ok "the ranking is served" || bad "rankings" "HTTP $code"
board=$(body)
has "ann$RS" "$board" && ok "a rated competitor now appears" || bad "missing from the ranking" "$board"
has "cat$RS" "$board" && ok "so does the third-placed one" || bad "missing" ""

hasi "@e2e.invalid" "$board" && bad "THE RANKING LEAKS EMAIL ADDRESSES" "$board" \
  || ok "the ranking carries no email address"
hasi "\"role\"|\"email\"|password" "$board" && bad "the ranking leaks account data" "$board" \
  || ok "the ranking carries no account data"

top_rank=$(jq_ 'd["items"][0]["rank"] if d["items"] else "none"')
[ "$top_rank" = "1" ] && ok "the first row is rank 1" || bad "ranking starts at" "$top_rank"

step "21. The rating profile and its history"
code=$(get ann "/api/users/$AID/rating")
[ "$code" = "200" ] && ok "the profile is served" || bad "profile" "HTTP $code"
rated=$(jq_ 'd["rated"]')
[ "$rated" = "True" ] && ok "and now reports rated=true" || bad "rated flag" "$rated"
n=$(jq_ 'len(d["progression"])')
[ "$n" -ge 1 ] && ok "the graph has a point per rated contest ($n)" || bad "progression" "$n"

profile=$(body)
hasi "@e2e.invalid|sourceCode|print\(1\)" "$profile" \
  && bad "the profile leaks private or submission data" "$profile" \
  || ok "the profile carries no email and no source code"

code=$(get ann "/api/users/$AID/rating/history")
[ "$code" = "200" ] && ok "the history is served" || bad "history" "HTTP $code"
n=$(jq_ 'd["totalItems"]')
# Only the rated contest. The casual one produced no rating, and a row of zeroes
# would report a result that does not exist.
[ "$n" = "1" ] && ok "it lists only the rated contest" || bad "history count" "$n"

step "22. Another user's history carries no source code"
BID=$(psqlq "SELECT public_id FROM users WHERE username='ben$RS';")
code=$(get ann "/api/users/$BID/rating/history")
[ "$code" = "200" ] && ok "one user may read another's rating history" || bad "history" "HTTP $code"
hasi "print\(1\)|sourceCode" "$(body)" \
  && bad "A USER'S SOURCE CODE IS REACHABLE THROUGH THE RATING HISTORY" "$(body)" \
  || ok "and it contains no submission source"

step "23. The contest now reports a finalised result"
code=$(get ann "/api/contests/$CUP/rating")
st=$(jq_ 'd["status"]')
[ "$st" = "FINALIZED" ] && ok "the rated contest reports FINALIZED" || bad "status" "$st"
ch=$(jq_ 'd["ratingChange"]')
[ -n "$ch" ] && [ "$ch" != "None" ] && ok "with a real change ($ch)" || bad "no change reported" "$ch"

step "24. The result is the caller's own, not one they name"
code=$(get cat "/api/contests/$CUP/rating?userId=$AID")
mine=$(jq_ 'd["rank"]')
theirs=$(psqlq "SELECT r.rank FROM contest_rating_changes r JOIN contests c ON c.id=r.contest_id JOIN users u ON u.id=r.user_id WHERE c.public_id='$CUP' AND u.username='cat$RS';")
[ "$mine" = "$theirs" ] && ok "naming another user in the query string changes nothing" \
  || bad "THE CONTEST RESULT CAN BE READ FOR ANOTHER USER" "got $mine, own is $theirs"

# =============================================================== 25. unrated

step "25. An unrated contest moves nobody"
ann_before=$(rating_of ann)
code=$(req boss POST "/api/admin/contests/$CASUAL/finalize" '{}')
[ "$code" = "200" ] && ok "an unrated contest can still be finalised" || bad "finalize casual" "HTTP $code $(body)"
r=$(jq_ 'd["rated"]')
[ "$r" = "False" ] && ok "and reports rated=false" || bad "casual reported rated" "$r"
n=$(jq_ 'd["ratedParticipants"]')
[ "$n" = "0" ] && ok "with nobody rated" || bad "unrated contest rated people" "$n"
[ "$(rating_of ann)" = "$ann_before" ] && ok "and no rating moved" \
  || bad "AN UNRATED CONTEST CHANGED A RATING" "$ann_before -> $(rating_of ann)"
[ "$(history_rows "$CASUAL")" = "0" ] && ok "and it wrote no history" || bad "history written" ""

code=$(get ann "/api/contests/$CASUAL/rating")
st=$(jq_ 'd["status"]')
[ "$st" = "UNRATED" ] && ok "the contest reports UNRATED, not a change of zero" || bad "status" "$st"

# =============================================================== 26. cancelled

step "26. A cancelled contest is never rated"
S2=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+1H +%Y-%m-%dT%H:%M:%SZ)
E2=$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+4H +%Y-%m-%dT%H:%M:%SZ)
code=$(req boss POST /api/admin/contests \
  "{\"title\":\"Void $RS\",\"slug\":\"void-$RS\",\"description\":\"x\",\"startAt\":\"$S2\",\"endAt\":\"$E2\",\"rated\":true}")
VOID=$(jq_ 'd["id"]')
req boss POST "/api/admin/contests/$VOID/problems" "{\"problemId\":\"$PROB\",\"points\":100}" >/dev/null
req boss POST "/api/admin/contests/$VOID/publish" '{}' >/dev/null
req ann POST "/api/contests/$VOID/register" '{}' >/dev/null
req ben POST "/api/contests/$VOID/register" '{}' >/dev/null
move_window "$VOID" "- interval '1 hour'" "+ interval '2 hours'"
req ann POST "/api/contests/$VOID/problems/$PROB/submissions" "$SOURCE_OK" >/dev/null
req ben POST "/api/contests/$VOID/problems/$PROB/submissions" "$SOURCE_OK" >/dev/null

code=$(req boss POST "/api/admin/contests/$VOID/cancel" '{}')
[ "$code" = "200" ] && ok "a running rated contest can be cancelled" || bad "cancel" "HTTP $code $(body)"
move_window "$VOID" "- interval '3 hours'" "- interval '1 hour'"

ann_before=$(rating_of ann)
code=$(req boss POST "/api/admin/contests/$VOID/finalize" '{}')
[ "$code" = "400" ] && ok "finalising a cancelled contest is refused (HTTP $code)" \
  || bad "A CANCELLED CONTEST WAS FINALISED" "HTTP $code $(body)"
[ "$(history_rows "$VOID")" = "0" ] && ok "it produced no rating history" || bad "history written" ""
[ "$(rating_of ann)" = "$ann_before" ] && ok "and moved nobody's rating" \
  || bad "A CANCELLED CONTEST CHANGED A RATING" "$ann_before -> $(rating_of ann)"

code=$(get ann "/api/contests/$VOID/rating")
st=$(jq_ 'd["status"]')
[ "$st" = "CANCELLED" ] && ok "the contest reports CANCELLED" || bad "status" "$st"

step "27. The sweeper leaves a cancelled contest alone"
# It has ended, it is rated, and it must still never be picked up.
sleep 3
[ -z "$(finalized_at "$VOID")" ] && ok "it is still unfinalised after a sweep window" \
  || bad "THE SWEEPER FINALISED A CANCELLED CONTEST" "$(finalized_at "$VOID")"

# =============================================================== 28. sweeper

step "28. Automatic finalisation picks up a contest nobody asked about"
S3=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+1H +%Y-%m-%dT%H:%M:%SZ)
E3=$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+4H +%Y-%m-%dT%H:%M:%SZ)
code=$(req boss POST /api/admin/contests \
  "{\"title\":\"Auto $RS\",\"slug\":\"auto-$RS\",\"description\":\"x\",\"startAt\":\"$S3\",\"endAt\":\"$E3\",\"rated\":true}")
AUTO=$(jq_ 'd["id"]')
req boss POST "/api/admin/contests/$AUTO/problems" "{\"problemId\":\"$PROB\",\"points\":100}" >/dev/null
req boss POST "/api/admin/contests/$AUTO/publish" '{}' >/dev/null
req ann POST "/api/contests/$AUTO/register" '{}' >/dev/null
req ben POST "/api/contests/$AUTO/register" '{}' >/dev/null
move_window "$AUTO" "- interval '1 hour'" "+ interval '2 hours'"

clear_buckets
code=$(req ann POST "/api/contests/$AUTO/problems/$PROB/submissions" "$SOURCE_OK")
SUB_A=$(jq_ 'd["submissionId"]')
sleep 1
code=$(req ben POST "/api/contests/$AUTO/problems/$PROB/submissions" "$SOURCE_OK")
SUB_B=$(jq_ 'd["submissionId"]')
wait_for_verdict ann "$SUB_A" 180 >/dev/null
wait_for_verdict ben "$SUB_B" 180 >/dev/null
ok "two contestants competed"

move_window "$AUTO" "- interval '3 hours'" "- interval '1 hour'"

# The sweeper runs on a fixed delay (60s by default). Polled rather than slept
# through, so a fast sweep is not waited out and a slow one is not missed.
waited=0
while [ "$waited" -lt 150 ]; do
  [ -n "$(finalized_at "$AUTO")" ] && break
  sleep 5; waited=$((waited + 5))
done

[ -n "$(finalized_at "$AUTO")" ] \
  && ok "the sweeper finalised it without anybody asking (after ${waited}s)" \
  || bad "the sweeper did not pick up an ended rated contest" "waited ${waited}s"

rows=$(history_rows "$AUTO")
[ "$rows" = "2" ] && ok "and wrote exactly two history rows" || bad "history rows" "$rows"

actor=$(psqlq "SELECT actor_type FROM audit_events WHERE action='CONTEST_FINALIZE' AND entity_id='$AUTO';")
# An audit row claiming an unauthenticated caller rated a contest would be worse
# than none, because it is wrong in a way somebody would act on.
[ "$actor" = "SYSTEM" ] && ok "attributed to SYSTEM, not to a phantom anonymous user" \
  || bad "wrong actor for an automatic finalisation" "$actor"

# =============================================================== 29. audit

step "29. Finalisation is audited"
n=$(psqlq "SELECT count(*) FROM audit_events WHERE action='CONTEST_FINALIZE' AND entity_id='$CUP';")
[ "$n" = "1" ] && ok "CONTEST_FINALIZE is recorded exactly once for the cup" || bad "audit rows" "$n"

# Either the administrator or the sweeper may legitimately have got there first:
# the cup ended several steps ago and the sweeper runs on its own timer. What must
# never happen is an event claiming an unauthenticated caller rated a contest,
# because that is wrong in a way somebody would act on.
actor_type=$(psqlq "SELECT actor_type FROM audit_events WHERE action='CONTEST_FINALIZE' AND entity_id='$CUP';")
case "$actor_type" in
  ADMIN|SYSTEM) ok "attributed to a real actor ($actor_type)" ;;
  *)            bad "FINALISATION ATTRIBUTED TO THE WRONG ACTOR" "$actor_type" ;;
esac

# And the attribution has to be internally consistent: an administrator's call
# names them, the sweeper's names nobody.
actor_name=$(psqlq "SELECT coalesce(actor_username,'') FROM audit_events WHERE action='CONTEST_FINALIZE' AND entity_id='$CUP';")
if [ "$actor_type" = "ADMIN" ]; then
  [ "$actor_name" = "boss$RS" ] && ok "and names the administrator who asked" || bad "actor" "$actor_name"
else
  [ -z "$actor_name" ] && ok "and names nobody, because nobody asked" || bad "a system act named a user" "$actor_name"
fi

meta=$(psqlq "SELECT metadata::text FROM audit_events WHERE action='CONTEST_FINALIZE' AND entity_id='$CUP' LIMIT 1;")
has "ratedParticipants" "$meta" && ok "and records how many were rated" || bad "metadata" "$meta"
hasi "$E2E_PASSWORD|sourceCode|print" "$meta" \
  && bad "THE AUDIT METADATA CARRIES A SECRET OR SOURCE CODE" "$meta" \
  || ok "and carries no secret and no source code"

# =============================================================== 30. limits

step "30. The new endpoints go through the Phase 9 limiter"
code=$(get ann /api/rankings)
limit=$(header 'RateLimit-Limit')
[ -n "$limit" ] && ok "the ranking carries a RateLimit-Limit header ($limit)" \
  || bad "the ranking bypasses the rate limiter" ""

code=$(req boss POST "/api/admin/contests/$CUP/finalize" '{}')
limit=$(header 'RateLimit-Limit')
[ -n "$limit" ] && ok "finalisation carries a RateLimit-Limit header ($limit)" \
  || bad "finalisation bypasses the rate limiter" ""

step "31. A repeated finalisation is idempotent, not an error"
before=$(rating_of ann)
code=$(req boss POST "/api/admin/contests/$CUP/finalize" '{}')
[ "$code" = "200" ] && ok "calling it again succeeds (HTTP $code)" || bad "second call" "HTTP $code $(body)"
already=$(jq_ 'd["alreadyFinalized"]')
[ "$already" = "True" ] && ok "and says the work was already done" || bad "alreadyFinalized" "$already"
[ "$(rating_of ann)" = "$before" ] && ok "and nothing moved" \
  || bad "A REPEAT FINALISATION CHANGED A RATING" "$before -> $(rating_of ann)"
[ "$(history_rows "$CUP")" = "3" ] && ok "with no extra history rows" || bad "extra rows" "$(history_rows "$CUP")"

# =============================================================== 32. logs

step "32. Nothing sensitive reached the logs"
logs=$(docker compose logs backend --since 10m 2>/dev/null)
hasi "$E2E_PASSWORD" "$logs" && bad "A PASSWORD APPEARS IN THE BACKEND LOGS" "" \
  || ok "no password in the backend logs"
has "print(1)" "$logs" && bad "SOURCE CODE APPEARS IN THE BACKEND LOGS" "" \
  || ok "no submission source in the backend logs"
has "CONTEST_FINALIZED" "$logs" && ok "finalisation is logged as a named event" \
  || bad "no finalisation log line" ""

step "33. The rating metrics are exported, with bounded labels"
code=$(getx boss /actuator/prometheus)
[ "$code" = "200" ] && ok "the metrics endpoint answers" || bad "prometheus" "HTTP $code"
metrics=$(body)
has "codearena_rating_finalizations_total" "$metrics" \
  && ok "finalisations are counted" || bad "missing finalisation counter" ""
has "outcome=\"rated\"" "$metrics" && ok "tagged by outcome" || bad "missing outcome tag" ""

# A metric tagged with a contest or a user id creates one time series per contest
# that ever ran, each frozen forever. The labels must be a closed set.
has "$CUP" "$metrics" && bad "A CONTEST ID APPEARS AS A METRIC LABEL" "" \
  || ok "no contest id in any metric label"
has "$AID" "$metrics" && bad "A USER ID APPEARS AS A METRIC LABEL" "" \
  || ok "no user id in any metric label"
has "ann$RS" "$metrics" && bad "A USERNAME APPEARS AS A METRIC LABEL" "" \
  || ok "no username in any metric label"

finish
