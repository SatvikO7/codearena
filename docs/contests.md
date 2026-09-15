# Contests

How a contest works, precisely enough to settle an argument about a result.

---

## Lifecycle

A contest has a **lifecycle** an administrator controls and a **status** users see. They are
not the same thing, and only the first is stored.

```
  lifecycle (stored)          status (computed, never stored)
  ─────────────────           ──────────────────────────────
  DRAFT              ───────► DRAFT
  CANCELLED          ───────► CANCELLED
  PUBLISHED          ───────► UPCOMING   while  now <  startAt
                     ───────► LIVE       while  startAt <= now < endAt
                     ───────► ENDED      while  endAt <= now
```

Permitted lifecycle moves:

| From | To | Notes |
|---|---|---|
| DRAFT | PUBLISHED | Requires at least one problem, and an end time still in the future |
| DRAFT | CANCELLED | Abandoning something never released |
| PUBLISHED | CANCELLED | Allowed **including while LIVE**; see below |
| PUBLISHED | DRAFT | **Refused.** Un-publishing a contest people can see, and may have registered for, is a cancellation in disguise |
| CANCELLED | anything | **Refused.** Terminal, so nobody is told a contest is off and then on again |

### Why the status is derived rather than stored

A stored status has to be advanced by something. If that something is down at `endAt`, or is
late, or the clock skews, the row says LIVE after the contest is over — and a late submission
is accepted because a background job had not got round to it yet.

Deriving it means a contest ends on time whether or not anything is running to notice, and a
restart cannot resurrect a finished one. There is no scheduled job in this design, and
nothing to fall behind. ADR-032.

### Cancelling a live contest

Allowed, deliberately. A broken problem or a leaked test set is a real reason to stop a
contest, and refusing would leave an administrator no honest option but to let a spoiled one
finish.

The semantics are blunt:

- **Submissions stop immediately** — CANCELLED does not accept them.
- **Submissions already made are kept.** They are a record of what happened.
- **The standings stay readable**, marked cancelled. People competed; hiding the result would
  be a second penalty for somebody else's decision.
- **Nothing is resurrected.** A cancelled contest cannot become live again.

An **ended** contest cannot be cancelled. Its result is already history.

---

## Time

Every timestamp is stored as `TIMESTAMPTZ` in **UTC**, and the API emits ISO-8601 with an
offset. `2026-03-01T09:00:00Z` and `2026-03-01T14:30:00+05:30` are the same instant and are
treated as the same instant. No contest rule ever consults a server's local timezone; the
browser renders the instant in the reader's zone and names it.

### The window is half-open: `[startAt, endAt)`

- At **exactly `startAt`** the contest is LIVE.
- At **exactly `endAt`** it is ENDED, and submissions are refused.

Every instant belongs to exactly one state. An inclusive end would leave a single ambiguous
millisecond in which a contest is both running and finished — the kind of thing only ever
discovered by the person whose submission lands on it.

### The countdown is not the deadline

The contest page draws a countdown, and corrects it: the API sends `serverTime` alongside the
schedule, so the browser measures the offset between the two clocks once and counts down
against a corrected one. On a laptop resumed from sleep an uncorrected timer is routinely
minutes wrong.

It remains **informational**. Every submission is checked against the server's clock, on the
request itself. A browser still showing a running countdown is refused all the same, and a
browser whose countdown has expired early is not the thing that closed the contest.

---

## Registration

`POST /api/contests/{id}/register`. No request body: the participant is whoever is
authenticated, and there is no field in which to name somebody else.

**Registration closes when the contest starts.** A contestant joining a contest already in
progress would compete over a shorter window while the penalty clock still ran from the
contest's start, so their standing would not be comparable with anyone else's. Supporting
late entry properly needs a per-participant start time and a different penalty basis — a
different product, not a looser check. ADR-033.

**Registering twice is a success, not an error.** The second call returns the original
registration with `alreadyRegistered: true`. A double-click must not produce an error a user
cannot act on.

What actually prevents a duplicate is the unique constraint on `(contest_id, user_id)`, not
the check in the service: two concurrent requests can both pass a check, and only one can win
an index.

---

## Submissions

`POST /api/contests/{contestId}/problems/{problemId}/submissions`, carrying a language and a
source string. There is no field for a user, a verdict, a score, a status, a runtime or a
timestamp.

Five things are verified server-side, none taken from the request:

1. The contest exists and is visible — a draft answers 404.
2. **The problem belongs to this contest**, read from the database. This is what stops a
   contestant submitting to any problem whose id they know by pairing it with a contest id.
3. The caller is registered. Authentication is not enough.
4. The contest is LIVE **now**, by the server's clock.
5. The problem is still published.

### Contest and practice submissions

The same table, the same queue, the same worker, the same sandbox. A submission carries a
nullable `contest_id`; **null means practice**. There is no second execution engine, because
a second one would be a second place for a judging bug to live, and the one that ran less
often would be the one nobody noticed was broken.

Every submission made before contests existed is a practice submission, which is exactly what
a null column gives it — no backfill, no ambiguity.

### The authoritative timestamp

Scoring uses `submissions.created_at`, written by the database's auditing on insert. No
client timestamp is read anywhere in the submission path, and none could be: the request type
has no field for one.

---

## Scoring

ICPC-style.

**A problem is solved** by a contestant's *first* ACCEPTED submission to it within the
contest. Later submissions to a solved problem change nothing — not the score, not the
penalty, not the solve time. Resubmitting cannot help and cannot hurt.

**Score** is the sum of the points of solved problems. No partial credit.

**Penalty** accumulates only over *solved* problems:

```
penalty(problem) = minutes from contest start to the accepted submission
                 + 20 × (counted rejections before that submission)
```

Two consequences, both deliberate:

- **Rejections on a problem you never solve are free.** The standard rule, and the right one:
  penalising them would rank a contestant who attempted a hard problem and failed below an
  identical contestant who never tried, punishing effort rather than error.
- **Rejections after solving are free.** They cannot have helped.

Minutes are truncated rather than rounded, so a charge never exceeds the time elapsed.

### Which verdicts count as a rejection

| Verdict | Counts? |
|---|---|
| WRONG_ANSWER | Yes |
| RUNTIME_ERROR | Yes |
| TIME_LIMIT_EXCEEDED | Yes |
| MEMORY_LIMIT_EXCEEDED | Yes |
| COMPILATION_ERROR | Yes |
| **SYSTEM_ERROR** | **No** |
| QUEUED, RUNNING | No |
| ACCEPTED | No |

**SYSTEM_ERROR is never counted.** It means the judge failed — a sandbox that would not
start, a daemon that went away — and the submitted code was never shown to be wrong. Charging
twenty penalty minutes for our own outage would be the platform taking its failures out on
the people using it.

QUEUED and RUNNING do not count because nothing has been judged yet.

---

## Standings

`GET /api/contests/{id}/standings`.

### Ordering

1. Higher **score** first.
2. Then lower **penalty**.
3. Then the **earlier last solve** — of two contestants level on both, the one who finished
   sooner ranks higher.
4. Then **user id**, purely so the order is stable. Two genuinely tied contestants get
   adjacent rows in an arbitrary but *repeatable* order rather than swapping places between
   two reads of the same data.

Ranks are **competition ranks**: genuinely tied contestants share a rank and the next rank
skips (1, 2, 2, 4). The id tie-break orders them but does not separate them — being earlier
in the database is not an achievement.

### Visibility

Readable by any authenticated user for a contest that has started, **including while it is
running**. There is no scoreboard freeze; adding one is a feature with its own rules, not a
default.

### What is on it

Username, rank, score, penalty, solved count, and a per-problem grid. **No email, no internal
identifier, no profile data, no source code.** A leaderboard is the most widely read page a
contest has, and anything on it is effectively published.

### How it is computed

From persisted submission results, on every request. Three bounded queries — the contestants,
the solved cells, the attempt counts — and then a pure function.

No score is stored anywhere, so there is no cached total to fall out of step with the
submissions it came from, and no incremental update to get wrong when a result is recorded
twice. The aggregation happens in PostgreSQL; what crosses the wire is bounded by
contestants × problems, not by how many times people submitted. ADR-034.

**Live updates** are a 15-second poll of that endpoint while a contest is running, stopping
when it ends. Not SSE: the existing stream infrastructure is keyed by submission id and fans
out to the one person watching that submission, where standings are per contest and would
need a different fan-out, new authorisation and a new connection cap.

---

## Immutability

A contest is freely editable while DRAFT or UPCOMING, and **frozen the moment it goes LIVE** —
schedule, problem set, ordering and points alike.

There is no emergency override. Changing what a problem is worth mid-contest silently
rewrites the standings of everyone who already solved it; moving `endAt` invalidates every
penalty already computed. An administrator who genuinely must stop a contest cancels it,
which is visible to every contestant, rather than editing it, which is not.

The rule is enforced on the `Contest` aggregate rather than in a service, so an endpoint added
later cannot forget it.

---

## Deletion

Permitted **only for an untouched draft**: never published, nobody registered, nothing
submitted. Anything else is a record of what people did.

The database enforces it independently — `fk_submissions_contest` is `ON DELETE RESTRICT` —
so a mistake in the service still cannot destroy submission history. Cancelling is the
lifecycle change; deletion is not a substitute for it. ADR-035.

---

## Known limitations

- **No late registration.** Registration closes at `startAt`; see above for why.
- **No scoreboard freeze.** Standings are live throughout. Contests that need a frozen final
  hour do not have it.
- **No per-user opt-out of standings.** Everyone registered appears. This is not implemented,
  and is recorded here as absent rather than described as if it existed.
- **No plagiarism detection and no anti-cheat of any kind.** Nothing in CodeArena compares
  submissions between contestants, detects shared solutions, or watches for suspicious
  behaviour. A contest run on this platform is not protected against collusion.
- **No team contests and no divisions.** One contestant, one score.
- **Ratings are a separate concern, added in Phase 11.** A contest is rated or unrated,
  and that decides whether its final standings move anybody’s rating — but the scoring
  itself is unchanged by it, and a rated contest is scored exactly like an unrated one.
  The flag is fixed once the contest starts, and a cancelled contest is never rated.
  See [ratings.md](ratings.md).
- **No per-problem scoring variants** — no partial credit, no subtasks, no decay over time.
- **Standings are recomputed per request.** At the scale this is built for that is the right
  trade; a contest with tens of thousands of participants would want a materialised
  scoreboard, and the point at which that becomes true is measurable rather than guessed.

---

## Related

- [decisions.md](decisions.md) — ADR-032 (derived status), ADR-033 (registration window),
  ADR-034 (standings computation), ADR-035 (contest data durability)
- [architecture.md](architecture.md) — where contests sit in the system
- [submission-lifecycle.md](submission-lifecycle.md) — how a submission is judged
