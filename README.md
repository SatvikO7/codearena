# CodeArena

An online judge platform: users solve algorithmic problems, submit code in C++, Java or
Python, and receive a verdict from a judge that compiles and runs their program inside a
disposable, network-isolated container under enforced CPU, memory and wall-clock limits.

The interesting part of this project is not the CRUD. It is everything around it —
asynchronous job processing, sandboxed execution of untrusted code, queue reliability,
idempotency and concurrency control.

> **Project status: Phase 1 of 16 — code complete, verification partial.**
> Authentication, problems, submissions and the judge itself are the phases that follow.
> This README describes what exists today; it is updated at the end of every phase.
> Nothing below is aspirational.
>
> Verified on the development machine: both Maven modules compile under Java 21, the
> unit suite passes, and the frontend type-checks, builds and lints clean.
> **Not yet verified: `docker compose up` and the Testcontainers integration suite**,
> because the machine used for Phase 1 has Docker installed but pending a reboot. Both
> are run and this note removed before Phase 1 is declared done.

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

### With Docker (recommended)

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD and JWT_SECRET - compose refuses to start without them.
# Generate a secret with:  openssl rand -base64 48

docker compose up --build
```

| Service | URL |
|---|---|
| Frontend | http://localhost:5173 |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| API health | http://localhost:8080/actuator/health |

The home page shows a live connectivity panel: if it reports the API server's name,
version and profile, the whole chain (browser → API → PostgreSQL → Redis) is working.

Startup is health-gated, not timing-based: the backend waits for PostgreSQL and Redis
to pass their health checks, and the worker additionally waits for the backend, because
the backend applies the database migrations.

### Without Docker

Requires JDK 21, Node 20+, and PostgreSQL and Redis reachable on localhost. Maven is
**not** required: the committed Maven Wrapper (`./mvnw`) downloads the pinned version.

```bash
# API server on :8080
./mvnw -pl backend -am spring-boot:run

# Judge worker on :8081
./mvnw -pl worker -am spring-boot:run

# Frontend dev server on :5173
cd frontend && npm install && npm run dev
```

---

## Configuration

No secret is committed and none is hardcoded. Every environment-specific value is read
from an environment variable with a development default; see
[.env.example](.env.example) for the full list.

| Variable | Purpose |
|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis connection |
| `JWT_SECRET` | Token signing key (used from Phase 2) |
| `WORKER_CONCURRENCY` | Submissions judged in parallel per worker process |
| `CORS_ALLOWED_ORIGINS` | Explicit browser origin allow-list |
| `VITE_API_BASE_URL` | API base URL baked into the frontend bundle |

`docker-compose.yml` uses `${VAR:?message}` for `POSTGRES_PASSWORD` and `JWT_SECRET`, so
the stack fails loudly rather than silently starting with a default credential.

---

## Testing

```bash
./mvnw test      # unit tests only - no Docker daemon required
./mvnw verify    # adds the integration suite (Testcontainers: real PostgreSQL + Redis)
```

Unit tests (`*Test`) run under Surefire and have no external dependencies. Integration
tests (`*IT`) run under Failsafe against real containers — never an in-memory database,
because the system depends on real PostgreSQL behaviour. See ADR-003.

Frontend:

```bash
cd frontend
npm run lint
npm run build   # type-checks with tsc, then bundles
```

---

## Security posture

Established in Phase 1, extended in every phase that follows:

- **Untrusted code is never run by the API server.** Execution belongs to the worker,
  in a container that is destroyed afterwards.
- **Network isolation.** PostgreSQL, Redis and the worker sit on a Docker network
  declared `internal: true`, with no gateway to the internet. Database and cache ports
  are published to `127.0.0.1` only.
- **No secrets in the repository.** `.env` is git-ignored; only `.env.example` is
  committed, and it contains placeholders.
- **Containers run as a non-root user**, from a JRE-only runtime image that carries
  neither the JDK nor the build cache.
- **Errors leak nothing.** Stack traces are logged server-side and never serialised into
  a response; a test asserts this.
- **CORS is an explicit origin allow-list**, never a wildcard.

Authentication, authorisation, rate limiting and the sandbox's own resource limits
arrive in Phases 2, 6 and 9 and will be documented as they land.

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Repository, build, Docker stack, service skeletons | Code complete, Docker verification pending |
| 2 | Users, roles, JWT access and refresh tokens | Next |
| 3 | Problems, tags, test cases, search and pagination | Planned |
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
