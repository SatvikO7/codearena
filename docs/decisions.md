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
