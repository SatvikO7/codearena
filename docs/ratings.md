# Ratings, rankings and contest history

How CodeArena turns a finished contest into a number, and why it is the number it is.

This document is written so that a competitor who disagrees with their rating change can
check it themselves. That is the whole design goal: nobody can look at −17 and tell whether it
should have been −18, so the only defence against a silent error is arithmetic somebody can
repeat with a calculator.

---

## 1. The short version

- Every competitor starts at **1500**, but only once they have completed a rated contest.
  Before that they have **no rating at all**, which is a different fact from having 1500.
- A contest is **rated** or **unrated**, decided before it starts and **frozen the moment it
  begins**.
- When a rated contest ends, everybody who **submitted something** is rated against everybody
  else who did, using a pairwise Elo formula stated in full below.
- Ratings are computed **exactly once** per contest, in **one transaction**, from the
  **authoritative final standings**. Nothing a client sends can influence them.
- Every change is written to an **append-only history** that the database refuses to update or
  delete. Your rating is always the `ratingAfter` of your most recent history row.

---

## 2. The formula

A contest is treated as a round robin: every participant "plays" every other participant once,
and the result of each pairing is decided by their **competition ranks** from the scoreboard.

For a field of `n` eligible participants, and participant *i* with rating *R<sub>i</sub>*:

```
Expected score against one opponent j:

    E(i, j) = 1 / (1 + 10 ^ ((R_j − R_i) / 400))

Expected score over the field:

    E_i = ( Σ_{j ≠ i} E(i, j) ) / (n − 1)

Actual score over the field:

    A_i = ( wins_i + 0.5 × ties_i ) / (n − 1)

      where wins_i = number of j with rank_j > rank_i   (a larger rank is worse)
            ties_i = number of j with rank_j = rank_i

Rating change:

    Δ_i  = round( K_i × (A_i − E_i) )
    R'_i = R_i + Δ_i
```

### Constants

| Constant | Value | Why |
| --- | --- | --- |
| Starting rating | 1500 | Elo convention, and it leaves room to fall. A scale from zero makes one bad contest look catastrophic. |
| Scale | 400 | Elo's constant: 400 points is roughly a 10:1 expectation. |
| K, provisional | 40 | First **5** rated contests. A new rating is mostly a guess and should move quickly toward the truth. |
| K, established | 20 | The normal case. |
| K, elite | 10 | At **2400** and above. The field is small and the sample thin; one unlucky contest should not undo a season. |
| Minimum field | 2 | Below that there is nobody to be measured against, and `n − 1` is zero. |

Provisional is checked **before** elite, so a newcomer who happens to be rated highly still
moves at K = 40 — otherwise a high seeding would confirm itself.

### Rounding

`BigDecimal.setScale(0, HALF_UP)`: half rounds **away from zero**, so +2.5 → +3 and
−2.5 → −3. Deliberately not `Math.round`, which computes `floor(x + 0.5)` and would round
−2.5 to −2 — quietly favouring whoever is losing points.

---

## 3. Worked examples

Every one of these is checked by a test in `RatingCalculatorTest`, with the arithmetic in a
comment beside it.

### Two evenly matched established competitors

Both 1500, both past their fifth contest, so K = 20 and E = 0.5 for each.

| | E | A | Δ | New rating |
| --- | --- | --- | --- | --- |
| Winner | 0.5 | 1.0 | round(20 × 0.5) = **+10** | 1510 |
| Loser | 0.5 | 0.0 | round(20 × −0.5) = **−10** | 1490 |

### The same pair, but both are newcomers

K = 40 instead of 20, so the changes are **+20** and **−20**. Same expectation, twice the
movement.

### A tie between equals

A = 0.5 (one tie, worth a half), E = 0.5, so Δ = round(20 × 0) = **0**. Nobody moves, and the
page shows `+0` rather than a blank — zero is a real result.

### The favourite wins as expected

1900 against 1500. E(high) = 1 / (1 + 10<sup>−1</sup>) = 10/11 ≈ 0.90909.

| | E | A | Δ |
| --- | --- | --- | --- |
| 1900, wins | 0.90909 | 1.0 | round(20 × 0.09091) = **+2** |
| 1500, loses | 0.09091 | 0.0 | round(20 × −0.09091) = **−2** |

### The upset

The same pair, opposite result: **+18** for the 1500, **−18** for the 1900. This is the
property that makes Elo worth using — the size of the change is the size of the surprise.

### Three equals finishing 1–2–3

Everybody 1500, K = 20, two opponents each, so E = 0.5 for all three.

| Rank | A | Δ |
| --- | --- | --- |
| 1st | 2/2 = 1.0 | **+10** |
| 2nd | 1/2 = 0.5 | **0** |
| 3rd | 0/2 = 0.0 | **−10** |

### A tie for first

Ranks 1, 1, 3 — competition ranking, so second place does not exist.

| Rank | A | Δ |
| --- | --- | --- |
| 1st (tied) | (0.5 + 1.0)/2 = 0.75 | **+5** |
| 1st (tied) | 0.75 | **+5** |
| 3rd | 0/2 = 0 | **−10** |

### An overwhelming favourite gains nothing

2400 against 1000 — a 1400-point gap, so E ≈ 0.99968, and K is 10 in the elite band:
round(10 × 0.00032) = **0**. That is correct, not a bug. Beating somebody 1400 points below
you is worth no information about your rating.

This is also why a rating rarely falls very far very fast: losing to somebody hundreds of
points above you rounds to nothing. Reaching zero from 1500 takes roughly **150 consecutive
defeats by an equal**.

---

## 4. What the formula does not do

**Rating is not conserved.** Before rounding the system is exactly zero-sum — Σ A = Σ E,
because both count each unordered pair once — so Σ (A − E) = 0. Two things break the sum
afterwards, both on purpose:

1. **K varies per participant.** A newcomer's +30 is not paid for by three veterans' −10.
2. **Each change is rounded independently.**

Forcing conservation would mean taking points from somebody to pay for a rounding error.

**There is no floor and no ceiling.** A rating can, in principle, go negative. A floor would
mean the system declining to record a result it had computed, and a competitor sitting on the
floor could lose indefinitely at no cost — precisely when their rating most needs to keep
moving.

**There is no decay.** A rating that has not moved in a year is still that competitor's last
measured result. Decaying it would be inventing evidence.

**There is no predicted or provisional rating change during a contest.** A number labelled
"unofficial" is still a number people will quote at each other, and it would be wrong as often
as the standings moved.

See [ADR-043](decisions.md) for the full reasoning, including why this is not a
Codeforces-style seed calculation.

---

## 5. Who gets rated

**Anyone who submitted at least one thing** in the contest, whatever the verdict.

- **Registering is not competing.** Registration is free and reversible up to the start. Rating
  a no-show would take points from somebody for a contest they never opened — and would let
  anybody pad a field with registrations to move other people's ratings.
- **A wrong answer still counts.** So does a compile error. The question is whether you turned
  up, not how it went.
- **A field of one produces nothing.** Not an error — a contest one person entered is a real
  contest. It simply says nothing about how they did.
- **A cancelled contest is never rated**, whatever its standings show. The scoreboard stays
  readable as a record of what happened; what is withheld is the consequence.

Ranks are **not renumbered** after no-shows are filtered out. A rating change has to be
explained by the standings somebody can look at. This is safe as well as honest: a no-show
scores zero, and since penalty is only charged on solved problems, carries no penalty either —
so removing one cannot change anybody else's rank.

See [ADR-045](decisions.md).

---

## 6. Rated or unrated, and when it is decided

A contest carries a `rated` flag.

- It **defaults to false**. A contest accidentally created unrated is fixed with an edit
  before it starts; one accidentally created rated has already put everybody's rating at stake
  in what was meant to be a practice round.
- It can be changed while the contest is **DRAFT or UPCOMING**.
- It is **frozen the moment the contest goes LIVE**, by the same check that protects the
  schedule, the problem set and the points. **There is no override.** A contest that became
  rated halfway through would be asking people to compete for stakes they never agreed to; one
  that became unrated would take away a result somebody had earned.

An **unrated contest is still finalised** — it simply produces no rating changes. "Finalised"
and "changed somebody's rating" are different facts, and the schema keeps them apart:
`rating_finalized_at` answers the first, `rated` answers the second.

---

## 7. How finalisation happens

```
BEGIN
  claim the contest            ← one conditional UPDATE; only one caller wins
  compute the final standings  ← from the database, never from a request
  compute the rating changes   ← RatingCalculator, pure
  update every user's rating
  insert every history row
  record the participant count
COMMIT
```

**All of it, or none of it.** If anything throws, the transaction rolls back, the claim goes
with it, and the contest is not finalised. There is no path that rates half the field — a
contest where forty of eighty competitors had been rated would be unrecoverable by any
automatic means, because there would be no way to tell which forty.

### The claim

```sql
UPDATE contests SET rating_finalized_at = :now
 WHERE id = :id AND rating_finalized_at IS NULL
```

PostgreSQL serialises concurrent updates to a row, so of several callers running this exactly
one updates a row and the rest update none. The return value **is** the decision. Behind it,
`uq_rating_changes_contest_user` makes a double rating physically impossible rather than
merely unlikely.

An integration test runs **ten simultaneous finalisations** and asserts that exactly one does
the work, nine are told it was already done, and the history has one row per competitor. The
end-to-end suite does the same over real HTTP.

### Three ways in

| Route | When | Audited as |
| --- | --- | --- |
| The sweeper | Every 60s, and once at startup | SYSTEM |
| `POST /api/admin/contests/{id}/finalize` | An administrator asks | that administrator |
| A retry of either | Any time | whichever asked |

All three are the same operation. There is deliberately no "force", no "recalculate" and no
"override": every one of those would be a second way for a rating to change, and a rating with
two possible provenances is a rating whose history no longer explains it.

### Automatic finalisation

The sweeper asks the database *which contests have ended, are rated, and have no
finalisation*. It does not schedule anything. A scheduled callback lives in one process's
memory, so a restart, a deploy or a lost instance means the contest ends with nobody
listening — and nothing notices until a competitor asks why their rating did not move.

The query cannot be wrong: it is correct after a restart, after a crash, and on a brand-new
instance that has never heard of the contest. It runs on `ApplicationReadyEvent` as well as on
the timer, so contests that ended during downtime are picked up immediately. `BATCH_SIZE` is
20 per sweep, so a backlog is worked through over several sweeps rather than in one burst.

Running several application instances needs no lock: they may all see the same contest, and
the claim means one wins.

Set `CODEARENA_RATING_AUTO_FINALIZE=false` (or `codearena.rating.auto-finalize`) to turn it
off; contests then wait for an administrator. The interval is
`codearena.rating.sweep-interval-ms`, default 60000.

See [ADR-044](decisions.md).

---

## 8. The data

### `user_ratings` — the current standing

One row per competitor, created on their **first** rated contest. An account that has never
competed has no row, which is what makes "unrated" a real state rather than a default value
indistinguishable from somebody sitting at 1500.

| Column | Notes |
| --- | --- |
| `rating` | Current. |
| `peak_rating` | Never decreases. `CHECK (peak_rating >= rating)`. |
| `contests_rated` | Used for the K-factor. |
| `last_rated_contest_id`, `last_rated_at` | The most recent result. |

**This table is a cache; the history is the truth.** `rating` is a fold over the history rows.
Keeping it here makes the leaderboard an indexed scan rather than an aggregation over every
rating change ever recorded — and an invariant test asserts that every user's rating equals
the `rating_after` of their most recent change.

### `contest_rating_changes` — the history

Append-only, enforced by a PostgreSQL trigger that raises on UPDATE and DELETE. The same
mechanism as the audit log.

Each row records `rating_before`, `rating_after`, `rating_change`, `rank`,
`participant_count`, `score`, `penalty`, `expected_score`, `actual_score` and `k_factor` — the
whole input and output of the calculation, so any change can be re-derived and checked.

Constraints that the database enforces regardless of what the application believes:

- `CHECK (rating_after = rating_before + rating_change)`
- `CHECK (rank >= 1)` and `CHECK (participant_count >= rank)`
- `UNIQUE (contest_id, user_id)` — nobody can be rated twice for one contest

The table has **no foreign keys**, deliberately. A cascading delete would issue a DELETE that
the append-only trigger refuses, which would make deleting a user impossible. The history
outlives the rows it refers to, which is what an append-only record is for.

### `contests` — three new columns

`rated`, `rating_finalized_at`, `rated_participant_count`, with a check that the last two move
together, and a partial index `ix_contests_awaiting_finalisation` over exactly the sweeper's
predicate.

Migration: `V9__create_ratings.sql`.

---

## 9. The API

Every rating endpoint is a **read**, except the administrative finalisation — and that takes
**no request body**, because every input comes from the database.

**There is no endpoint that accepts a rating.** Not one that validates it and rejects bad
values: one that does not exist. A rating moves in exactly one place, inside a contest
finalisation, from standings the database computed.

| Endpoint | Who | What |
| --- | --- | --- |
| `GET /api/rankings` | Any signed-in user | A page of the global ranking. `size` clamped to 100. |
| `GET /api/users/{id}/rating` | Any signed-in user | Rating, peak, rank, recent results, progression. |
| `GET /api/users/{id}/rating/history` | Any signed-in user | A page of rated contests. |
| `GET /api/contests/{id}/rating` | Any signed-in user | **The caller's own** outcome for that contest. |
| `POST /api/admin/contests/{id}/finalize` | ADMIN | Finalise now. Idempotent. |

### Competition ranking

`RANK() OVER (ORDER BY rating DESC)`: ties **share** a rank and the next rank skips
(1, 2, 2, 4). Rows are additionally ordered by contests rated and then by identity so a page
boundary is stable between requests — but that ordering does not change anybody's rank. A
result and a row number are different things.

One user's own rank is `1 + COUNT(*) WHERE rating > theirs`, which is the definition of
competition ranking and needs no window function.

### The four contest states

`GET /api/contests/{id}/rating` distinguishes four outcomes, and the distinction is the whole
point of the endpoint:

| Status | Meaning |
| --- | --- |
| `UNRATED` | This contest does not move ratings, and never will. |
| `CANCELLED` | It was called off. There will never be a rating. |
| `PENDING` | Rated, ended, not yet computed. A rating is coming. |
| `FINALIZED` | Here is the result — or nulls, if you did not compete. |

A pending contest **does not report a change of zero**. Zero is a real rating change; "not yet"
is not, and a page that showed the two the same way would be lying about one of them.

### What is never returned

No email, no role, no account state, no internal identifier, no submission source. The
leaderboard is the most public surface this system has and it reads a table that does not
contain anything private to begin with.

---

## 10. Rate limiting

The read endpoints use the existing `STANDINGS` policy (60 burst, 30/minute sustained, fails
**open**). Administrative finalisation uses a new `ADMIN_WRITE` policy (20 burst, 20/minute
sustained, fails **closed**).

`ADMIN_WRITE` is separate from `ADMIN_READ` because the two protect different things. A read
is a cost control, so it fails open — refusing an administrator a dashboard because Redis is
down helps nobody. A write is a blast-radius control: finalisation computes ratings across a
whole field and writes permanent, append-only history. If the limiter cannot answer, the
honest response is to refuse and let the administrator retry.

No new limiter was written. This is the Phase 9 infrastructure with one more policy in the
enum; the switch in `RateLimitProperties.bucketFor` is exhaustive, so a policy added without
configuration does not compile.

---

## 11. Metrics

| Metric | Type | Labels |
| --- | --- | --- |
| `codearena.rating.finalizations` | counter | `outcome` = rated / unrated / already-finalized / failed |
| `codearena.rating.participants` | counter | — |
| `codearena.rating.finalization.duration` | timer | — |
| `codearena.rating.ranking.query` | timer | — |
| `codearena.rating.last.field.size` | gauge | — |

**Every label is a closed set.** There is no contest id and no user id in any tag. A metric
tagged with a contest would create a time series per contest that ever ran, each frozen at its
final value forever, accumulating for as long as the deployment lived. Identifiers belong in
logs and in the audit log, both of which are searchable and neither of which multiplies. The
end-to-end suite asserts that no contest id, user id or username appears anywhere in
`/actuator/prometheus`.

`outcome="failed"` is the one to alert on. A failed finalisation is invisible from the
outside: the contest simply stays unrated, the sweeper tries again later, and nobody notices
until a competitor asks.

---

## 12. Performance

The ranking is the only query here whose cost grows with the whole user base. Everything else
is bounded — a profile is one row plus that user's history, a contest result is a single
lookup.

`RatingScaleIT` seeds 1,000 and 10,000 rated competitors and asserts that a page is served
quickly, that the **last** page costs about what the first page costs (the signature of real
database paging rather than an in-memory sort), and that one user's rank stays cheap even for
the lowest-rated competitor in the table. Thresholds are loose on purpose: a regression that
mattered would be seconds or an out-of-memory error, not a few milliseconds.

The calculator itself is O(n²). A 1,000-competitor field is a million expectations and
measures in tens of milliseconds; that is the documented limit of the approach, and a test
asserts it rather than assuming it.

---

## 13. What this system does not do

Stated plainly, because a feature list that quietly omits its gaps is worse than one that
names them.

- **No team ratings.** Ratings are per account.
- **No divisions.** One global scale.
- **No rating decay or inactivity adjustment.**
- **No manual rating adjustment.** There is no endpoint, no admin screen and no service
  method. This is a deliberate absence, not an oversight.
- **No recalculation of a finalised contest.** History is append-only. If a contest were rated
  on standings that later proved wrong, the correction would be a new operation with its own
  design, not an edit.
- **No cross-platform rating import.**
- **No predicted rating change during a live contest.**
- **The bands** (Novice, Apprentice, Specialist, Expert, Master, Grandmaster) are a
  presentation detail in the frontend. The server knows nothing about them, and moving a
  boundary changes a label and nothing else.

---

## 14. Verifying it yourself

```bash
# The arithmetic, with hand-calculated expected values.
./mvnw -pl common,backend -am -Dtest=RatingCalculatorTest test

# The whole rating system against real PostgreSQL, including the ten-way
# concurrency test and the sixteen security cases.
./mvnw -pl common,backend -am -Dtest=RatingApiIT test

# 1,000 and 10,000 competitors.
./mvnw -pl common,backend -am -Dtest=RatingScaleIT test

# End to end against a running stack: real contests, real judging, real ratings.
scripts/e2e/ratings.sh

# Everything, including the above as stage 10.
./scripts/verify-all.sh
```

To check your own rating change by hand: open your contest history, take the `rank` and
`participantCount` from the row, look up the standings for everybody's rating going in, and
work through §2. The `expected_score`, `actual_score` and `k_factor` are stored on every
history row precisely so the arithmetic can be reconstructed.
