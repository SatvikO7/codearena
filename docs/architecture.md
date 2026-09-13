# Architecture

> Status: this document tracks what is **built**, and is extended at the end of every
> phase. Anything not yet implemented is listed under "Planned" rather than described
> as if it exists.

## Components

CodeArena is deliberately split into four runtime processes rather than one, because
they have incompatible risk profiles and scaling characteristics.

```mermaid
flowchart TD
    Browser["Browser<br/>React + TypeScript"]
    API["API server<br/>Spring Boot"]
    Worker["Judge worker<br/>Spring Boot"]
    PG[("PostgreSQL")]
    Redis[("Redis")]
    Sandbox["Execution sandbox<br/>disposable container"]

    Browser -->|HTTPS / REST| API
    API --> PG
    API -->|enqueue job| Redis
    Redis -->|dequeue job| Worker
    Worker --> PG
    Worker -->|create, run, destroy| Sandbox

    subgraph private["Private network - no internet access"]
        PG
        Redis
        Worker
        Sandbox
    end
```

| Component | Responsibility | Why it is separate |
|---|---|---|
| **Frontend** | Rendering and client-side routing | Static assets; scales and deploys independently of the API |
| **API server** | REST API, authentication, persistence, enqueuing jobs | Must stay responsive; **never** executes user code |
| **Judge worker** | Consumes jobs, drives sandboxed execution, records results | CPU-heavy and untrusted-adjacent; scaled by queue depth, not by request rate |
| **PostgreSQL** | System of record | Relational, transactional data |
| **Redis** | Queue, cache, rate-limit counters | Low-latency, non-authoritative state |

The central architectural rule: **the API server never runs user-submitted code.** A
submission request writes a row, pushes a job and returns. Everything expensive and
everything dangerous happens in a worker, behind a queue.

## Networking

`docker-compose.yml` defines two networks:

- `edge` — the frontend and the API server. Ports published to the host come from here.
- `internal` — PostgreSQL, Redis and the worker, declared `internal: true` so Docker
  attaches no gateway. Containers on it cannot reach the internet and cannot be reached
  from outside the compose project.

The worker sits only on `internal` and publishes no ports.

PostgreSQL and Redis publish **no** host ports at all. This is a property of the
network, not just a policy: Docker cannot set up port publishing for a container whose
only network is `internal: true`, because there is no NAT for it. The datastores are
therefore unreachable from the host and from the LAN, and are inspected from inside the
network (`docker compose exec postgres psql …`). Verified by observation: a container on
`internal` has no default route at all — only its own subnet — so an outbound connection
to a raw IP fails with `Network unreachable`, not merely a DNS failure.

## Schema ownership

Flyway migrations live in the backend and run **only** in the backend
(`backend/src/main/resources/db/migration`). The worker has no migration tooling on its
classpath at all. This makes it structurally impossible for several worker replicas to
race each other to migrate the database. The worker runs with
`hibernate.ddl-auto: validate` and waits for the backend's health check before starting.

## Authentication

Sessions, not tokens. The browser holds an HttpOnly cookie carrying a session id; the
session itself lives in Redis under `codearena:session:*`. Spring Security's context is
persisted into that session, so an authenticated request is resolved by a Redis lookup
rather than by verifying a signature.

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as API server
    participant R as Redis
    participant P as PostgreSQL

    B->>A: POST /api/auth/login
    A->>P: load user by username or email
    A->>A: BCrypt verify (cost 12)
    A->>A: discard any pre-login session
    A->>R: store new session + security context
    A-->>B: 200 + Set-Cookie CODEARENA_SESSION (HttpOnly)

    B->>A: GET /api/auth/me (cookie attached automatically)
    A->>R: load session
    A->>P: re-read account
    A-->>B: 200 profile

    B->>A: POST /api/auth/logout
    A->>R: delete session
    A-->>B: 204 + cookie cleared
```

Two consequences worth naming:

- **Revocation is immediate.** Deleting the session in Redis ends access on the next
  request. `GET /api/auth/me` also re-reads the account rather than trusting the session
  copy, so a role change or a disabled flag takes effect at once instead of lingering
  until the session expires.
- **Redis is now on the authentication path.** If Redis is unavailable nobody can sign in
  and existing sessions stop resolving. This is accepted, and why, is recorded in ADR-010.

Sessions are why the API server stays horizontally scalable: any instance can serve any
request because none of them hold session state locally.

## Problem catalogue

Problems are an aggregate: a `Problem` owns its examples, its test cases and its tags, and
they are replaced wholesale on edit and deleted with it. Modelling it that way — rather
than as three independently-managed repositories — means an edit cannot leave a problem
holding examples that belonged to a previous version of itself.

The security boundary of this phase is the split between **examples** and **test cases**.
They are separate tables mapped to separate response types, so the user-facing
`ProblemDetailResponse` has no field capable of carrying an answer key. A single table with
a `visibility` flag would have worked too, but then every user-facing query would need a
predicate, and one forgotten predicate ships the answers.

Status is not a writable property. It changes only through `publish`, `unpublish`,
`archive` and `restore` on the entity, each of which consults `ProblemStatus` for whether
the move is legal, and `publish` additionally refuses an incomplete problem. Since
`ProblemRequest` has no status field, there is no payload a client can send that reaches
the catalogue without passing those checks.

```mermaid
flowchart LR
    Admin["Administrator"] -->|"POST /api/admin/problems"| AdminApi["AdminProblemController<br/>ADMIN required twice"]
    AdminApi --> AdminSvc["ProblemAdminService"]
    AdminSvc --> Aggregate["Problem aggregate<br/>examples · test cases · tags"]

    User["Authenticated user"] -->|"GET /api/problems"| PublicApi["ProblemController"]
    PublicApi --> Catalogue["ProblemCatalogService<br/>status fixed to PUBLISHED"]
    Catalogue --> Aggregate

    Aggregate --> DB[("PostgreSQL")]

    Catalogue -.->|"never reads"| TestCases["problem_test_cases"]
    AdminSvc --> TestCases
```

## The judging pipeline

The API server and the judge worker are separate processes for one reason: the API must
never execute user code. `POST /api/submissions` writes a row, asks for it to be queued, and
returns. Everything expensive and everything dangerous happens elsewhere.

```mermaid
flowchart TD
    API["API server"] -->|"INSERT (QUEUED)"| PG[("PostgreSQL")]
    API -->|"after commit"| Redis[("Redis: pending")]
    Sweeper["Recovery sweeper<br/>(in the API)"] -->|"republish / reclaim"| Redis
    Sweeper --> PG
    Redis -->|"BLMOVE"| Worker["Judge worker"]
    Worker -->|"atomic claim"| PG
    Worker --> Exec["ExecutionService"]
    Exec --> Sandbox["Sandbox container<br/>network none · caps dropped<br/>cpu · memory · pids capped"]
    Worker -->|"verdict"| PG
    Worker -->|"PUBLISH (notification)"| Events[("Redis: pub/sub")]
    Events --> API
    API -->|"re-read, then SSE"| Browser["Browser"]
```

Three properties hold this together:

- **The submission row is the outbox.** Creating a submission and recording that it needs
  queueing are one INSERT, so there is no state in which one exists without the other.
- **The claim is atomic.** `UPDATE … WHERE status = 'QUEUED'` lets exactly one worker win,
  so duplicate delivery is a no-op rather than a double execution.
- **Terminal is terminal.** Nothing leaves a verdict, so a straggling worker cannot
  overwrite a newer result.

The worker uses plain JDBC rather than the API's JPA entities (ADR-022), and the two
services share only `Language`, `SubmissionStatus` and the event record — the rules both
must agree on.

### Getting the verdict to the browser

A fourth property governs the return path: **Redis carries a notification, never the state.**
The worker publishes three fields — submission, status, timestamp — to one channel. Every
API instance subscribes, and an instance that has a browser watching that submission
**re-reads the row from PostgreSQL** and streams the result of that read over SSE. The
published payload is never forwarded.

That is what lets the design work behind a load balancer without sticky sessions, and what
keeps Redis out of the trust path: the notification can be duplicated, reordered or lost
entirely without a browser ever being shown a status the database does not hold.

Delivery is **at-least-once and best-effort, not exactly-once**. It is made safe by a
snapshot on connect, a convergent client reducer that ignores anything not strictly newer,
and a bounded fallback poll armed only when the stream fails. See
[submission-lifecycle.md](submission-lifecycle.md) and ADR-023/ADR-024.

## Current state (Phase 5 — complete and verified)

Implemented:

- Maven multi-module build (`codearena-parent` → `backend`, `worker`), Java 21
- API server: `GET /api/system/info`, actuator health with database and Redis
  indicators, OpenAPI/Swagger UI, global exception handler, configurable CORS
- Worker: configuration binding with validation, fail-fast startup connectivity check
  against PostgreSQL and Redis, actuator health
- Frontend: Vite + React + TypeScript, router, isolated service layer, API
  connectivity panel with loading / connected / error states
- Full docker compose stack with health-gated startup ordering
- Flyway baseline migration

Added in Phase 2:

- `users` table (`V2`) with UUID public ids, functional unique indexes for
  case-insensitive identity, a role check constraint and audited timestamps
- Registration, login (by username or email), logout and current-user endpoints
- Spring Security with session authentication, CSRF, role-based rules and JSON error
  responses for 401 and 403
- Frontend auth context, protected routes, and login/register/profile pages

Added in Phase 4:

- `common` module: `Language` and `SubmissionStatus`, shared by both deployables
- `submissions` (`V4`), doubling as the transactional outbox
- Submission API, Redis queue, publication-after-commit and a recovery sweeper
- Judge worker: atomic claim, Docker execution, verdict mapping, bounded retries
- Frontend solve page with a code editor and verdict display

Added in Phase 3:

- `problems`, `problem_examples`, `problem_test_cases` and `problem_tags` (`V3`), with
  a UUID public id, a unique slug, check constraints mirroring every enum, and composite
  indexes over the catalogue's actual access path
- Admin authoring and lifecycle endpoints; public catalogue and detail endpoints
- Database-level pagination, filtering and search with a closed sort vocabulary
- Frontend catalogue, problem detail, and the admin authoring and management screens

Added in Phase 5:

- `submission_test_results` (`V5`): per-test outcome, runtime and a denormalised `hidden`
  marker, with **no column capable of holding test data**; plus a composite index over the
  history page's actual access path (`user_id`, `status`, `created_at DESC`)
- Submission history with server-side filtering by problem, verdict and language, paginated,
  backed by a constructor projection so a listing never reads `source_code` at all
- `GET /api/submissions/{id}/events`: Server-Sent Events, authorised identically to the REST
  endpoint before the first byte, snapshot first, stream closed at the verdict
- Redis Pub/Sub notifications from the worker; every API instance subscribes and **re-reads
  PostgreSQL** rather than forwarding the payload, so the design survives a load balancer
  without sticky sessions and keeps Redis out of the trust path
- A convergent client reducer plus a bounded fallback poll, replacing Phase 4's
  unconditional polling
- Frontend test suite (Vitest + Testing Library): 25 tests, specifying the convergence rules
  and the colour-independence of the verdict display

Verified by execution, not assumed: `./mvnw clean verify` passes (172 unit, 143 integration
against real PostgreSQL, Redis and **real Docker containers**), plus 25 frontend tests; all
five compose services reach `healthy` with zero restarts; Flyway records `V1` through `V5` as
applied; a draft answers 404 rather than 403 to a normal user; the raw catalogue response is
asserted to contain neither a hidden test case's input nor its expected output; a `status`
field added to an update payload is ignored; and every admin mutation returns 403 to a USER
and 401 to an anonymous caller.

Phase 5 adds to that: the SSE endpoint answers 404 — not 403, and not a JSON envelope that
`Accept: text/event-stream` cannot negotiate — for a submission belonging to someone else;
a stream opened on an already-terminal submission sends one snapshot and closes rather than
hanging; the history listing is asserted to carry no source code for any row; and the
convergence reducer is proven against duplicate, reordered, lost and unparseable events.

**Not implemented, and not claimed:** there is no submission rate limiting; the SSE
connection cap is global rather than per user; memory is enforced but not measured, so no
endpoint reports it; there is no dead-letter queue for inspection; and the sandbox is not
production-hardened — the worker holds the Docker socket and containers share the host
kernel. Event delivery is at-least-once and best-effort, not exactly-once, and real-time
delivery is not guaranteed. See docs/security.md.

Planned, in phase order: worker scaling and queue observability (6), caching (7), profiles
and statistics (8), rate limiting (9), contests and leaderboards (10), full frontend (11),
sandbox hardening (12).

## Related documents

- [decisions.md](decisions.md) — engineering decisions and their trade-offs
