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
