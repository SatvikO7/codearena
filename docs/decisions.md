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
