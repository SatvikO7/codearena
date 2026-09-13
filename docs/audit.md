# Audit logging and administrative governance

What is recorded, what is deliberately not, and what the guarantees actually are.

---

## What this is, and is not

An **append-only record of security-sensitive and administrative events**: who did what, to
what, when, and whether it worked.

It is **not an event store**. CodeArena's authoritative state stays in the domain tables —
`users`, `problems`, `contests`, `submissions` — and nothing is ever reconstructed from audit
events. Deleting every row in `audit_events` would lose the history of who did what and
change no password, no problem's status, no contest's standings and no verdict. That
separation is deliberate; see ADR-036.

---

## The model

| Column | Meaning |
|---|---|
| `occurred_at` | **Written by PostgreSQL** (`DEFAULT now()`), never by the application or a client |
| `actor_user_id` | The acting user's **public** id, or null for an anonymous or system actor |
| `actor_username` | Copied in at write time, so the record survives a rename or a deletion |
| `actor_type` | USER, ADMIN, SYSTEM or ANONYMOUS |
| `action` | An `AuditAction` constant |
| `outcome` | SUCCESS, FAILURE or DENIED |
| `entity_type` / `entity_id` | What was acted on. Text, not a foreign key |
| `request_id` | Correlates the event with the server log lines from the same request |
| `metadata` | Curated, bounded JSON |

**Every column is written by the server.** No request body anywhere in the API contributes an
actor, an action, an outcome, a timestamp or a metadata value, so none can be forged.

### Why there are no foreign keys

Neither `actor_user_id` nor `entity_id` is a foreign key, for the same reason: **the record
must outlive the thing it describes.** A foreign key has to do something when the referenced
row is deleted, and both options are wrong — `CASCADE` erases the history of what somebody
did, and `SET NULL` is an UPDATE, which the append-only trigger refuses outright. An audit
row is a statement about the past, and deleting an account does not change what that account
did.

---

## Immutability

Enforced in three independent layers, because the row most worth tampering with is the one
recording the tamperer:

1. **The entity is `@Immutable`** and has no setters, so Hibernate will not issue an UPDATE.
2. **No repository method and no endpoint modifies or removes an event.** The audit API is a
   single GET.
3. **A database trigger raises on UPDATE and DELETE.** "The code does not do that" is a
   weaker guarantee than "the database refuses", and this matters exactly when somebody with
   database access is the problem.

`AuditApiIT` asserts all three, including that a blanket `DELETE FROM audit_events` fails.

---

## Transaction semantics

Two modes, and the difference is the point.

### Administrative mutations — same transaction

`AuditService.record(...)` uses `Propagation.MANDATORY` and joins the caller's transaction.
The event and the change commit together or not at all. Two consequences, both intended:

- **A rolled-back mutation leaves no record claiming it happened.** Otherwise the log would
  accumulate confident accounts of changes that never occurred — worse than no log, because
  it would be believed.
- **A mutation cannot commit without its audit row.** If the audit write fails, the
  transaction fails. For a security-critical change, "it succeeded but we cannot say who did
  it" is not an acceptable outcome, so the audit write is allowed to veto the mutation.

`MANDATORY` rather than `REQUIRED` so that calling it outside a transaction is an immediate,
loud failure rather than a row that quietly commits alone.

### Failures and denials — independent transaction

`AuditService.recordIndependently(...)` uses `REQUIRES_NEW`. Used for events that describe
something already outside any transaction of ours, or that must survive a request which is
failing:

- **A failed login.** Nothing was changed, and the request ends in a 401. Joining a
  transaction that is rolling back would discard precisely the record worth keeping.
- **A denied request.** Refused by a servlet filter, before any transaction exists.

Here a database failure is **logged at ERROR and swallowed**. The alternative would turn a
failed login into a 500 and hand an attacker a way to tell real accounts from imaginary ones
by the error they produce.

> **The trade, stated plainly:** a lost *failure*-audit row is possible. A lost
> *success*-audit row is not.

---

## What is audited

### Authentication
`AUTH_REGISTER`, `AUTH_LOGIN`, `AUTH_LOGIN_FAILURE`, `AUTH_LOGOUT`

### Problems
`PROBLEM_CREATE`, `PROBLEM_UPDATE`, `PROBLEM_PUBLISH`, `PROBLEM_UNPUBLISH`,
`PROBLEM_ARCHIVE`, `PROBLEM_RESTORE`

### Contests
`CONTEST_CREATE`, `CONTEST_UPDATE`, `CONTEST_PUBLISH`, `CONTEST_CANCEL`, `CONTEST_DELETE`,
`CONTEST_PROBLEM_ADD`, `CONTEST_PROBLEM_UPDATE`, `CONTEST_PROBLEM_REMOVE`,
`CONTEST_REGISTER`

### Submissions
`SUBMISSION_CREATE` — that somebody submitted, never *what* they submitted.

### Access control
`ADMIN_ACCESS_DENIED` — an authenticated caller refused an administrative endpoint.

The list is served at `GET /api/admin/audit-events/actions` so an administrative UI need not
hard-code one that goes stale.

---

## What is deliberately **not** audited

| Not recorded | Why |
|---|---|
| **Reads** — browsing, listing, fetching | They are the bulk of traffic and change nothing. `GET /api/contests` produces no event, and a test asserts it |
| **Ordinary validation failures** | A malformed problem body is an API error and a log line. Auditing them would bury the events that matter |
| **Non-admin authorisation denials** | Ordinary authorisation working as designed on the normal API. Only denials under `/api/admin/` are recorded |
| **Judging outcomes** | A verdict is domain state, already durable in `submissions`. Copying it here would duplicate the truth |
| **Session expiry** | Nobody did it |

The distinction that governs all of it:

- **business validation failure** → API error + application log, no audit event
- **authentication failure** → `AUTH_LOGIN_FAILURE`, outcome FAILURE
- **authorisation denial (admin surface)** → `ADMIN_ACCESS_DENIED`, outcome DENIED
- **successful mutation** → the action's constant, outcome SUCCESS, in the same transaction

---

## Metadata rules

**Curated, never captured.** Metadata is built field by field by the code recording the
event. Nothing serialises a request body, a DTO or an entity. A problem publication records
`{problemId, slug, previousStatus, newStatus}` — not the problem, which would carry its
statement and its test cases.

That is the primary defence and it is structural: a map somebody populates by hand cannot
accidentally acquire a password. `AuditMetadata` adds a second line:

- **Keys that look like secrets are redacted** — `password`, `secret`, `token`, `session`,
  `cookie`, `csrf`, `authorization`, `credential`, `sourceCode`, `expectedOutput` — matched
  case-insensitively, ignoring separators, as substrings. Deliberately broad: a false
  positive costs one redacted field, a false negative writes a credential into a table that
  is never deleted.
- **Values are truncated** at 500 characters and marked.
- **At most 20 entries**, and a database constraint caps the serialised map at 4 kB.

### Never stored, under any circumstances

Passwords and hashes, session identifiers, CSRF tokens, the executor token, submitted source
code, hidden test inputs and expected outputs.

A failed login records the **attempted identifier** and a coarse reason — never the password,
and never anything that would reveal whether the account exists. Login itself answers
identically for "no such user" and "wrong password" precisely so it cannot be used to
enumerate accounts; an audit record that distinguished them would hand the same oracle back
to anyone who could read the log.

The attempted identifier is recorded as *what was tried*, never as the actor. The actor is
whoever made the request: ANONYMOUS in the ordinary case of a signed-out caller, but the
signed-in user when the attempt came from a live session. That is not flattened to ANONYMOUS
— somebody signed in as one user guessing at another account is precisely the pattern worth
surfacing, and it would be lost.

---

## Request correlation

Every request gets an id, from `RequestIdFilter`:

```
request  →  application log  →  audit event
   └────────── one id ──────────────┘
```

A caller-supplied `X-Request-Id` is accepted, **sanitised**, and echoed back. Sanitising
matters: the value is attacker-controlled text that ends up in log files and in a table that
is never deleted, so only letters, digits, hyphens and underscores are allowed, bounded at 64
characters. Anything else is replaced with a generated id rather than rejected — failing a
request over a cosmetic header would be a poor trade. A newline would otherwise let a caller
forge log lines.

The id is **not a security control** and authorises nothing.

---

## Client IP

**Not recorded.** A deliberate omission rather than an oversight.

The deployment puts the API behind nginx, so `request.getRemoteAddr()` yields the proxy's
address, not the client's — a value that looks like evidence and is not. The real address
would have to come from `X-Forwarded-For`, which is a client-settable header: trusting it
blindly means an attacker chooses what the audit log says about them, which is worse than
recording nothing.

Doing it properly needs a configured chain of trusted proxies and a documented deployment
topology. Until that exists, an absent field is more honest than a forgeable one. Personal
data that is not collected also cannot be mishandled.

---

## The API

`GET /api/admin/audit-events` — **ADMIN only**. Anonymous callers get 401, authenticated
non-administrators get 403, and that 403 is itself audited.

Filters: `actorUserId`, `action`, `outcome`, `actorType`, `entityType`, `entityId`, `from`,
`to`. All optional, combined with AND.

**Time range is half-open**: `from` inclusive, `to` exclusive, so consecutive ranges neither
overlap nor leave a gap. An inverted range is a 400 rather than an empty page — an empty page
reads as "nothing happened", which is a wrong answer to a mistyped question.

**Sorting** accepts only `occurredAt`, `action` or `outcome`; anything else is a 400. An open
sort parameter would let a caller order by fields the API never exposes and make the database
sort an unindexed column. When sorting by action or outcome, `occurredAt DESC` is applied as
a secondary key so pages do not shuffle between requests.

**Page size** is capped at 200.

Filtering is built as a Criteria specification rather than one query with optional
parameters: PostgreSQL cannot infer a type for a null bound parameter in
`(:x IS NULL OR col = :x)` and the query fails outright — the same trap ADR-017 records for
problem search. Only the filters actually sent become predicates, which also lets the planner
use the indexes.

### Response

Event id, timestamp, actor public id and username, actor type, action, outcome, entity type
and id, request id, and metadata. **No internal identifiers, no email address, no profile
data, no source code.**

---

## System status

`GET /api/admin/system/status` — **ADMIN only**.

A **curated** operational view: whether PostgreSQL and Redis answer and how quickly, the
judging queue depths, the build version and the audit event count.

Deliberately not an Actuator dump. Actuator's `/env` and `/configprops` list every property
including database and Redis credentials, and `/heapdump` hands over process memory; none of
them are exposed. Adding a field to this endpoint is a deliberate act, which is the property
worth having.

### The three kinds of health, kept distinct

| | Endpoint | Audience |
|---|---|---|
| **Liveness** | `/actuator/health/liveness` | An orchestrator deciding whether to restart |
| **Readiness** | `/actuator/health/readiness` | Container health checks; gates startup ordering |
| **Operational status** | `/api/admin/system/status` | A human administrator |

The first two stay unauthenticated by necessity — a health check has no credentials — and
return a bare status. Actuator health **details** were previously visible to any caller,
disclosing the Redis version, the database engine, the container's filesystem path and the
host's free disk space; `show-details` is now `when-authorized` with `roles: ADMIN`, so the
breakdown needs an administrator session while the probes keep working.

The operational endpoint is deliberately **not** wired into any health check: a view an
orchestrator depends on stops being free to change.

---

## Retention

**Audit records are append-only and retention is currently indefinite.** Nothing prunes them,
and no automatic cleanup exists.

This is a deliberate absence rather than an unfinished feature. A retention policy would need
a decision about what may be discarded and when, and the mechanism would be a separately
governed operation — drop the trigger, prune under supervision, restore it — not an ordinary
DELETE that happens to be permitted.

> **No compliance claim is made.** CodeArena does not implement GDPR erasure, data-subject
> export, legal-hold, or tamper-evident signing. The log is append-only and access-controlled;
> it is not a certified audit trail, and nothing here should be read as claiming otherwise.

Practical note: the table grows monotonically. At the volume this project is built for that
is unremarkable, and the event count on the status page is the number to watch.

---

## Known limitations

- **No client IP.** See above — deliberate, and documented rather than faked.
- **No worker heartbeat.** The API server has no network route to the worker; the queue
  depths are the honest proxy. A real heartbeat means workers writing to a shared store,
  which belongs with queue observability.
- **No user administration.** There is no endpoint to change a role, disable an account or
  delete a user, so there is nothing of that kind to audit. Adding role management would need
  privilege-escalation guards and a safeguard against removing the last administrator; it is
  **deferred**, and `ADMIN_ACCESS_DENIED` plus the role recorded on `AUTH_REGISTER` are what
  exists today.
- **No retention tooling**, no archival, no export.
- **No tamper-evidence beyond access control** — no hash chaining, no signing. A database
  superuser could disable the trigger. The trigger raises the bar; it does not make the log
  cryptographically verifiable.
- **No alerting.** A burst of `AUTH_LOGIN_FAILURE` is visible to somebody who looks; nothing
  raises it. Rate limiting and abuse controls are Phase 9.

---

## Related

- [decisions.md](decisions.md) — ADR-036 (architecture), ADR-037 (consistency and
  immutability), ADR-038 (operational status)
- [architecture.md](architecture.md) — where auditing sits
- [security.md](security.md) — the wider security posture
