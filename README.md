# CodeArena

An online judge platform: users solve algorithmic problems, submit code in C++, Java or
Python, and receive a verdict from a judge that compiles and runs their program inside a
disposable, network-isolated container under enforced CPU, memory and wall-clock limits.

The interesting part of this project is not the CRUD. It is everything around it —
asynchronous job processing, sandboxed execution of untrusted code, queue reliability,
idempotency and concurrency control.

> **Project status: Phase 2 of 16 complete and verified.** Accounts, roles and sessions
> work end to end. Problems, submissions and the judge itself are the phases that follow.
> This README describes what exists today; it is updated at the end of every phase.
> Nothing below is aspirational — every claim here was executed, not assumed.

---

## Architecture

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

The rule the whole design serves: **the API server never executes user-submitted code.**
`POST /api/submissions` will persist a row, push a job onto Redis and return
immediately. All execution happens in a worker process, behind a queue, inside a
container that is destroyed afterwards.

See [docs/architecture.md](docs/architecture.md) for the component breakdown and
[docs/decisions.md](docs/decisions.md) for the engineering decisions and their
trade-offs.

---

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Web, Spring Data JPA, Actuator |
| Database | PostgreSQL 16, schema managed by Flyway |
| Cache / queue | Redis 7 (append-only persistence enabled) |
| Frontend | React 19, TypeScript, Vite, React Router, Axios |
| Execution | Docker, one disposable container per submission |
| Build | Maven 3.9 (multi-module reactor, wrapper committed), npm |
| API docs | OpenAPI 3 via springdoc, Swagger UI |
| Testing | JUnit 5, AssertJ, MockMvc, Testcontainers |

---

## Repository layout

```
codearena/
├── backend/            Spring Boot API server (owns the Flyway migrations)
├── worker/             Spring Boot judge worker
├── frontend/           React + TypeScript client
├── docs/               Architecture and decision records
├── pom.xml             Maven aggregator
├── docker-compose.yml  Full local stack
└── .env.example        Configuration template
```

---

## Running the stack

### Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker + Compose | Engine 24+, Compose v2+ | the whole stack; also for integration tests |
| JDK | 21 | building or running the JVM services outside Docker |
| Node | 20+ | building or running the frontend outside Docker |

Maven is **not** a prerequisite — the committed wrapper (`./mvnw`) fetches the pinned
version itself.

### With Docker (recommended)

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD - compose refuses to start without it.

docker compose up --build
```

| Service | Where | Published to host |
|---|---|---|
| Frontend | http://localhost:5173 | yes |
| API | http://localhost:8080 | yes |
| Swagger UI | http://localhost:8080/swagger-ui.html | yes |
| API health | http://localhost:8080/actuator/health | yes |
| Worker health | `:8081/actuator/health` on the internal network | no — serves no public traffic |
| PostgreSQL | `postgres:5432` on the internal network | no |
| Redis | `redis:6379` on the internal network | no |

PostgreSQL, Redis and the worker sit on a network declared `internal: true`. Docker
cannot publish a host port from such a network, which is the intended outcome: the
datastores are unreachable from the host and from the LAN. Inspect them from inside:

```bash
docker compose exec postgres psql -U codearena -d codearena
docker compose exec redis redis-cli
docker compose ps            # health of all five services
docker compose logs -f worker
docker compose down          # stop; add -v to discard the data volumes
```

The home page shows a live connectivity panel: if it reports the API server's name,
version and profile, the whole chain (browser → API → PostgreSQL → Redis) is working.

Startup is health-gated, not timing-based: the backend waits for PostgreSQL and Redis
to pass their health checks, and the worker additionally waits for the backend, because
the backend applies the database migrations. All five health checks probe `127.0.0.1`
rather than `localhost`, because in a container `localhost` can resolve to `::1` first
and a server bound to IPv4 only then looks dead.

### Without Docker

Requires JDK 21, Node 20+, and your own PostgreSQL and Redis reachable on localhost —
the compose datastores cannot be used here, because they sit on an internal-only
network and publish no host port. Maven is **not** required: the committed Maven
Wrapper (`./mvnw`) downloads the pinned version.

The connection defaults in `application.yml` point at `localhost:5432` and
`localhost:6379`; override with `DATABASE_URL`, `DATABASE_USERNAME`,
`DATABASE_PASSWORD`, `REDIS_HOST` and `REDIS_PORT` if yours differ.

```bash
# API server on :8080
./mvnw -pl backend -am spring-boot:run

# Judge worker on :8081
./mvnw -pl worker -am spring-boot:run

# Frontend dev server on :5173
cd frontend && npm install && npm run dev
```

---

## Authentication

Accounts are protected by **server-side sessions stored in Redis**, delivered to the
browser as an HttpOnly cookie. There is no JWT, and no token is ever placed anywhere
JavaScript can read it.

That choice is deliberate. The client is a browser and there is one API server; the judge
worker never authenticates a user. A stateless token would buy nothing here and would cost
the property this system actually needs — immediate revocation. Signing out, or disabling
an abusive account, has to take effect now, not whenever a token happens to expire. Doing
that with JWT means a server-side denylist consulted on every request, which is a session
store with extra steps. The full trade-off, including what is given up, is in
[ADR-010](docs/decisions.md).

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/register` | public | Create a `USER` account. Does not sign you in. |
| `POST /api/auth/login` | public | Authenticate by username **or** email; sets the session cookie. |
| `POST /api/auth/logout` | session | Destroys the session in Redis and clears the cookie. |
| `GET /api/auth/me` | session | The current account, or `401`. |

Authorisation rules, enforced by the server on every request:

| Path | Rule |
|---|---|
| `/api/system/info`, `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**` | public |
| `/api/admin/**` | `ADMIN` only |
| `/actuator/**` (other than health) | `ADMIN` only |
| everything else under `/api/**` | any authenticated user |

The frontend's `ProtectedRoute` is a usability measure only. Bypassing it in the browser
yields an empty page and a `401` from the API — hiding a route is never what keeps it safe.

### Passwords

Length and screening, not composition rules, following NIST SP 800-63B: at least 10
characters, no requirement for a symbol-and-digit ritual that reliably produces
`Password1!`. A password may not contain the username or email local-part, and the most
frequently breached choices are screened out.

The upper bound is **72 bytes**, measured in UTF-8 rather than characters. BCrypt silently
ignores everything past 72 bytes, so a longer passphrase would be truncated without warning
and two passwords sharing a 72-byte prefix would both authenticate. Rejecting over-long
input is honest; quietly truncating it is not.

### Known gap

Login is **not yet rate limited**. BCrypt at cost 12 makes offline cracking expensive and
online guessing slow, but nothing currently stops sustained attempts against a single
account. Rate limiting arrives in Phase 9, where Redis is already the counter store. This
is stated plainly rather than filed under "future improvements", because it is a real gap
in what exists today.

---

## Configuration

No secret is committed and none is hardcoded. Every environment-specific value is read
from an environment variable with a development default; see
[.env.example](.env.example) for the full list.

| Variable | Purpose |
|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis connection |
| `SESSION_TIMEOUT` | Idle lifetime of a session (ISO-8601, default `PT2H`) |
| `SESSION_COOKIE_SECURE` | Marks session and CSRF cookies `Secure`. **Must be `true` behind HTTPS** |
| `BCRYPT_STRENGTH` | Password hashing cost factor (default `12`) |
| `WORKER_CONCURRENCY` | Submissions judged in parallel per worker process |
| `CORS_ALLOWED_ORIGINS` | Explicit browser origin allow-list |
| `VITE_API_BASE_URL` | API base URL baked into the frontend bundle |

`docker-compose.yml` uses `${VAR:?message}` for `POSTGRES_PASSWORD`, so the stack fails
loudly rather than silently starting with a default credential. There is no token signing
key to manage: sessions live in Redis, so revoking one is a delete rather than a wait for
expiry.

---

## Testing

```bash
./mvnw test      # unit tests only - no Docker daemon required
./mvnw verify    # adds the integration suite (Testcontainers: real PostgreSQL + Redis)
```

Unit tests (`*Test`) run under Surefire and have no external dependencies. Integration
tests (`*IT`) run under Failsafe against real containers — never an in-memory database,
because the system depends on real PostgreSQL behaviour. See ADR-003.

Current suite: 60 tests — 29 backend unit (error contract, password policy, role
mapping, registration including the concurrent-insert race), 3 worker unit
(configuration validation), and 28 integration against a real PostgreSQL and Redis.

The integration tests drive real HTTP with a genuine cookie jar and CSRF handling rather
than Spring's `MockMvc` CSRF shortcut. That shortcut injects a valid token directly, so
the suite would still pass if the server never issued the CSRF cookie at all — which is
precisely the bug most likely to reach production.

Frontend:

```bash
cd frontend
npm run lint
npm run build   # type-checks with tsc, then bundles
```

---

## Security posture

In place and verified as of Phase 1:

- **Network isolation.** PostgreSQL, Redis and the worker sit on a Docker network
  declared `internal: true`. Containers on it get no default route, so an outbound
  connection fails with `Network unreachable` — confirmed against a raw IP, not just a
  hostname. No host port is published for either datastore.
- **No secrets in the repository.** `.env` is git-ignored; only `.env.example` is
  committed, with placeholders. Compose refuses to start if `POSTGRES_PASSWORD` is unset
  rather than falling back to a default credential.
- **Passwords are BCrypt hashes at cost 12**, stored through a `DelegatingPasswordEncoder`
  so the algorithm can be migrated later without invalidating existing hashes. No endpoint
  can return a hash: responses are built from a closed record with no password field.
- **Sessions, not tokens.** The session id is an HttpOnly, SameSite=Lax cookie, so
  JavaScript cannot read it and an XSS bug does not hand over the account. Logging out
  destroys the session in Redis, verified by replaying the captured cookie and getting 401.
- **CSRF tokens** on every state-changing request, since the browser attaches the session
  cookie automatically.
- **Login does not leak which accounts exist**: a wrong password and an unknown username
  return an identical status and message, asserted by a test.
- **Containers run as a non-root user** (`uid=100 codearena`, confirmed at runtime),
  from a JRE-only runtime image carrying neither the JDK nor the build cache.
- **Errors leak nothing.** Stack traces are logged server-side and never serialised into
  a response; a unit test asserts the thrown exception's message is absent from the body.
- **CORS is an explicit origin allow-list**, never a wildcard.
- **Browser hardening headers** — `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy` — asserted present on responses, not merely configured (see ADR-009
  for why that distinction mattered).

Designed for, but **not yet implemented**:

- **The API server never executes user code.** The topology enforcing this — a separate
  worker, behind a queue — exists; the sandbox that runs submissions in a disposable,
  resource-limited container arrives in Phase 6.

Authentication and authorisation arrive in Phase 2, the execution sandbox and its CPU,
memory and timeout limits in Phase 6, and rate limiting in Phase 9. Each is documented
as it lands, never before.

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Repository, build, Docker stack, service skeletons | **Complete** |
| 2 | Users, roles, sessions, registration and login | **Complete** |
| 3 | Problems, tags, test cases, search and pagination | Next |
| 4 | Submission API and state machine | Planned |
| 5 | Redis queue, worker loop, retries, idempotency | Planned |
| 6 | Docker execution engine, resource limits, isolation | Planned |
| 7 | Judging, output comparison, verdicts | Planned |
| 8 | Real-time submission status | Planned |
| 9 | Caching, cache invalidation, rate limiting | Planned |
| 10 | Contests, scoring, leaderboards | Planned |
| 11 | Full frontend | Planned |
| 12–16 | Hardening, testing, CI/CD, docs, deployment | Planned |

---

## Licence

[MIT](LICENSE)
