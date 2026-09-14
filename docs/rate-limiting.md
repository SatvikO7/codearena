# Rate limiting, quotas and abuse controls

How CodeArena bounds what one caller can make the system do, what each limit is
protecting, and what this design does **not** protect against.

Implemented in Phase 9. The architectural decision is ADR-039.

---

## The shape of it

One component decides, and nothing above it knows how.

```
request
  → authentication            (401 if absent)
  → authorisation             (403 if not permitted)
  → RateLimitInterceptor      (429 if over the allowance)
  → validation
  → controller
```

`RateLimitService` answers one question — may this identity make one more request under
this policy — and returns a `RateLimitDecision`. Controllers carry a `@RateLimited`
annotation and nothing else; no controller contains a counter, a window or a Redis call.

The ordering is deliberate and is covered by tests. **Rate limiting is not authorisation
and never runs in its place**: an anonymous caller is answered 401 and a caller without
the role is answered 403, in both cases without ever reaching the limiter. Only a request
that is authenticated *and* permitted can be refused with 429.

The one exception is the per-account login throttle, which is keyed on the identifier
inside the request body and so is applied by `AuthController` directly. Its response is
identical to the interceptor's, down to the headers.

---

## Algorithm: a token bucket, evaluated inside Redis

A bucket holds `capacity` tokens and regains one every `refill-interval`. A request costs
one token. A bucket starts full, so an idle caller may spend the whole capacity at once —
that is the burst — and thereafter proceeds at the sustained rate.

The whole decision is one Lua script (`redis/token_bucket.lua`): refill, test, consume,
set expiry. That matters more than it might appear:

- **Atomic.** A read-then-write from the application — `GET` the count, compare, `INCR` —
  lets every concurrent caller observe the same remaining count and all be admitted. The
  bypass appears exactly under the load a limiter exists for. A test fires twenty
  simultaneous submissions at a capacity of three and asserts that three pass.
- **Bounded state.** Two fields per bucket, whatever the traffic.
- **Deterministic expiry.** The key's TTL is exactly the time the bucket needs to refill to
  full, at which point it carries no information. An absent bucket is a full bucket, so
  expiry loses nothing and memory tracks *recent* activity rather than every identity ever
  seen.
- **No database writes.** Nothing about rate limiting touches PostgreSQL.
- **Distributed.** State is in Redis, which every API instance shares, so the limit is the
  limit however many instances are running.
- **One clock.** The script reads Redis's own `TIME` rather than the caller's. Several
  instances share these buckets and machine clocks drift; taking the time from the process
  that owns the state makes the limit independent of what the callers believe the time is.

Why a token bucket rather than a fixed window: a fixed window admits twice its allowance
across a boundary, and a sliding-window log stores one entry per request, which is
unbounded state chosen by the attacker. A bucket also makes `Retry-After` arithmetic
rather than a guess, which is what allows the header to be honest.

**There is no in-memory fallback.** A local counter would make the enforced limit silently
proportional to the number of instances while this document continued to claim one number.
A control that is wrong in an unstated direction is worse than one that is absent, because
it is believed.

---

## What is limited

| Policy | Keyed on | Burst | Sustained | On Redis failure |
|---|---|---|---|---|
| `login-origin` | calling client | 30 | 30/min | closed |
| `login-account` | account attempted (failures only) | 10 | 2/min | closed |
| `registration` | calling client | 10 | 30/hour | closed |
| `submission` | user (practice **and** contest) | 10 | 6/min | closed |
| `standings` | user | 60 | 30/min | open |
| `problem-search` | user | 60 | 30/min | open |
| `admin-read` | administrator | 120 | 60/min | open |

Every number is configuration (`codearena.rate-limit.*`), not a constant in code.

### Authentication — two dimensions, and why

Password guessing needs a limit that holds *however the attempts are spread*, and flood
protection needs a limit per caller. Those are different keys, so there are two buckets.

**`login-origin`** counts every attempt from one client. It is generous on purpose: the
client identity available in this deployment can be shared by many honest users (see
below), and a tight limit on a shared identity is a denial of service against everyone who
shares it.

**`login-account`** counts *failed* attempts against one account, from anywhere. This is
the control that actually stops guessing, because credential stuffing is distributed by
nature and a per-caller limit does nothing about it. Ten wrong answers, then one every
thirty seconds — so an attacker gets roughly two guesses a minute against any given
account, no matter how many machines they have.

**A throttle, deliberately not a lockout.** "N failures and the account is locked" hands an
attacker a denial of service against any user whose name they can guess, which is worse
than the bug it fixes. A token bucket cannot lock anything: it refills continuously, and a
correct password empties it outright, so a legitimate user never accumulates against
themselves and is never more than one refill interval from an attempt.

> **The residual weakness, stated rather than papered over:** while a sustained attack is
> running against one account, its owner competes for each newly regenerated token and may
> need more than one attempt to get in. They are *delayed*, not locked out, the delay is
> bounded by the refill interval, and it ends when the attack does. A test asserts that the
> account itself is never touched — no flag, no disabled column, only a key with a TTL.

The check runs **before** authentication, which is the point: the expensive part of a login
is the BCrypt verification at cost 12, and a control applied afterwards would already have
paid for the attack it was meant to prevent.

**A throttled login is indistinguishable from a throttled registration.** Same status, same
error code, same sentence, same headers — and identical for a real account and an imaginary
one. Login is carefully built not to be an enumeration oracle and a limiter that answered
differently would quietly give that back.

### Registration

Rate limiting is an *additional* defence here. The unique username and email constraints,
the password policy and the validation are all unchanged and still decide first: a
duplicate username is a 409 while allowance remains, and a test asserts it.

Ten to begin with and one every two minutes is loose enough for a developer seeding
accounts or a class signing up together, and tight enough that scripted account creation is
not free.

### Submissions — one bucket, shared by practice and contests

The most important control in the phase. A submission is cheap to accept and expensive to
judge: it compiles and runs untrusted code in a container against every test case. One
scripted user can fill the queue far faster than the worker pool drains it, delaying
everybody else's verdicts — an availability failure that needs no exploit at all.

The limit is applied **before anything is queued or written**. A refused submission does
not become a row and does not reach Redis; tests assert both.

Practice and contest submissions **share one bucket**:

- The resource being protected is one shared judging queue. Two allowances would let a user
  apply twice the pressure to it.
- Separate buckets would be a bypass by design — refused on one endpoint, submit the same
  work through the other. Tests assert the limit holds in both directions.

### Contests

The allowance is sized for contest use rather than for idle browsing: ten queued at once,
then one every ten seconds. A competitor who has just found a bug submits again within a
minute, not within a second; ICPC-style scoring already penalises wrong submissions, so
rapid-fire resubmission is discouraged by the rules before it is discouraged by a limit.

No contest-specific policy exists, because none is justified: a per-contest cap would be a
second, differently-shaped limit on the same queue, and contest-wide concurrency is already
governed by worker concurrency and queue depth.

**Nothing here touches contest timing.** The window `[startAt, endAt)` is evaluated
server-side inside the service, exactly as before. A throttled submission is one that was
never accepted, never queued and never scored — it cannot turn a valid submission into an
invalid one, or the reverse.

### Public reads — only where the work is real

Most reads are not limited, and that is a decision rather than an omission. The problem
catalogue, contest listings and submission history are indexed, paginated queries whose
cost is what a database is for; limiting them buys a little protection against a nuisance
at the price of breaking somebody who opens six tabs.

Two reads are limited:

- **Standings** are recomputed from the submission table on every request and get more
  expensive as a contest fills up — which is when the most people are watching.
- **Catalogue search**, and only when a `search` term is present: a plain listing is an
  indexed read, while a term runs a trigram match across the catalogue.

The scoreboard refreshes every 15 seconds and the allowance is 30 a minute, so a browser
with several contests open is still nowhere near it.

### Administrators

Administrators are limited too. "Trusted users are exempt" is how a control ends up
protecting only the people who were never the threat, and an admin session is the one with
the most reach if it is stolen. The allowance — 120 burst, 60 a minute — is far above what
the admin pages consume: the system dashboard polls every 10 seconds and the audit page
pages on demand. A test asserts that an administrator really is refused past the limit.

---

## Identity: what is being limited

| Kind | Value | Cardinality bounded by |
|---|---|---|
| `USER` | the user's public UUID | the user table |
| `CLIENT` | SHA-256 of the network address, 16 hex chars | real network peers |
| `ACCOUNT` | SHA-256 of the normalised identifier, 16 hex chars | key TTL |

**Authenticated requests are keyed on the user**, taken from the server-side session —
the same source authorisation reads. There is no body field, header or query parameter
that names a user, so nobody can spend somebody else's allowance or escape their own. A
test tries.

**The hashing is not decoration.** Two of the three kinds derive from caller-supplied text,
and writing either verbatim into a Redis key would be two mistakes: the caller would choose
how much memory each attempt costs, and `SCAN` over the keyspace would enumerate every
username anyone had tried. The limiter needs to tell identities apart; it never needs to
read them back.

Account identifiers are normalised — trimmed and lower-cased — before hashing. Without
that, "a limit per account" would be "a limit per spelling", and an attacker would get a
fresh allowance for every variation of capitalisation.

Keys are `codearena:rl:{policy}:{kind}:{identity}`. They contain no password, no session
id, no CSRF token and no source code, and a test asserts it.

### Client identity, and its honest limits

`X-Forwarded-For` is **ignored by default**. Any client can send it; believing it without a
proxy that overwrites it means the attacker picks their own bucket — a fresh one per
request, which is a limiter that cannot be reached.

`codearena.rate-limit.trust-forwarded-headers` turns it on for a deployment that really
does terminate at such a proxy. When it is on, the **last** entry is used, not the first: a
proxy appends the peer it saw, so the final entry is the one the proxy wrote and every
earlier entry may have been supplied by the client. Reading the first entry is the usual
mistake and it is reading attacker input.

> **Known limitation.** The shipped compose deployment has no reverse proxy in front of the
> API — nginx serves the built frontend only, and the browser calls the API directly. So
> `getRemoteAddr()` is the peer socket, which behind Docker's published port is frequently
> the bridge gateway rather than the real client. **Anonymous callers can therefore collapse
> into a single shared identity.** This is why `login-origin` is a generous flood cap rather
> than the anti-guessing control, and why `login-account` — which does not collapse — carries
> the weight. It is also why the default is not to trust a header that would appear to fix
> this while actually making it worse.

A value that is not a plausible address is not used as an identity. The fallback is the
socket peer, so sending rubbish leaves a caller exactly where they started; only when
neither is usable do such callers share one bucket, which is the safe direction to fail in.

---

## Response semantics

A refused request is `429 Too Many Requests` with the project's standard error envelope:

```json
{
  "timestamp": "2026-09-14T09:31:22.104Z",
  "status": 429,
  "error": "RATE_LIMITED",
  "message": "Too many requests. Please wait a moment and try again.",
  "path": "/api/problems/…/submissions"
}
```

| Header | Meaning |
|---|---|
| `RateLimit-Limit` | the bucket's capacity |
| `RateLimit-Remaining` | whole requests still available, floored |
| `RateLimit-Reset` | seconds until **at least one** request is available |
| `Retry-After` | seconds until *this* request would be admitted — **429 only** |

Only what the algorithm can guarantee is published. `RateLimit-Reset` is the wait for the
next token, not the wait for a full bucket, which is a different and much longer number —
a value whose meaning was fuzzy would be worse than none, because clients would schedule
against it and be refused anyway. `Remaining` is floored because a fractional token cannot
serve a request.

Advisory headers are set on successful responses too, so a well-behaved client can slow
down before it is refused rather than after. `Retry-After` appears only on a 429, where it
means something.

**The message is the same sentence for every policy**, and says nothing about Redis, the
bucket, the key, the identity or which control was tripped.

---

## When Redis is unavailable

Decided per policy and declared on the policy itself. ADR-039 has the reasoning; the
summary:

| Endpoint | Behaviour | Why |
|---|---|---|
| Login | **closed** — 429 | Login cannot create a session without Redis anyway, so closing costs nothing that was still working |
| Registration | **closed** — 429 | Every registration writes a permanent row and consumes a username; unlimited account creation during an outage is the one outcome worth avoiding |
| Submission | **closed** — 429 | The queue it protects is in Redis; without it there is nothing to submit into |
| Public/authenticated GETs | **open** — served | The limiter is a comfort here, and an unavailable comfort must not become a second outage |

> **The observation that makes this cheap:** Redis already holds every session. For
> authenticated traffic, "Redis is down" and "the API is down" are close to the same
> sentence — so failing the security-critical policies closed costs very little that was
> still functioning, and the practical effect of failing the others open is correspondingly
> limited. Registration is the one genuine availability cost, and it is taken deliberately.

A closed policy still returns a usable `Retry-After` rather than an empty refusal, and the
response says nothing about the infrastructure. Tests break the connection for real — a
proxy in front of a private Redis drops every socket — and assert each of these.

**Recovery is automatic and needs no code.** An absent bucket is a full one, which is the
state it would have reached by waiting, so losing every bucket to a restart is not a
failure mode that needs handling. There is a short window after the server returns during
which the client is still reconnecting and closed policies still refuse; it is bounded, and
a test asserts that enforcement resumes within it.

---

## Audit and observability

**Rejections are not audited one for one.** The audit table is append-only and has no
retention tooling — nothing can delete from it, by design — so a row per rejection would
hand every rate-limited caller a way to grow a table nobody can prune, using the very
requests the limiter just refused. The control against flooding would have become the
flood.

Instead the first rejection in a window writes one `RATE_LIMIT_EXCEEDED` event and claims a
marker in Redis (`SET NX EX`, atomic and self-expiring); every rejection until it expires
writes nothing. At the default ten-minute cooldown, one identity produces at most six rows
an hour under one policy, whatever they do.

**Only policies with a bounded identity may produce events at all.** `login-account` is
excluded, because its identity derives from a username typed by the caller — auditing it
would let anybody mint rows by inventing account names. The event records the policy and
the *category* of identity, never an address or an attempted username; when the caller is
authenticated the actor fields already name them.

The same marker bounds logging: one `RATE_LIMIT_REJECTED` line per identity per cooldown,
carrying the policy, the identity kind, the method and the path. Never the identity itself
— an attacker chooses how many of those lines they cause.

Metrics are a single counter, `codearena.ratelimit.decisions`, tagged with policy, outcome
(`allowed`, `rejected`, `failed-open`, `failed-closed`) and identity kind. All three are
closed sets. **No username, user id or address appears in a metric label**: one time series
per caller turns an attack into a monitoring outage.

---

## Frontend behaviour

`describeApiError` tells a 429 apart from every other failure and reports the server's own
wait: *"Too many requests. Please try again in 30 seconds."* The wait is read from
`Retry-After`, never invented — a locally guessed delay either retries too early and is
refused again, or makes somebody wait longer than they had to.

The submit buttons on both solve pages are held closed for the duration, showing a
countdown. A button that has just failed invites a second press, every press is refused,
and each refusal extends the wait; the user makes their own situation worse and the server
absorbs a burst from a client it has already told to stop.

**Nothing retries automatically.** When the countdown reaches zero the button opens and the
next attempt is the user's to make. An automatic retry is how a rate limit becomes a retry
storm: every throttled client waking at once and resending, which is the shape of the load
the limit existed to shed.

The standings poller backs off on a 429 — honouring `Retry-After`, or sixty seconds if the
server gave none — rather than keeping its fifteen-second rhythm through a refusal.

None of this is a security control and nothing depends on it. A client that ignores all of
it is refused by the server exactly as before.

---

## Configuration

All under `codearena.rate-limit`, every value with an environment variable and a default.

| Setting | Default | Notes |
|---|---|---|
| `enabled` | `true` | Off for local experiments only. On by default, so forgetting it is not a way to ship unprotected |
| `trust-forwarded-headers` | `false` | On only where a proxy overwrites `X-Forwarded-For` |
| `violation-audit-cooldown` | `PT10M` | Collapses one identity's rejections into one event and one log line |
| `<policy>.capacity` | see table | The burst |
| `<policy>.refill-interval` | see table | One token per interval — the sustained rate |

Validated at startup: a capacity below one or an interval that rounds to zero milliseconds
refuses to start, rather than surfacing as an unexplained outage or a division by zero
inside the script during an attack.

**Development.** Defaults are deliberately usable as-is; there is no separate development
profile, because a limit that only exists in production is a limit nobody has tested.

**Test.** Rate limiting stays *on* throughout the test suite, so the interceptor, the script
and the 429 path are exercised by every suite rather than only by the ones written for
them. What is removed is the interference: `AbstractIntegrationTest` clears the limiter's
own keys before each test — not `FLUSHALL`, which would take sessions and the queue with
it. The rate-limit suites set tiny limits of their own so that reaching one takes a moment
rather than a minute.

**Production.** Set `trust-forwarded-headers` only alongside a proxy that overwrites the
header. Tune capacities to the judging capacity actually deployed: the submission limit
should be set against what the worker pool can drain, not against what feels generous.

---

## Known limitations

- **No trustworthy client IP in the shipped deployment.** Covered above. The consequence is
  that anonymous per-caller limits are weaker than they look, and the per-account throttle
  is what carries the protection.
- **A sustained distributed attack on one account delays that account's owner.** Bounded by
  the refill interval, never a lockout, ends when the attack does.
- **No quota on totals.** Limits are on rate, not on lifetime volume: there is no "50
  submissions per day" or per-user storage cap. Nothing in the system currently needs one,
  and a daily quota is a different control with different failure modes.
- **No adaptive or reputational limiting.** Every caller in a category gets the same
  allowance. No behavioural scoring, no CAPTCHA, no anti-cheat, no plagiarism detection —
  none of these exist and none are claimed.
- **Rejections are visible but not alerted on.** The metric and the audit events are there
  for somebody who looks; nothing raises them. Alerting is Phase 10.
- **Key cardinality is bounded by TTL, not by a cap.** A flood of invented usernames creates
  buckets that expire on their own, so steady-state memory is proportional to the attack
  rate over one TTL rather than to the number of distinct names ever tried. The origin
  bucket caps that rate in the first place. There is no hard ceiling on key count.
