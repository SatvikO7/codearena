# Engineering decisions

A running log of choices that were not forced by the specification. Each entry states
the problem, the options considered, what was chosen and what it costs.

---

## ADR-001 — One Maven reactor with `backend` and `worker` modules

**Problem.** The API server and the judge worker are separate processes, but they will
share domain types (entities, enums, queue payloads) from Phase 4 onwards.

**Options.**
1. Two entirely independent Maven projects — duplicate the shared types in each.
2. One multi-module reactor.
3. One process doing both jobs, with judging on a background thread pool.

**Chosen.** Option 2: a parent aggregator POM with `backend` and `worker` modules.

**Why.** Option 3 is disqualified by the whole point of the project — the API server
must never execute user code, and judging must not compete with request handling for
CPU. Option 1 guarantees the two copies of `SubmissionStatus` drift apart. A reactor
gives one `mvn verify` across both artefacts while keeping them separately deployable,
and each still builds into its own container image.

**Trade-off.** The modules are version-locked and released together, which is fine for
a single-repository system but would need splitting if the worker were ever owned by a
different team. A third `common` module will be extracted in Phase 2, when the first
genuinely shared type (the `User` entity) appears — not before, to avoid an empty
module that exists only in anticipation.

---

## ADR-002 — Flyway migrations run in the backend only

**Problem.** Both processes talk to the same database. If both ran migrations, two
replicas starting simultaneously could race.

**Options.**
1. Both run Flyway and rely on Flyway's advisory lock.
2. Only the backend runs Flyway.
3. A dedicated one-shot migration container.

**Chosen.** Option 2. Flyway is not even on the worker's classpath, and compose gates
worker startup on the backend's health check.

**Why.** Flyway's lock would in fact make option 1 safe, but "safe because of a lock we
must remember exists" is weaker than "impossible because the code isn't there". Option 3
is the right answer at production scale and is noted for the deployment phase; it adds
orchestration complexity that a compose-based dev stack does not need.

**Trade-off.** The worker cannot start before the backend, which couples their
deployment order. Acceptable: the worker has nothing to do without a schema anyway.

---

## ADR-003 — Integration tests are separated from unit tests by Failsafe

**Problem.** Tests that use Testcontainers need a Docker daemon. If they ran under
`mvn test`, the build would be unrunnable on any machine without Docker, including some
CI runners.

**Chosen.** Surefire runs `*Test` (no external dependencies, fast). Failsafe runs `*IT`
during `mvn verify` (Testcontainers, real PostgreSQL and Redis).

**Why.** It keeps a fast inner loop honest — `mvn test` genuinely passes without
Docker — while the integration suite tests against real infrastructure rather than
in-memory substitutes. H2 was rejected outright: the project depends on PostgreSQL
behaviour (`citext`, upserts, advisory locks later), and an in-memory database that
disagrees with production is worse than no test.

**Trade-off.** `mvn test` alone does not prove the application boots. `mvn verify` is
the real gate, and CI must run it.

---

## ADR-004 — The worker fails fast when PostgreSQL or Redis is unreachable at startup

**Problem.** A worker that cannot reach the queue is invisible: it stays up, consumes
nothing, and nothing obviously alerts.

**Chosen.** `StartupConnectivityVerifier` probes both dependencies during startup and
throws if either fails, so the process exits and the orchestrator restarts it.

**Why.** A crash loop is loud and immediately diagnosable; a silently idle worker is
not. Because the orchestrator restarts the container, this doubles as a retry loop
while dependencies come up.

**Trade-off.** This applies to startup only. Failures *during* operation must not kill
the process — they are handled by the job pipeline's retry and dead-letter logic in
Phase 5, so that an in-flight submission is never lost to a transient Redis blip.

---

## ADR-005 — `citext` for case-insensitive identity

**Problem.** `alice@example.com` and `Alice@Example.com` must not be able to register as
two accounts.

**Options.** Application-level lower-casing before insert; a functional unique index on
`lower(email)`; the `citext` extension.

**Chosen.** `citext`, established in the baseline migration `V1__enable_extensions.sql`.

**Why.** Correctness is enforced by the database rather than by every code path
remembering to normalise. A functional index would work equally well, but `citext` also
makes *lookups* case-insensitive without every query having to wrap the column, which
removes a whole class of "login works for one casing only" bugs.

**Trade-off.** A PostgreSQL-specific type, which deepens the commitment to PostgreSQL.
That commitment is already deliberate (see ADR-003).

> **Superseded in Phase 2 — this decision did not survive contact with Hibernate.**
> `CITEXT` is reported to JDBC as `Types#OTHER`, and Hibernate's schema validator rejects
> it against a `String` field: the application refuses to start under `ddl-auto: validate`.
> Every workaround amounts to relaxing that validation, and that check is worth more than
> the syntactic nicety — it is what catches an entity mapping drifting away from the
> migrations.
>
> The replacement keeps the actual guarantee and drops only the spelling: unique indexes
> over `lower(username)` and `lower(email)`. Uniqueness is still enforced by the database,
> still case-insensitive, and the same indexes serve the login lookup. The reasoning in
> the original decision was right; the mechanism was wrong. See ADR-012.
>
> `V1` already installs the extension and migrations are immutable, so it stays installed
> and unused. Dropping it would be tidier and is not worth a migration that could fail on
> a database where something else has come to depend on it.

---

## ADR-006 — Stack traces are logged, never returned

**Problem.** Error responses must be useful to clients without leaking internals.

**Chosen.** A single `ApiErrorResponse` envelope with a stable machine-readable code and
a safe human message. `GlobalExceptionHandler` logs the full stack trace at ERROR and
returns a generic message for anything unanticipated. Spring's own
`server.error.include-stacktrace` and `include-message` are both set to `never`.

**Why.** Exception messages routinely contain SQL fragments, file paths and
configuration values. A unit test (`GlobalExceptionHandlerTest`) asserts specifically
that a thrown exception's message does not appear in the response body.

**Trade-off.** Debugging a client-reported failure requires correlating with server
logs. Request-scoped correlation IDs will be added with the observability work.

---

## ADR-007 — Health checks live in `docker-compose.yml`, not in the images

**Problem.** The frontend's health check was originally a `HEALTHCHECK` instruction in
its Dockerfile while the other four were defined in compose. Liveness policy was split
across two files, and the two halves could disagree.

**Options.** Keep `HEALTHCHECK` in every image; define every check in the orchestrator;
keep both and accept duplication.

**Chosen.** All five checks are defined in `docker-compose.yml`; no image declares a
`HEALTHCHECK`.

**Why.** Liveness is a deployment concern, not a property of the artefact: the same
image is probed differently by compose, by ECS and by Kubernetes, and each orchestrator
wants to own the interval, timeout and retry budget. Defining it once, where the
dependency graph already lives, keeps `depends_on: condition: service_healthy` and the
probe it depends on in the same file.

**Trade-off.** `docker run` on the image alone reports no health status. Acceptable —
the image is never run that way outside of debugging.

---

## ADR-008 — Health checks probe `127.0.0.1`, never `localhost`

**Problem.** The frontend container was permanently `unhealthy` while nginx was in fact
serving correctly.

**Cause.** Inside the container `localhost` resolves to `::1` before `127.0.0.1`. nginx
binds IPv4 only (`0.0.0.0:80`), so the probe was refused. The JVM services happened to
pass the identical check only because Tomcat binds dual-stack (`:::8080`) — the same
latent bug, masked by a coincidence of the runtime.

**Chosen.** Every health check targets `127.0.0.1` explicitly.

**Why.** It removes name resolution and dual-stack behaviour from the probe entirely.
Relying on Tomcat's binding happening to be dual-stack is exactly the kind of accident
that breaks on a base-image upgrade.

**Trade-off.** An IPv6-only listener would not be probed. Nothing in the stack is
IPv6-only, and the check is explicit enough that the assumption is visible.

---

## ADR-009 — Security headers are repeated per `location` in nginx

**Problem.** The configured `X-Content-Type-Options`, `X-Frame-Options` and
`Referrer-Policy` headers were absent from every response.

**Cause.** nginx does not inherit `add_header` from an outer block into any `location`
that declares an `add_header` of its own. Both locations set `Cache-Control`, which
silently discarded all server-level headers. Separately, combining `expires 1y` with an
`add_header Cache-Control` emitted two conflicting `Cache-Control` headers on assets.

**Chosen.** The security headers are declared inside each `location`, with a comment
stating the inheritance rule; `Cache-Control` is set through `add_header` only.

**Why.** This is the behaviour nginx actually has. The duplication is deliberate and
annotated, because the alternative — a correct-looking server-level block that emits
nothing — is far worse than three repeated lines.

**Trade-off.** A new `location` must remember the headers. A future `include` snippet
would fix that; with two locations it is not yet worth the indirection.

**How it was caught.** Asserting on the response headers rather than trusting the
configuration. The same applies to the port-publishing assumption: `ports:` entries for
PostgreSQL and Redis were silently inert because Docker cannot publish from an
`internal: true` network, and only checking real reachability revealed it.

---

## ADR-010 — Server-side sessions in Redis rather than JWT

**Problem.** Phase 2 needs an authentication mechanism for a browser client. The original
specification named JWT with access and refresh tokens.

**Options.**
1. Stateless JWT access token plus a rotating refresh token.
2. JWT held in an HttpOnly cookie, with a Redis denylist for revocation.
3. Server-side session in Redis, delivered as an HttpOnly cookie.

**Chosen.** Option 3.

**Why.** The deciding requirement is that logging out must actually invalidate access, and
that disabling an abusive account must take effect immediately rather than whenever a
token expires. Option 1 cannot do this at all — a stolen access token stays valid for its
full lifetime, and shortening that lifetime just trades the problem for refresh traffic.
Option 2 can, but only by consulting server-side state on every request, at which point the
token is no longer stateless and the design has become a session store with extra
cryptography to get wrong.

JWT earns its keep when many independent services must verify a caller without a shared
store, or when clients are not browsers. Neither holds here: there is one API server, and
the judge worker never authenticates a user. Sessions in Redis mean the API server can be
restarted or scaled horizontally without signing anyone out, which was the only real
advantage option 1 offered.

Storing the session id in an HttpOnly cookie also removes a whole class of failure: there
is no token for JavaScript to read, so an XSS bug does not directly hand over the account,
and no developer is tempted to put a credential in `localStorage`.

**Trade-off, stated plainly.** Authentication now depends on Redis. If Redis is down,
nobody can sign in and existing sessions stop resolving — a JWT design would have kept
serving reads through a Redis outage. That is accepted because Redis is already a hard
dependency of the submission queue, so the system has no meaningful degraded mode without
it. Redis persistence is enabled (`appendonly yes`) so sessions survive a restart of the
container. The second cost is a Redis round-trip per authenticated request; it is a
sub-millisecond local lookup, and it is what buys immediate revocation.

This decision supersedes the JWT and refresh-token requirement in the original
specification, and the `JWT_*` environment variables have been removed rather than left in
place as dead configuration.

---

## ADR-011 — One role per user, stored as text

**Problem.** Users need roles. The original specification listed a separate `Role` entity,
implying a many-to-many relationship.

**Options.** A `roles` join table; a single `role` column constrained to an enum; a bitmask
of permissions.

**Chosen.** A single `role` column holding `USER` or `ADMIN`, mirrored by a Java enum.

**Why.** An online judge distinguishes people who solve problems from people who author
them, and nothing in the roadmap requires one account to hold two roles simultaneously.
Adding a role later — `MODERATOR`, say — means one enum constant and one value in the
`ck_users_role` check constraint, with no change to any authentication code, which is what
"extensible" was actually asking for. A join table would add a query, an eager-fetch
decision and a mapping for a cardinality nothing needs; that is the speculative generality
the brief warns against.

The column is `VARCHAR` holding the enum *name*, never its ordinal. Persisting an ordinal
means reordering the Java enum silently promotes existing users, which is the kind of bug
that is discovered late and in production. A test pins the mapping.

**Trade-off.** A genuine multi-role requirement would need a migration and a join table.
That is a contained change — the `Role` lookup lives behind `AuthenticatedUser` — and is
cheaper than carrying an unused join table through fourteen more phases.

---

## ADR-012 — Case-insensitive identity via functional unique indexes

**Problem.** `alice@example.com` and `Alice@Example.com` must not be able to register as
two accounts. ADR-005 chose `CITEXT`; that turned out not to work (see the note there).

**Options.**
1. Normalise to lower case in application code before every insert and lookup.
2. `CITEXT`, with schema validation disabled or a custom dialect to appease Hibernate.
3. `VARCHAR` with unique indexes over `lower(username)` and `lower(email)`.

**Chosen.** Option 3.

**Why.** Option 1 was rejected in ADR-005 for the right reason and the reason still holds:
correctness that depends on every code path remembering to normalise will eventually meet a
code path that forgets. Option 2 keeps the nicer spelling but pays for it by weakening
`ddl-auto: validate`, which is the check that catches an entity drifting away from the
migrations — a bad trade for cosmetics.

Option 3 keeps the guarantee exactly where it belongs. The database rejects a case-variant
duplicate whatever the application does, Hibernate validates the schema strictly, and the
same indexes serve the login lookup, which queries `lower(username)` and `lower(email)` so
it uses them rather than scanning.

**Trade-off.** Queries must remember to wrap both sides in `lower(...)`, or they will be
case-sensitive *and* miss the index. This is confined to `UserRepository`, where the two
lookups are written once and the derived `existsBy…IgnoreCase` queries generate the same
form. An integration test asserts both indexes exist, are `UNIQUE`, and are functional.

---

## ADR-013 — Integration tests drive real HTTP instead of MockMvc's CSRF shortcut

**Problem.** The authentication tests need to exercise sessions and CSRF.

**Options.** `MockMvc` with `.with(csrf())`; `MockMvc` with a real token round-trip; a real
HTTP client with a cookie jar.

**Chosen.** A real HTTP client (`TestRestTemplate`) wrapped in a small `BrowserClient` that
keeps cookies and echoes the CSRF token back as a header.

**Why.** `.with(csrf())` injects a valid token directly into the request. That makes the
tests pass whether or not the server ever issues the CSRF cookie — and "the cookie is never
delivered, so the SPA's first POST always fails" is exactly the bug most likely to reach
production. It is also precisely the bug this project hit: the first version of the suite
failed with `403` on every registration because a real client had no token to send, which a
mocked token would have hidden completely.

The same reasoning drives the logout test. Rather than asserting that logout returns `204`,
it captures the session cookie, logs out, and replays the captured cookie — proving the
session is gone from Redis rather than merely forgotten by the client.

**Trade-off.** Slower than `MockMvc`, and the client is about eighty lines of test support
code to maintain. Worth it: these tests fail when the mechanism is broken, which is the only
property that matters in an authentication suite.

---

## ADR-014 — Examples and test cases as relational tables, not JSONB

**Problem.** A problem owns an ordered list of worked examples and an ordered list of judge
test cases. Both are replaced wholesale on edit and never queried independently. That is
close to a textbook case for a JSONB column.

**Options.** A `jsonb` column per collection; separate tables; a single table with a
`kind` discriminator.

**Chosen.** Two separate tables, `problem_examples` and `problem_test_cases`.

**Why.** JSONB was genuinely tempting and would have meant fewer joins. Two things decided
against it. First, the brief asks for database constraints as a second validation layer,
and JSONB cannot express "input is not null and at most 100 000 characters" without
contorted assertions over JSON paths — the constraint would exist only in application code,
which is exactly what the second layer is meant to survive. Second, the judge in Phase 7
will read test cases filtered by visibility and ordered by position. That is a query, and
queries belong on columns with indexes rather than inside a serialised blob.

Keeping them as **two** tables rather than one with a discriminator is the security
argument. A single table means every user-facing query needs a `WHERE kind = 'EXAMPLE'`
predicate, and the day somebody forgets one, answer keys ship to solvers. Separate types
cannot be confused: `ProblemDetailResponse` has no field that could hold a test case.

**Trade-off.** Two extra tables and a join to render a problem. The detail query uses an
entity graph so it is one round trip, not N+1.

---

## ADR-015 — UUID for mutation, slug for public URLs

**Problem.** A problem needs a stable identifier for the API and a readable one for URLs.

**Chosen.** Both, with distinct jobs. The UUID `public_id` is the canonical identifier: it
appears as `id` in every response, addresses every admin endpoint, and is what submissions
will reference in Phase 4. The slug addresses the public detail endpoint,
`GET /api/problems/{slug}`.

**Why.** A slug is editable by definition — an author fixes a typo in a title and wants the
URL to match — so hanging foreign keys or admin bookmarks off it would mean a rename breaks
them. A UUID never changes. But a UUID in a shared link is unreadable, and the catalogue URL
is the one users paste to each other, so that one gets the slug.

Sequential ids are never exposed at all. `/api/problems/1` would leak how many problems
exist and invite enumeration; the internal `BIGSERIAL` stays internal, where it keeps
foreign keys and indexes compact.

**Trade-off.** Two lookup paths to maintain, and the rule has to be remembered. It is
written down here and in the controller javadoc, and the slug is deliberately *not* changed
by an ordinary content update — only by supplying one explicitly.

---

## ADR-016 — Attribution columns and structured logs, not an audit table yet

**Problem.** Problem mutations must be traceable to an administrator.

**Options.** A dedicated `audit_log` table; attribution columns on the entity plus
structured logging; both.

**Chosen.** `created_by` and `updated_by` foreign keys to `users`, plus a structured log
line per mutation naming the actor, the action, the problem and the resulting status.

**Why.** This answers the question actually being asked — "who last changed this problem,
and when" — from the row itself, with a foreign key that cannot drift. The brief explicitly
permits documenting why the existing approach suffices rather than building the table.

An `audit_log` table earns its place when there are several mutating domains to correlate
and a need to see *history* rather than current attribution: who published this and then
unpublished it an hour later, or which administrator disabled that account. Problems are the
first such domain. Building the table now would mean designing its schema around a single
writer and guessing at the rest, which is how audit tables end up with a `metadata` JSON
column that nobody can query.

**Trade-off, stated plainly.** Today the system records the *current* author and editor,
not a history. An administrator who publishes and then unpublishes leaves only the log line
behind, and logs rotate. This is a real limitation, not a feature, and the table lands with
the admin surface in a later phase.

**Not logged:** test case inputs and expected outputs. `ProblemTestCase.toString()`
deliberately omits both so an answer key cannot reach a log file, and a test asserts it.

---

## ADR-017 — Search patterns are built in Java, not in the query

**Problem.** The catalogue supports an optional case-insensitive substring search. The
obvious JPQL is `LOWER(p.title) LIKE LOWER(CONCAT('%', :search, '%'))`.

**What went wrong.** That form failed against PostgreSQL on *every* listing request,
including those with no search term at all: `ERROR: function lower(bytea) does not exist`.
When a parameter is null, PostgreSQL cannot infer its type from a function argument
position, falls back to `bytea`, and the statement fails before the `:search IS NULL` guard
can spare it. The guard is evaluated at runtime; the type check happens at parse time.

**Chosen.** The service turns the term into a complete LIKE pattern —
lower-cased, wildcard-wrapped, with `%`, `_` and the escape character themselves escaped —
and the query compares against a bare parameter with an explicit `ESCAPE '!'` clause.

**Why.** No function is applied to the parameter, so there is nothing for PostgreSQL to
mis-infer. It also lower-cases the term once instead of once per row, and escaping the
user's own wildcards means a search for `100%` matches the literal text rather than
everything in the catalogue.

**Trade-off.** The pattern syntax now lives in Java rather than being visible in the query.
The helper is four lines and commented; the alternative was a query that did not run.

---

## ADR-018 — The submission row is the outbox record

**Problem.** A submission must be committed to PostgreSQL *and* published to Redis. If the
API dies between the two, the submission exists and nothing will ever judge it. The brief
asks for this not to be hand-waved.

**Options.**
1. Publish inside the transaction.
2. Publish after commit and accept the gap.
3. A separate `submission_outbox` table, written in the same transaction, drained by a publisher.
4. Treat the submission row itself as the outbox record.

**Chosen.** Option 4, with a recovery sweeper.

**Why.** Option 1 is the tempting wrong answer: a worker can then claim a submission that
does not exist yet, or that is about to be rolled back. Option 2 is what "we'll add
reliability later" looks like, and it loses submissions in exactly the case nobody tests.

Option 3 is the textbook shape and would work. It was rejected because it creates a second
row describing the same fact — "submission 5 exists" and "submission 5 needs queueing" — which
then have to be kept consistent with each other, and because the brief explicitly wants
PostgreSQL to remain the single source of truth rather than the queue.

Option 4 collapses those two rows into one. `enqueued_at` is the publication marker: NULL
means the push has not been confirmed. It satisfies every property the outbox pattern asks
for — the submission and its intent are created atomically, a publisher transfers pending
work to Redis, processing is idempotent, and published work is marked — with one row and one
lifecycle.

Two orderings are load-bearing. Publication fires **after commit**, so no worker sees a
submission that might roll back. The push happens **before** the marker is written, so a
crash in between republishes (a harmless duplicate) rather than marking something published
that never arrived (a silent loss).

**Trade-off.** A submission can be delivered more than once, and the fast path can fail
silently and be repaired seconds later by the sweeper rather than instantly. Both are
acceptable because claiming is atomic, and both are far better than the alternative failure
mode, which is losing work.

---

## ADR-019 — At-least-once delivery with an atomic claim, not exactly-once

**Problem.** Two workers must never judge the same submission, and a crash must not lose one.

**Chosen.** At-least-once delivery from Redis, made safe by an atomic claim: a single
`UPDATE ... SET status = 'RUNNING' ... WHERE public_id = ? AND status = 'QUEUED' RETURNING ...`

**Why.** Exactly-once is not achievable and claiming it would be dishonest: a worker can die
between taking a job and recording that it did, and no amount of queue machinery closes that
gap. What *is* achievable is making a second delivery harmless.

The predicate is evaluated inside the same statement that changes the row, so PostgreSQL's
row lock picks the winner. Of two workers racing, one sees a row returned and the other sees
none. The obvious implementation — `SELECT` then `UPDATE` — lets both read QUEUED and both
proceed, which is precisely the bug this phase had to avoid. A test races eight threads at
one submission and asserts exactly one claim.

`BLMOVE` moves a job from `pending` to `processing` atomically, so it is never in neither
list; a plain `BRPOP` would delete the job before the worker had done anything with it.

Result writes carry the same guard (`WHERE status = 'RUNNING' AND claimed_by = ?`), so a
worker whose lease expired writes nothing rather than overwriting a newer verdict.

**Trade-off.** A submission can be *started* twice if a worker is slow enough for its lease
to expire, wasting one container. Wasting a container is cheaper than losing a submission or
recording a stale verdict.

---

## ADR-020 — The worker gets the Docker socket; the sandbox never does

**Problem.** Running untrusted code in containers requires talking to a container runtime.
Something has to hold that capability.

**Options.**
1. Mount the host Docker socket into the worker.
2. A Docker-in-Docker sidecar, so sandboxes run inside a containerised daemon.
3. Run the worker directly on the host, outside compose.

**Chosen.** Option 1, documented rather than glossed over.

**Why.** Option 3 breaks the "one command brings the stack up" property and makes the
development environment diverge from the deployed one.

Option 2 is genuinely better isolation — an escape lands in the dind container rather than on
the host — and was seriously considered. It was rejected for this phase on a practical
ground: dind needs `--privileged` (a host risk of its own) and needs to pull the language
images itself, which means giving it internet access. The worker currently sits on a network
declared `internal: true` with no route off it, and dind would have to break that. Trading a
documented risk for a different undocumented one is not an improvement.

**The risk, stated plainly.** Access to the Docker socket is equivalent to root on the host.
If the worker process is compromised, the host is compromised. What the socket mount does
*not* do is widen what a *submission* can reach: sandboxes get no socket, no host volume and
no network, and a test asserts the socket is absent inside one.

**Trade-off and the fix.** This is the largest piece of un-hardened surface in the system and
is recorded as such in docs/security.md. Phase 12 should move to a rootless or remote daemon
dedicated to judging, and/or a runtime with its own kernel boundary (gVisor, Kata,
Firecracker), so that neither a worker compromise nor a container escape reaches the host.

---

## ADR-021 — Language is an enum carrying no executable configuration

**Problem.** The client must choose a language. Compilers and interpreters are commands.
Those two facts must never meet.

**Chosen.** `Language` is an enum in the shared module holding a display name and a file
extension — nothing executable. The image, compile command and run command live in
`LanguageSpec` in the worker, as compile-time argv constants.

**Why.** The client sends `"CPP"`. If that string does not name a constant, deserialisation
fails and the request is rejected before any code runs. There is no code path by which a
request-supplied value reaches a process argument, because the only thing that crosses the
boundary is an enum constant.

Commands are argv arrays handed to a `ProcessBuilder`, never strings handed to a shell, so a
source file containing a shell metacharacter is a file whose name never appears on a command
line and whose contents are only read by a compiler inside a throwaway container. Source file
names are fixed per language, which removes path traversal by construction rather than by
filtering.

`LanguageSpecTest` asserts that no command invokes a shell, that no argument contains shell
metacharacters, and that every image is pinned to an explicit tag — assertions that look
pedantic and exist to fail the day somebody makes a command line configurable.

**Trade-off.** Adding a language needs a code change and a deployment, not a configuration
row. That is the point.

---

## ADR-022 — The worker uses JDBC, not the API server's JPA entities

**Problem.** The worker needs submissions, problems and test cases. The API server already
has JPA entities for all three.

**Options.** A shared module containing the domain model; the worker owning duplicate
entities; the worker using plain SQL.

**Chosen.** Plain SQL through `JdbcTemplate`, with a tiny shared module holding only the two
enums both services must agree on (`Language`, `SubmissionStatus`).

**Why.** The worker issues four statements, one of which is an atomic claim that has to be
hand-written SQL regardless. Sharing entities would mean a shared module containing the whole
domain, two independently deployable services locked to one persistence mapping, and
lazy-loading hazards in a process that runs outside any request scope — in exchange for
nothing the worker needs.

Keeping `Language` and `SubmissionStatus` shared *is* worth it: a status machine only one
service knew would be a rule the other could violate. This is the `common` module ADR-001
anticipated, kept deliberately small so it does not become the dumping ground that couples
the two services together.

**Trade-off.** The two services' notions of a submission can drift, since no compiler checks
the worker's SQL against the API's entities. Integration tests against the real schema are
what catch that, and `ddl-auto: validate` catches it on the API side.

---

## ADR-023 — Server-Sent Events for live status, not WebSockets

**Problem.** A submission takes seconds to judge. The page showing it has to move from
QUEUED to RUNNING to a verdict without the user reloading.

**Options.** Polling on a timer; WebSockets; Server-Sent Events.

**Chosen.** SSE as the primary transport, with a bounded poll as the fallback.

**Why.** The traffic here is entirely one-directional: the server has something to say and
the browser has nothing to reply. SSE is exactly that shape, and it costs one endpoint
returning an `SseEmitter` — no second protocol, no upgrade handshake, no separate
authentication story. The session cookie authorises the stream the same way it authorises
every other request, and `SubmissionStreamController` runs the *same* authorisation check as
the REST endpoint before the first byte is written.

WebSockets would buy a channel back from the browser that nothing in this phase needs, and
would cost a protocol that proxies handle less predictably and that Spring Security's servlet
filter chain does not cover after the upgrade.

Polling alone was the Phase 4 behaviour and is kept only as the fallback, because it is the
one thing guaranteed to work when a proxy strips the stream.

**Trade-off.** Each open stream pins a servlet container thread, which is why
`SubmissionStreamRegistry` caps concurrent connections (default 500) and refuses past it —
a refusal the client cannot distinguish from a failure, and does not need to, because both
lead to the same fallback. A rejected connection still gets its snapshot before the stream
closes, so no client is left with nothing.

---

## ADR-024 — Redis Pub/Sub carries a notification; PostgreSQL stays authoritative

**Problem.** The worker learns the verdict. The API instance holding the browser's stream is
a different process, possibly on a different host.

**Options.** The worker writes to the browser somehow; the event payload carries the new
state and the API forwards it; the event carries only "something changed" and the API
re-reads.

**Chosen.** The worker publishes a three-field event to one Redis channel. Every API
instance subscribes. On receiving one, `SubmissionEventSubscriber` **re-reads the submission
from PostgreSQL** and sends *that* to the browser. The event payload is never forwarded.

**Why.** This is what keeps Redis out of the trust path. The event is a doorbell, not a
letter: if it is duplicated, delayed, reordered or lost, the worst case is a redundant read
or a late one, never a browser shown a status the database does not hold. Redis restarting
with an empty keyspace loses notifications and loses nothing else.

Forwarding the payload would have made Redis a second source of truth for submission state,
and a cheaper, less durable one than the database that already holds it.

The re-read is cheap and it is skipped entirely when nobody is watching — the common case,
since most submissions are judged with no stream open.

**Delivery semantics, stated plainly.** This is **at-least-once, best-effort** delivery, and
it is not exactly-once. Redis Pub/Sub retains nothing: an API instance that is restarting
when a message is published does not receive it, and there is no replay. Three things make
that acceptable rather than merely tolerated:

1. Every stream sends a **snapshot** of current state as its first event, so a client that
   connects late or reconnects after a gap starts from the truth.
2. The client's reducer (`applyStreamEvent`) is **convergent**: it applies an event only if
   it is strictly newer than what it has already applied, and a terminal status absorbs
   everything after it. A duplicate is a no-op, a straggler is discarded, and a client that
   has seen ACCEPTED can never be walked back to RUNNING.
3. A **bounded fallback poll** repairs the case where the stream dies silently.

No part of the system claims guaranteed real-time delivery, and the UI does not pretend to:
when the stream is unavailable the page says so and says it is checking periodically.

**Trade-off.** A verdict can reach a browser later than it reaches the database — in the
worst case one fallback poll interval (2.5 s), or not until reload if every transport fails.
That is a latency guarantee we do not make, in exchange for never displaying a state the
database does not hold.

---

## ADR-025 — Per-test results record an outcome and nothing else

**Problem.** "3 of 5 tests passed" is not enough to act on; which ones failed matters. But
test inputs and expected outputs are the answer key, and hidden ones must stay hidden.

**Options.** Store the full per-test detail and filter it on read; store a diff; store only
the outcome.

**Chosen.** `submission_test_results` has columns for position, pass/fail, runtime and a
`hidden` flag. **There is no column that can hold test data**, so there is nothing to filter
on the way out.

**Why.** A filter is a rule somebody can forget to apply — on a new endpoint, in a new
projection, in a query written next year. A schema with nowhere to put the secret cannot
leak it through any of those. The `TestResultResponse` DTO has the same shape for the same
reason, and the frontend test asserts the shape rather than the rendering, because the shape
is the actual guarantee.

`hidden` is denormalised onto the row at judging time rather than joined from `test_cases`.
Visibility is a property of what was judged: flipping a test case from example to hidden
afterwards must not retroactively change what a past result was allowed to say.

A `runtime_ms` of NULL means the test never ran, which is genuinely different from failing
it — judging stops at the first failure. The UI renders those three states distinctly, and
never as a failure the user's program did not cause.

**Trade-off.** A user who fails a hidden test is told only that they failed it. That is the
intended trade: debuggability loses to the integrity of the answer key.

---

## ADR-026 — Memory is enforced but not measured, and the DTO says nothing about it

**Problem.** A judge conventionally reports memory used alongside runtime. Phase 4 enforces
a memory ceiling; it does not measure consumption.

**Options.** Report a number obtained some approximate way; keep a `memoryKb` field that is
always null; remove the field.

**Chosen.** Removed `memoryKb` from the API and the frontend types. The limit is still
enforced — the container is capped with `--memory` and `--memory-swap`, and the kernel OOM
killer produces MEMORY_LIMIT_EXCEEDED — but nothing reports how much was used.

**Why.** Measuring peak RSS of a process inside a `--read-only`, `--cap-drop ALL` container
requires either reading the container's cgroup from the host after exit (racy — the cgroup is
gone once the container is reaped) or instrumenting the sandbox image with a supervisor. Both
are real options; neither is Phase 5's job.

Between the remaining two, an always-null field is worse than no field. It reads as a bug to
every client author, and it is the kind of thing that quietly acquires a plausible-looking
value later. The brief is explicit that unimplemented features must not be claimed, and a
schema field is a claim.

**Trade-off.** Users see runtime but not memory, and MEMORY_LIMIT_EXCEEDED says the ceiling
was hit without saying by how much. Restoring the number means committing to a sandbox image
we control, which is the same work Phase 12's isolation hardening implies.

---

## ADR-027 — Shutdown does not wait on event streams

**Problem.** Spring Boot's graceful shutdown waits for in-flight requests to finish. An SSE
stream is an in-flight request that is *designed* not to finish — it stays open until the
verdict lands or its timeout expires. Every restart therefore stalled for the full 30-second
grace period whenever anyone happened to be watching a submission, which is precisely when
the server is in use.

It surfaced as a forked test JVM that would not exit: `SubmissionStreamIT` alone reproduced
it, and Surefire killed the fork thirty seconds after `System.exit(0)`.

**Options.** Disable graceful shutdown; shorten the SSE timeout to something shutdown can
outwait; close the streams explicitly before shutdown begins; bound the shutdown wait.

**Chosen.** Two changes, because the first one alone did not work.

1. `SubmissionStreamRegistry` implements `SmartLifecycle` at a phase above the web server's
   graceful-shutdown lifecycle, and completes every open emitter there.
2. `spring.lifecycle.timeout-per-shutdown-phase` is set to **5s**, down from the 30s default.

**Why both.** The first change is correct and necessary — a stream with a live browser on the
end of it is released cleanly, and the browser reconnects. But it is *not sufficient*, and
the measurement says so plainly: after the change the log shows `SSE_SHUTDOWN closed=3`
immediately followed by `Commencing graceful shutdown`, and then the full thirty-second wait
anyway. A stream whose client has **already disconnected** stays counted as in-flight by
Tomcat even after the server completes its emitter, and nothing the application can do from
the registry releases it.

So the wait itself is bounded. Five seconds is generous for what genuinely needs draining
here: no non-streaming endpoint in this service does anything slower than a few indexed
queries.

Closing streams is safe in a way that cutting off an ordinary request would not be. The
verdict lives in PostgreSQL, not in the connection. `EventSource` reconnects on its own, and
the reconnect is handed a snapshot of current state — a client whose stream is cut during a
deploy converges through exactly the path ADR-024 already relies on.

**Trade-off.** A genuinely slow non-streaming request could now be cut off at five seconds
rather than thirty. Nothing in this service is expected to run that long, and the alternative
is a restart that stalls for half a minute every time the judge is in use.

**What is still true and not claimed away.** An abandoned SSE connection remains counted as
in-flight until Tomcat notices, so shutdown still waits — it simply waits five seconds
instead of thirty. Removing the wait entirely would mean reaching into the servlet
container's async bookkeeping, which is not worth the coupling.

`SubmissionStreamRegistryTest` pins the part that is enforceable in code: that stopping
completes every registered emitter, and that the registry's phase sits strictly above
`WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE` — so a Spring Boot upgrade that
moves that constant fails the build rather than silently restoring the stall.

---

## ADR-028 — A separate execution service holds the Docker socket

**Problem.** Running untrusted code in disposable containers requires talking to a container
runtime, and a Docker socket is host-equivalent: anything holding one can start a privileged
container that mounts the host filesystem. Until Phase 6 the holder was the judge worker —
the same process that parses untrusted program output, connects to PostgreSQL and Redis, and
carries the judging logic. Any remote-code-execution bug anywhere in the worker was therefore
a host compromise. ADR-020 named this as the largest piece of unhardened surface in the
system and deferred it.

**Options.**

1. Leave it, and document the risk again.
2. A Docker socket proxy restricting the HTTP API surface.
3. Rootless Docker.
4. A dedicated execution service exposing a narrow, typed contract.

**Chosen.** Option 4. A new `executor` module holds the socket and exposes four operations:
prepare a workspace for one of three languages, compile it, run it, discard it.

**Why.** The question is not whether *something* holds the socket — something must — but how
much else that something does, and what authority a caller inherits by compromising the
caller.

A **socket proxy** (option 2) was considered seriously and rejected on the merits. Proxies of
this kind filter by endpoint and method, not by request body, and the worker genuinely needs
`POST /containers/create`. A caller that can create containers can create one with a bind
mount of `/`, so the proxy would block image builds and `exec` while leaving the actual
escape route open. It looks like a fix and is not one.

**Rootless Docker** (option 3) is a genuine fix and remains the right long-term answer, but
it is a deployment change that breaks the standard Docker Desktop setup this project is
developed against. It is named in the threat model as future work rather than quietly
skipped.

The contract is what makes option 4 worth the extra service:

```java
record PrepareRequest(String submissionId, Language language, String source)
record CompileRequest(ExecutionLimits limits)
record RunRequest(String stdin, ExecutionLimits limits)
```

There is no field for an image, a mount, a capability, a network, a user, an entrypoint or a
device. `Language` is an enum, so the image is chosen by the executor from a fixed table.
Limits are clamped server-side, so a caller asking for a twelve-hour, sixty-four-gigabyte run
gets the configured maximum. A worker compromised completely inherits *this*, which is "can
ask for a sandboxed program to be run" — not host control.

The executor is kept deliberately dull for the same reason: no database driver, no Redis
client, no security starter, no user model, one caller, one shared secret. The less it does,
the less there is to take. Its dependency list is short on purpose and each absence is noted
in the pom.

**Trade-off.** One more service to deploy, one more hop per execution, one more shared secret
to manage, and a stateful HTTP contract (prepare/compile/run/close) that needs idle-timeout
and shutdown handling so a vanished caller cannot strand a volume. Measured overhead is a few
milliseconds per call against executions that take hundreds; the cost is real but small
against what it buys.

**What this does NOT fix, stated plainly.** Whatever holds the socket is host-equivalent if
*it* is compromised. That is now the executor. The surface has been made much smaller, not
zero. See docs/threat-model.md §7.1.

---

## ADR-029 — A custom seccomp allowlist, built from observed requirements

**Problem.** Docker's default seccomp profile is good but general: it is designed for
arbitrary workloads, and permits syscalls a judge has no use for. `ptrace` is the clearest
example — it is not capability-gated between processes of the same user, so dropping
capabilities does not block it.

**Options.** Keep the default; add a denylist; replace it with an allowlist.

**Chosen.** Replace it with a narrower allowlist (`sandbox/seccomp/codearena.json`).

**Why an allowlist.** Docker has no notion of layering profiles — supplying one replaces the
default entirely — so the choice is really allowlist versus denylist. A denylist lets a
syscall added by a future kernel through by default, which is exactly the failure mode that
matters for a sandbox. An allowlist fails closed.

**How it was built.** From observed requirements, as the only way that works: an allowlist
assembled from intuition breaks real programs in ways that look like judge bugs. The list was
validated by compiling and running real C++, Java and Python workloads under it, and the
language tests keep validating it — remove a needed syscall and the build fails rather than
the submissions.

**Evidence it is doing something.** `seccompDeniesSyscallsTheDefaultProfileAllows` asserts
`ptrace` returns `EPERM`. Under the daemon default the same call returns 0 (measured). So the
test fails if the profile is ever silently not applied — which is the failure worth catching,
since a missing profile degrades quietly by design.

**Why networking syscalls are allowed.** `socket`, `connect`, `sendto` and the rest are in
the allowlist. Network isolation comes from `--network none`, which gives the container no
interface to use — a stronger and far less brittle control than syscall filtering, since both
the JVM and CPython create sockets during ordinary startup. A `connect()` in this sandbox
fails because there is nowhere to connect to, and a test proves it by trying.

**Trade-off.** An allowlist can break a future program that needs a syscall nobody thought
to include, and the symptom (`EPERM` from deep inside a runtime) is confusing. The mitigation
is that the profile path is configurable and the language tests exercise all three
toolchains. If the file cannot be read the sandbox falls back to the daemon default — weaker
than ours, but still an allowlist, never "no filtering" — and logs a warning and reports it
on the health endpoint rather than degrading in silence.

---

## ADR-030 — Sandbox images are built here, pinned by digest, and stripped

**Problem.** The sandbox images were upstream tags — `gcc:13-bookworm`,
`eclipse-temurin:21-jdk-alpine`, `python:3.12-alpine` — pulled at need. Three problems: tags
move, so the environment submissions run in changed without anyone deciding; the images carry
a great deal that a judge has no use for; and pulling at judging time means judging depends
on a registry.

**Chosen.** Build our own from `sandbox/*.Dockerfile`, pin the bases **by digest**, strip
what a compiler does not need, and create containers with `--pull never`.

**Why by digest.** A tag is a moving target: the same `gcc:13-bookworm` is rebuilt with new
package versions. An image that changes underneath the judge changes what programs are
compiled against, which is a verdict-affecting change nobody reviewed. Updating a digest is a
deliberate edit.

**What is stripped, and why it is worth doing.** Package managers, network and remote-access
tooling, account-management tooling, documentation and caches — and **every setuid/setgid
bit**. The Debian gcc base ships thirteen setuid binaries including `su`, `mount`, `passwd`
and `ssh-keysign`.

`no-new-privileges` already prevents a setuid binary from raising privilege, so this is
defence in depth rather than the only control. It is still worth doing: a sandbox has no use
for any of them, and the cheapest way to not be exploited through a program is to not ship
the program.

The build **asserts** rather than hopes. Each Dockerfile fails if any setuid bit survives,
and each exercises its toolchain afterwards — so an image stripped past the point of working
fails to build instead of failing every submission.

**Why `--pull never`.** Judging must not reach a registry: it is a network dependency in the
one path that is supposed to have no network, and a slow or hostile registry becomes a
judging outage. A missing image is therefore a hard failure, which the health indicator
reports at startup so somebody who forgets `sandbox/build-images.sh` finds out immediately
rather than through a run of failed submissions.

**Trade-off.** A build step before a fresh clone can judge anything, and three image
definitions to maintain and update. The C++ image is still large (~2 GB) because a full GCC
toolchain is large; splitting compile and run images would shrink the run image
substantially, and is not done here.

---

## ADR-031 — Disk is bounded by what the kernel actually enforces

**Problem.** A submission that writes until the disk is full denies the judge to everybody.
Phase 4 bounded `/tmp` with a sized tmpfs, but the workspace volume had no bound at all.

**Options.** `--storage-opt size`; a filesystem quota; `RLIMIT_FSIZE`; accounting after the
fact.

**Chosen.** A read-only workspace during runs, a size-capped tmpfs as the only writable
filesystem, and `RLIMIT_FSIZE` as a hard per-file ceiling. Explicitly **not**
`--storage-opt`.

**Why not `--storage-opt size`.** It is the obvious control and it does not work here. This
daemon accepts the flag and silently does not enforce it: a container capped at 64 MB wrote a
100 MB file successfully, because overlayfs on this host has no project quota. Configuring it
would produce something that reads like a disk limit in every review and is nothing of the
kind — worse than leaving it out, because it stops anyone looking further.

**What actually holds.** At run time the workspace is mounted read-only and the only writable
filesystem is a tmpfs whose pages are charged to the container's memory cgroup — so filling
it is an OOM kill, and the host disk is never touched at all. During compilation the
workspace must be writable, and there the bound is `RLIMIT_FSIZE` per file plus the compile
timeout.

`RLIMIT_FSIZE` is a real kernel limit: exceeding it raises SIGXFSZ. That produces exit code
153, which is mapped to its own execution outcome and then to a `RUNTIME_ERROR` (or a
`COMPILATION_ERROR`) explaining what happened — "exited with signal 25" explains nothing to
the person who has to fix it.

**Why `--ulimit nproc` is not set, while `--pids-limit` is.** RLIMIT_NPROC counts processes
per *uid across the whole host*, and every sandbox runs as the same uid 65534. Setting it
would let one submission's processes count against a concurrent submission's budget — two
runs interfering with each other, which is the exact opposite of what the isolation is for.
`--pids-limit` is the correct control because it is per-cgroup.

**Trade-off, stated rather than hidden.** Total bytes written during compilation is bounded
only indirectly. A filesystem with project quotas (XFS with `prjquota`, btrfs, ZFS) would
allow a real per-container limit; that is a deployment property rather than a code change,
and it is recorded in docs/threat-model.md §7.3 as a residual limitation rather than a solved
problem.

---

## ADR-032 — A contest's status is derived, not stored

**Problem.** A contest is DRAFT, UPCOMING, LIVE, ENDED or CANCELLED. The obvious design is a
`status` column advanced by a scheduled job at `startAt` and `endAt`.

**Options.** A stored status with a scheduler; a stored status advanced lazily on read; a
status computed from the schedule and the clock.

**Chosen.** Store only what a human decided — DRAFT, PUBLISHED or CANCELLED — and compute
what users see from that plus `startAt`, `endAt` and the current time.

**Why.** A stored status has to be advanced by something, and that something can be late or
absent. If the application is down at `endAt`, or the job is delayed, or the clock skews, the
row says LIVE after the contest is over — and a late submission is accepted because a
background task had not got round to it. The failure is silent, it favours whoever submits at
exactly the wrong moment, and it is discovered by the person it costs a place.

Deriving the status removes the failure mode rather than making it less likely. A contest ends
on time whether or not anything is running to notice; a restart cannot resurrect a finished
contest; and there is no job to monitor, retry or reconcile. The brief asked that the
application being down at the exact start or end time must not matter, and this is the only
design where it genuinely does not.

The distinction the model makes is between a *decision* and a *consequence*. Publishing and
cancelling are decisions, and they are stored. Starting and ending are consequences of the
schedule, and they are computed.

**The window is half-open, `[startAt, endAt)`.** At exactly `startAt` a contest is LIVE; at
exactly `endAt` it is ENDED. An inclusive end would leave one instant belonging to both
states. `ContestStatusTest` asserts each boundary as an equality, which is only possible
because the rule is a pure function — a system reading `Instant.now()` internally could only
be tested by waiting.

**Trade-off.** Filtering a contest list by status cannot be done in SQL, because the status is
not a column; the service filters after deriving. At this scale that is cheap, and the
alternative is encoding the time arithmetic into a query so that the rule lives in two places
and can disagree with itself.

---

## ADR-033 — Registration closes when the contest starts

**Problem.** May somebody register for a contest that is already running?

**Options.** Allow it; forbid it; allow it with a per-participant clock.

**Chosen.** Forbidden. Registration is open only while the contest is UPCOMING.

**Why.** The penalty in an ICPC-style contest is measured from the *contest's* start, not the
contestant's. Somebody joining ninety minutes into a three-hour contest competes for half the
time while being charged from the beginning, so their score is not comparable with anybody
else's — and the standings stop meaning what they claim to mean.

Making late entry fair needs a per-participant start time, a penalty basis relative to it, and
a scoreboard that explains why two contestants with identical solves have different penalties.
That is a different product decision with its own rules, not a looser check on this one.

**Trade-off.** Somebody who finds a contest ten minutes after it starts cannot take part, even
though there is no technical obstacle. That is a real cost, and the alternative is a
scoreboard that quietly compares unlike things.

---

## ADR-034 — Standings are computed per request, from submissions

**Problem.** A scoreboard is the most-read page of a live contest and the most expensive to
produce. It could be stored and updated incrementally, cached, or computed on demand.

**Options.** A materialised `contest_scores` table updated when a submission settles; a cached
scoreboard invalidated on the same event; computation from the submission history per request.

**Chosen.** Computed per request. Three bounded queries and a pure function. Nothing is
stored, nothing is cached.

**Why.** Every alternative introduces a second copy of the truth that has to be kept in step
with the first. A stored score is updated when a submission reaches a terminal state — so it
must be updated exactly once per submission, in the same transaction, and never for a
SYSTEM_ERROR that is later retried, and correctly when a result is recorded twice by a
straggling worker. A missed or doubled update shows a wrong score indefinitely, and the bug is
invisible until somebody disputes a result, by which time the contest is over.

Computing from the submissions means the scoreboard cannot disagree with the submissions,
because it *is* the submissions. There is no invalidation to miss.

The cost is bounded by design. The aggregation runs in PostgreSQL against
`ix_submissions_contest_scoring`, and what crosses the wire is one row per contestant plus one
per solved cell — **contestants × problems, not submissions**. A contest where everyone
submits fifty times costs the same to score as one where everyone submits once.

Live updates are a 15-second poll, not SSE. The existing stream infrastructure is keyed by
submission id and fans out to the one person watching that submission; standings are per
contest and would need a different fan-out, its own authorisation, and its own connection cap.
Polling an endpoint that is already fast is the smaller and more predictable thing.

**Trade-off.** A contest with tens of thousands of participants would want a materialised
scoreboard, and this design would need revisiting. The point at which that becomes true is
measurable — it is the point where the aggregation stops being fast — rather than something to
guess at now by building the complicated version first.

---

## ADR-035 — Contest history is durable; cancellation is not deletion

**Problem.** What happens to a contest an administrator no longer wants?

**Options.** Delete it; soft-delete it; cancel it and keep everything.

**Chosen.** Cancel it. Deletion is permitted **only** for a draft that was never published,
has no participants and has no submissions.

**Why.** A contest that people entered is a record of what they did. Deleting it either takes
their submissions with it — destroying work they may want to look at years later — or leaves
those submissions pointing at a contest that no longer exists. Neither is a reasonable answer
to "we are not running this after all".

Cancelling says the same thing without destroying anything: submissions stop immediately, the
submissions already made are kept, and the standings remain readable and marked cancelled.

The rule is enforced twice, on purpose. `ContestAdminService` refuses, and
`fk_submissions_contest` is `ON DELETE RESTRICT` so the database refuses too. The service
check produces a clear 409; the constraint means a mistake in some future code path still
cannot destroy submission history.

`contest_problems` and `contest_participants` cascade from the contest, because they have no
meaning without it — but they can only cascade for a contest that is deletable at all, which
is one nobody has taken part in.

**Trade-off.** Cancelled contests accumulate. They are cheap, they are visible to the people
who registered for them, and a platform that quietly erases a contest somebody competed in is
worse than one with a few dead rows.

---

## ADR-036 — An audit log, not an event store

**Problem.** Phase 3 deferred a formal audit log, leaving only `createdBy`/`updatedBy`
attribution on a few entities. That answers "who last touched this" and nothing else: it
cannot say what changed, when, who tried and failed, or who was refused.

**Options.** Extend attribution columns; adopt event sourcing, with domain state derived from
an event stream; or add a separate append-only table of recorded events.

**Chosen.** A separate `audit_events` table. Domain state stays exactly where it is.

**Why not event sourcing.** It is the tempting answer and it is the wrong one here. Making
the event stream authoritative would mean rewriting every aggregate in the system — users,
problems, contests, submissions, scoring — replacing direct state with projections, and
inheriting the whole apparatus of snapshots, replay, versioned events and schema evolution.
The brief rules it out explicitly, but it would be wrong even without that: the existing
model is not the problem being solved. Nothing about "who published this contest" requires
the contest itself to be a fold over events.

So the direction of dependency is one-way, and stated in the migration: **audit events
describe changes; they never define them.** Deleting every row would lose the history of who
did what and change no password, no problem's status and no verdict.

**What follows from that.** Audit rows carry no foreign keys. Both `actor_user_id` and
`entity_id` are plain values, because a record must outlive the thing it describes — a
foreign key has to do *something* when its target is deleted, and CASCADE erases exactly the
history worth keeping. `actor_username` is denormalised for the same reason: a join that
returns nothing is not an answer to "who did this".

**Taxonomy.** Actions are business events, not method calls. Reads are absent entirely: they
are the bulk of traffic, they change nothing, and auditing them produces a log so noisy the
events that matter cannot be found. That failure mode — an audit log nobody can use — is more
likely than the one where a missing read event mattered.

**Trade-off.** The log is not a complete record of everything that happened, and cannot
reconstruct state. Both are deliberate.

---

## ADR-037 — Audit consistency: success in the transaction, failure outside it

**Problem.** When should an audit event be written relative to the change it describes, and
what happens if the audit write itself fails?

**Options.** Write before the mutation; write after commit; publish asynchronously; write
inside the mutation's transaction.

**Chosen.** Two modes, chosen per event kind.

**Administrative mutations join the caller's transaction** (`Propagation.MANDATORY`). The
event and the change commit together or not at all, which buys both directions:

- A rolled-back mutation leaves no record claiming it happened. Writing beforehand, or
  publishing asynchronously, produces a log that confidently describes changes which never
  occurred — worse than no log, because it is believed.
- A mutation cannot commit without its audit row. If the audit write fails, the transaction
  fails. For a security-critical administrative change, "it succeeded but we cannot say who
  did it" is not acceptable, so the audit write is allowed to veto the mutation.

The second direction is the deliberate one. Swallowing the failure would give a system that
*appears* audited and is not, which is the worst of the available outcomes.

`MANDATORY` rather than `REQUIRED` so that calling it outside a transaction fails
immediately and loudly, rather than committing a lone row that survives a rollback — a
mistake that would otherwise be discovered during an incident.

**Failures and denials use their own transaction** (`REQUIRES_NEW`). A failed login changes
nothing and ends in a 401 thrown from a controller; a denied request is refused by a servlet
filter before any transaction exists. Joining a rolling-back transaction would discard
precisely the record worth keeping, since a burst of failed logins is the clearest attack
signal this system produces.

There a database failure is logged at ERROR and swallowed. The alternative turns a failed
login into a 500 and hands an attacker an oracle: a real account and an imaginary one would
fail differently.

> **The trade, stated rather than assumed: a lost failure-audit row is possible; a lost
> success-audit row is not.**

**No message bus.** Kafka or an outbox would add a second durability story and a window in
which a mutation is committed and its event is not. The transactional write has neither, and
this system already uses the one-row-one-truth pattern for submissions (ADR-018).

**Immutability is enforced three times**: the entity is `@Immutable`, no repository method or
endpoint mutates, and a database trigger raises on UPDATE and DELETE. The third matters
because the row most worth tampering with is the one recording the tamperer, and "the code
does not do that" is a weaker guarantee than "the database refuses".

That trigger also settles a question the design would otherwise have to answer badly: it is
what made a foreign key on the actor impossible, since `ON DELETE SET NULL` is an UPDATE. The
constraint and the invariant could not both exist, and the invariant is worth more.

**Trade-off.** An administrative mutation now depends on a second insert succeeding, so a
database at its connection limit fails the mutation rather than proceeding unaudited. That is
the intended behaviour and worth stating plainly.

---

## ADR-038 — A curated operational endpoint, not an Actuator dump

**Problem.** An administrator needs to know whether the system is working: are the
dependencies up, is the queue draining, what is deployed. Spring Actuator already exposes
far more than that.

**Options.** Expose Actuator broadly and rely on role checks; expose a filtered subset of
Actuator; write a curated endpoint.

**Chosen.** A hand-written `GET /api/admin/system/status`, with Actuator kept to `health` and
`info`.

**Why.** Actuator's endpoints are a superset of what an administrator needs and of what is
safe to show. `/env` and `/configprops` list every property, including the database password
and the executor token; `/heapdump` hands over process memory. Restricting them to ADMIN is
necessary but not sufficient — "an administrator could read the database password from a web
page" is a poor default, and a filter is a rule somebody can forget to update when a new
endpoint appears.

A curated endpoint inverts that: adding a field is a deliberate act. It reports whether each
dependency answered and how quickly, the queue depths, the version and the audit count — and
carries no credentials, no connection strings, no environment variables and no paths, because
none were put in.

**A finding this produced.** `management.endpoint.health.show-details` was `always`, and
`/actuator/health` is unauthenticated by necessity — health checks have no credentials. Any
caller could read the Redis version (`7.4.11`), the database engine, the container's
filesystem path and the host's free disk space. A precise dependency version is a gift to
somebody matching CVEs against a target. It is now `when-authorized` with `roles: ADMIN`: the
bare status stays public, which is all the compose probes read, and the breakdown needs a
session.

**Three kinds of health, kept distinct.** Liveness answers "should this be restarted",
readiness answers "can it serve traffic", and both must stay unauthenticated for an
orchestrator to use them. The operational view answers "what is going on" for a human, is
authenticated, and is deliberately *not* wired into any health check — a view an orchestrator
depends on stops being free to change.

**What it does not report, and why.** Worker liveness. The API server has no network route to
the worker, which sits on `internal` and `sandbox`. Reporting "unknown" would be honest but
useless; reporting "up" without checking would be worse. The queue depths are the honest
proxy — a rising pending count against a static processing count is what a stopped worker
looks like from here — and a real heartbeat means workers writing to a shared store, which
belongs with queue observability rather than here.

**Trade-off.** The endpoint must be extended by hand as the system grows, and will lag behind
what Actuator would have offered automatically. That lag is the feature.

---

## ADR-039 — Rate limiting: a Redis token bucket, and what happens when Redis is gone

**Problem.** Nothing bounded what one caller could make the system do. Login was
unprotected against guessing — and login is deliberately expensive, because BCrypt at cost
12 is roughly a quarter-second of CPU per attempt. Submissions were unbounded, and a
submission is the cheapest request in the system to make and the most expensive to serve:
it compiles and runs untrusted code in a container against every test case. One scripted
user could fill the judging queue faster than the workers drain it and delay everybody
else's verdicts, with no exploit involved at all.

**Options.** A servlet filter with per-instance counters; a library (Bucket4j, Resilience4j)
over Redis; a hand-written Redis token bucket; an API gateway or nginx `limit_req`.

**Chosen.** A token bucket evaluated as one Lua script inside Redis, applied by a Spring MVC
interceptor driven by a `@RateLimited` annotation on handler methods.

**Why not in-memory.** It is the tempting answer and it is wrong for a system that is meant
to scale horizontally: the enforced limit silently becomes *N* times the configured one, and
the documentation goes on claiming a single number. A control that is wrong in an unstated
direction is worse than one that is absent, because it is believed. There is deliberately
no local fallback either, for the same reason — a fallback would make the limit correct
until the moment it mattered.

**Why not nginx.** The limits that matter here are per *user* and per *account*, which are
application concepts; nginx can count addresses, and addresses are the one identity this
deployment cannot see clearly. It also could not refund a bucket on a successful login, or
share one allowance between the practice and contest submission endpoints.

**Why not a library.** Bucket4j over Redis would do the arithmetic competently. It would
also add a dependency and an abstraction for roughly forty lines of Lua, and the interesting
decisions here are not the arithmetic — they are which identity to key on, which policy
fails open, and how to avoid turning rejections into an attack on the audit log. None of
those are in a library.

**Why the script is one script.** A read-then-write from Java — `GET` the count, compare,
`INCR` — lets every concurrent caller read the same remaining count and all be admitted.
That bypass appears precisely under load, which is the condition a limiter exists for. The
clock is Redis's own `TIME` rather than the application's, so several instances with drifting
clocks still agree; and the key's TTL is exactly the time the bucket needs to refill, so an
expired bucket and a full bucket are the same thing and memory tracks recent activity rather
than every identity ever seen.

**Why a bucket rather than a window.** A fixed window admits twice its allowance across a
boundary. A sliding-window log stores one entry per request, which is unbounded state chosen
by the attacker. A bucket also makes `Retry-After` arithmetic instead of a guess, which is
what lets the header be honest — and a `Retry-After` that clients learn to distrust is worse
than none.

**Why the annotation rather than path patterns.** Every classic bypass is drift between a
route and a rule written about it: a trailing slash, an encoded path segment, a second route
reaching the same handler, a method that falls through. Spring has already resolved the
request to a handler by the time the interceptor runs, so whatever spelling got the caller
there, the same allowance is spent. Contest and practice submissions share a bucket for the
same reason — two allowances would have made each endpoint the other's loophole.

**Ordering.** The interceptor runs after the entire security filter chain. An unauthenticated
caller is answered 401 and an unauthorised one 403, in both cases without reaching the
limiter. Running earlier would let anonymous traffic exhaust the allowance of endpoints it
was never permitted to call, and would answer 429 where 401 was the truth.

**Login is limited in two dimensions, and locks nothing.** A per-caller cap does nothing
about credential stuffing, which is distributed by nature; a per-account cap does. But the
obvious per-account control — N failures and the account is locked — hands an attacker a
denial of service against any user whose name they can guess, which is worse than the bug it
fixes. A bucket cannot lock: it refills continuously, and a correct password empties it, so
the worst an attacker achieves is to make the owner wait. The residual weakness is real and
is written down rather than glossed: during a sustained attack the owner competes for each
regenerated token. They are delayed, not locked out.

**Fail closed for login, registration and submissions; open for reads.** The reasoning turns
on something already true of this architecture: **Redis holds every session.** For
authenticated traffic, "Redis is down" and "the API is down" are nearly the same sentence, so
closing the security-critical policies costs very little that was still working. Registration
is the one genuine availability cost — it needs no session, so failing closed really does
stop account creation during an outage — and it is taken deliberately, because every
registration writes a permanent row to the authoritative database and an outage is exactly
the moment an attacker would choose. Reads fail open: the limiter there is a comfort against
wasted database work, and an unavailable comfort must not become a second outage.

**Rejections are audited once per burst, not once per request.** The audit table is
append-only and has no retention tooling, so a row per rejection would let every rate-limited
caller grow a table nobody can prune — using the very requests the limiter refused. A Redis
marker claimed with `SET NX EX` collapses a burst into one event and one log line. Policies
keyed on caller-supplied text are excluded from auditing altogether, because their identity
space is whatever an attacker can type.

**Trade-offs.**

- Registration stops working while Redis is down. Intended, and stated.
- Anonymous per-caller limits are weaker than they appear in the shipped deployment, because
  there is no reverse proxy in front of the API and Docker's published port hides the real
  peer. Documented rather than papered over with a forwarded header this system cannot
  verify; the per-account throttle is what carries the protection meanwhile.
- Every limited request costs one Redis round trip. Measured against the alternative — a
  submission costing a container start — this is not a cost worth optimising.
- Key cardinality is bounded by TTL rather than by a hard cap, so a flood of invented
  identities creates buckets that expire rather than buckets that accumulate. There is no
  ceiling on key count, only on how long each key survives.

---

## ADR-040 — Observability: Prometheus and structured logs, no distributed tracing

**Problem.** Phase 9 shipped a system whose most important failure mode was invisible. Every
HTTP metric could be green — 202 on every submission, 200 on every read, no errors anywhere —
while the judge was completely stopped. A submission that is accepted, queued and never
judged is a stream of successful responses.

**Options.** Micrometer with a Prometheus endpoint; OpenTelemetry with a collector, spans and
an exporter in every service; a hosted APM agent; or nothing beyond the curated status view
Phase 8 already had.

**Chosen.** Micrometer metrics exposed in Prometheus format, structured logs with two
correlation identifiers, and an operational status endpoint. **No distributed tracing.**

**Why business metrics are separate from HTTP metrics.** `http_server_requests` answers "is
the API healthy" and structurally cannot answer "is work getting done" — the two go wrong
independently, and the second is the one that matters here. The diagnostic pair is
`submissions_accepted` against `judge_submissions`: when the first climbs and the second does
not, the judge has stopped, and nothing in the HTTP metrics would have said so.

**Why no OpenTelemetry.** It is the modern answer and it is the wrong size for this system.
The chain is one API call, one queue hop and two services, and it is already followable end
to end by a submission id that appears in every log line on every service and in the audit
table. Tracing would add a collector to run, a sampling policy to tune, and an exporter in
three services — to answer questions grep already answers at four components.

The cost is stated rather than hidden: **there are no span-level timings across the queue
boundary.** "Where did these 900ms go" is answered by comparing the queue-wait timer with the
judging timer instead of by reading a waterfall. That is a real loss. What would change the
decision is more services, more hops, or fan-out — at which point the identifiers stop being
enough and a trace is the only thing that reconstructs the path.

**Why no identifier may ever be a metric label.** A metric tagged with a user or a submission
creates one time series per value. The failure is not untidiness: a busy evening turns the
monitoring backend into the outage, at precisely the moment somebody needs to look at it.
Identifiers cost one log line each and are searchable; labels are a closed set or they are a
liability. A test asserts no username and no public id appears in a scrape.

**Why the dangerous Actuator endpoints are absent rather than restricted.** `env` and
`configprops` print the database password and the executor token; `heapdump` hands over
process memory. All of `/actuator/**` is ADMIN-only, and that is necessary but not
sufficient: an authorisation rule is one mistake away from being wrong, and an endpoint that
does not exist is not. They are not enabled, and a test asserts each returns 404 even for an
administrator.

**What this found immediately.** A load test produced a p95 of 10.3 seconds on submissions
and ten outright failures. `hikaricp_connections_timeout_total` named the cause in one line:
the API's connection pool was saturating, and its ten-second timeout was setting the tail
latency — the same ten seconds as the browser's own HTTP timeout. Requests were succeeding
just after the client had abandoned them, so a submission existed that the user had been told
had failed, and the natural response was to submit it again. The pool timeout is now five
seconds, so the server always answers before the client stops listening.

That bug was present in Phase 9 and nothing could see it. It is the clearest possible
argument for the phase.

**Trade-offs.** Metrics are per instance and in memory, so a restart resets every counter —
rates survive because Prometheus handles counter resets, absolute totals do not. There is no
log aggregation and no alert evaluation here; the alerts are documented with their metric and
threshold so that wiring them up is mechanical.

---

## ADR-041 — Worker liveness through Redis, in three states

**Problem.** Phase 8 said plainly that there was no worker heartbeat and explained why: the
API server sits on a different compose network from the worker and has no route to it. Worker
liveness was inferred from queue depth, which cannot tell a stopped worker from a hard problem.

**Options.** An HTTP health check from the API to the worker; a shared database table; a
heartbeat record in Redis; or a service-discovery component.

**Chosen.** A small hash per worker in Redis, rewritten every ten seconds, read by the API.

**Why Redis.** Both processes already depend on it. An HTTP check would mean opening a route
between two networks that are separate on purpose — paying in attack surface for a fact that
can be published instead. A database table would put a write every ten seconds per worker
into the store that holds every verdict, to record something that is worthless the moment it
is stale.

**Why a register the workers write, rather than a check the API performs.** A check can only
prove a process answers HTTP. A heartbeat written by the same process that does the work
cannot be green while the work is not happening — and that is exactly the failure this
exists for. `docker compose ps` will report a worker as "Up" while its consumer threads are
wedged, its Redis connection is gone, or it is looping on an error.

**Why three states.** "No heartbeat" has two very different causes and they need different
responses:

- **Healthy** — beat within 35 seconds. Three intervals, so one slow scheduler tick is not a
  fault; a status page that cries wolf is one people stop reading.
- **Stale** — the record is there and nobody has touched it. The interesting case.
- **Gone** — expired after five minutes, or removed by a worker that stopped cleanly.

The TTL is deliberately far longer than the staleness threshold. Were they equal, a worker
that died would *vanish* rather than appear stale, and "no workers are registered" is a much
weaker signal than "this worker stopped reporting four minutes ago". A clean stop removes the
record, so a planned departure is never reported as a fault.

**Two bugs this design surfaced, both invisible without it.**

*Deregistration never worked.* The first version removed the record in `@PreDestroy`. Spring
closes a context in three steps — publish `ContextClosedEvent`, stop lifecycle beans, destroy
beans — and `LettuceConnectionFactory` is a lifecycle bean, so `@PreDestroy` ran after the
Redis connection was already shut. Every deployment left a worker looking stale for five
minutes, and the only evidence was a single WARN line during shutdown. Both the drain and the
deregistration now hang off `ContextClosedEvent`, which is the last point at which a component
can still use its dependencies to say goodbye.

*The drain never completed.* The worker allows thirty seconds to finish in-flight judgements;
Docker sends SIGKILL ten seconds after SIGTERM by default. The drain was being cut short on
every stop, and nothing said so. `stop_grace_period` now covers each service's declared
window.

**Trade-off.** The record is self-reported, so a worker that lies — or one whose heartbeat
thread survives while its consumers do not — would appear healthy. Mitigated by publishing
`active` and `judged` from the same counters the judging path increments, so a worker that is
reporting but not working shows a flat `judged` next to a rising queue. A heartbeat is
evidence, not proof.

---

## ADR-042 — Three kinds of health, kept apart

**Problem.** "Is it healthy" is three different questions with three different audiences, and
answering them with one endpoint gets at least two of them wrong.

**Chosen.** Liveness, readiness and an operational state, with different contents and
different consequences.

**Liveness — `/actuator/health/liveness`, `livenessState` only.** Nothing about a dependency
belongs here. Failing liveness means *restart the process*, and restarting a healthy API
server has never repaired a database. A liveness probe that fails when PostgreSQL is slow
turns one outage into a restart loop that guarantees a second, and destroys the evidence on
the way.

**Readiness — `readinessState`, `db`, `redis`.** Failing readiness means *take this instance
out of rotation*, which is correct and, crucially, reversible. An instance that cannot reach
its database genuinely cannot serve, and Spring Boot's default readiness group would have
reported it ready.

**DEGRADED — `/api/admin/system/status`, and deliberately not a probe.** It means the API is
serving perfectly and something is still wrong: work is waiting with no healthy worker, or
the oldest queued item has been waiting more than five minutes.

Making it a probe would be the obvious mistake. An API server whose workers have died can
still serve the catalogue, the contests, the standings and every submission's history —
removing it from rotation would turn a judging outage into a total one. It is a state for a
human to read, and the one this system could not previously express.

**Why five minutes for "stuck".** The slowest legitimate judgement is a compile timeout plus
every test running to its limit, which is under two. Anything waiting longer is not waiting
for a slow problem; it is waiting for a worker that is not coming.

**Why an idle system with no workers is not degraded.** No workers and no queue is idle, and
paging somebody for an idle system is how a status field gets ignored. Degradation requires
work that is waiting.

**Trade-off.** Readiness now depends on two external systems, so a brief Redis blip can take
an instance out of rotation when it could have served some read-only traffic. That is the
right way to be wrong: the alternative is routing to an instance that will fail every
authenticated request, since sessions live in Redis.
