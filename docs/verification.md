# Verification

One command that tells you whether CodeArena is healthy.

```bash
./scripts/verify-all.sh
```

It starts what it needs, waits for it properly, verifies the whole system against
a real stack, and puts things back. A final `PASS` means every mandatory stage
actually passed; anything else exits non-zero.

---

## Running it

| Command | Does |
|---|---|
| `./scripts/verify-all.sh` | Everything. **~40 minutes** |
| `./scripts/verify-all.sh --quick` | Skips the two fault-injection suites. **~20 minutes** |
| `./scripts/verify-all.sh --keep-up` | Leaves the stack running afterwards |
| `./scripts/verify-all.sh --help` | Options |

### Prerequisites

| Needed | Why |
|---|---|
| **Docker** and **Docker Compose v2**, daemon running | Six services, and the judge itself uses Docker |
| **Java 21+** | Checked via `./mvnw -v`. Maven itself comes from the wrapper |
| **Node and npm** | Frontend typecheck, lint, tests and build |
| **bash**, `curl`, `git`, `python`, `awk` | The pipeline and the E2E suites |
| **`.env`** with `POSTGRES_PASSWORD` and `EXECUTOR_TOKEN` | Compose refuses to start without them |

```bash
cp .env.example .env
openssl rand -hex 32     # EXECUTOR_TOKEN
```

### Supported environment

**One portable bash implementation**, deliberately. It is verified on Windows via
**Git Bash** (the project's own development environment) and uses nothing outside
POSIX plus `docker`, `curl` and `python`. A second PowerShell implementation would
be a second thing to keep correct, and would drift.

On Windows run it from Git Bash, not `cmd` or PowerShell:

```bash
./scripts/verify-all.sh
```

---

## The stages

Fourteen, in dependency order. A failure in one skips the rest — there is no value
in end-to-end results when the build did not compile.

| # | Stage | Verifies |
|---|---|---|
| 1 | **Repository and prerequisites** | Required commands, Docker reachable, Java 21+, `.env` present, `docker compose config` valid, `.env` not tracked by git |
| 2 | **Backend build and tests** | `./mvnw clean verify` — unit tests, Testcontainers integration tests against real PostgreSQL and Redis, Docker-backed judge and sandbox suites. Reports totals |
| 3 | **Frontend checks** | `npm ci` if needed, `tsc --noEmit`, `oxlint`, Vitest, production build |
| 4 | **Docker stack** | Builds images and starts all six services |
| 5 | **Service health** | Polls each service's healthcheck until healthy, with a bounded timeout |
| 6 | **Database migrations** | Every migration in `db/migration/` is applied, in order, with none failed |
| 7 | **Redis** | Answers `PING`; append-only persistence is on |
| 8 | **Core end-to-end flow** | `scripts/e2e/core-flow.sh` — the full user journey and the main rejections |
| 9 | **Audit logging** | `scripts/e2e/audit.sh` |
| 10 | **Ratings and rankings** | `scripts/e2e/ratings.sh` — two real contests, judged for real, then rated; includes the ten-way concurrent finalisation |
| 11 | **Rate limiting** | `scripts/e2e/rate-limiting.sh` *(skipped by `--quick`)* |
| 12 | **Observability and fault recovery** | `scripts/e2e/observability.sh` *(skipped by `--quick`)* |
| 13 | **Sandbox and repository hygiene** | No stray sandbox containers or volumes; no `.env` tracked |
| 14 | **Final repository state** | Nothing was modified *by* the run. Compared against the working tree as it was at the start, so work in progress is not reported as a failure |

### Why stage 10 runs in `--quick` too

It restarts nothing and injects no faults, so it is not one of the destructive
suites `--quick` exists to skip. Its one slow part is waiting for the background
finalisation sweeper, and that is polled rather than slept through — a fast sweep
is not waited out, and a slow one is not missed.

### What stage 14 compares against

The working tree as it was when the run **started**, not the last commit. The
question it answers is "did verification change anything", and a developer running
this with work in progress must not be told their own edits are a failure. What it
catches is a test or a build writing into the working tree.

### What stage 6 does *not* do

It does not compare against a hard-coded list of migrations. It reads the actual
filenames in `backend/src/main/resources/db/migration/` and compares them to
`flyway_schema_history`, so adding V9 cannot leave this stage quietly checking
yesterday's schema.

---

## The end-to-end suites

Each is independently runnable against a running stack and exits non-zero on any
failure. They share `scripts/e2e/lib.sh` — one HTTP client, one cookie jar
implementation, one pattern matcher.

| Suite | Checks | Time | Restarts services? |
|---|---|---|---|
| `core-flow.sh` | 63 | ~2 min | No |
| `audit.sh` | 42 | ~2 min | No |
| `rate-limiting.sh` | 63 | ~8 min | **Yes** — stops and starts Redis |
| `observability.sh` | 77 | ~10 min | **Yes** — worker, executor, Redis, API |

```bash
docker compose up -d          # they expect a running stack
bash scripts/e2e/core-flow.sh
```

### `core-flow.sh` — the one to read first

The complete journey, using the **real Docker sandbox**; the judge is never mocked
for the happy path:

register → login → session → author a problem → confirm a draft is invisible →
publish → browse → submit → **judged `ACCEPTED`** → per-test results → a wrong
answer judged `WRONG_ANSWER` → history → the event stream → create a contest →
derived `UPCOMING` → register → derived `LIVE` → contest submission judged →
standings show 100 → audit events exist → admin status.

Then the rejections: anonymous 401s, USER 403s on the admin surface, a duplicate
registration, another user's submission returning **404 rather than 403**, an
unregistered contest submission, over-posted `status`/`userId` fields ignored, a
submission after `endAt` refused, and a 429 with a correct `Retry-After`.

### Why the fault-injection suites are separate

`rate-limiting.sh` stops Redis and waits for a token bucket to refill.
`observability.sh` restarts the worker, the executor, Redis and the API while work
is queued, and asserts nothing is lost. Both are slow *because* they are doing the
thing that matters. `--quick` skips them; the full run does not.

> **Do not run the full suite against a stack somebody is using.** It will restart
> their services.

---

## Behaviour

### Services

The pipeline starts what it needs. You do **not** start PostgreSQL, Redis, the
backend, the worker, the executor or the frontend yourself.

**Afterwards:**

| Situation | Result |
|---|---|
| The stack was already running when you started | Left running — it was not the pipeline's to stop |
| The pipeline started it | Stopped (`docker compose stop`) |
| `--keep-up` | Left running |

**It never runs `docker compose down -v`.** The developer's database and Redis
volumes are theirs. A verification script that wipes them to get a clean run is a
script people stop trusting, and one bad flag away from destroying real work.

### Test data

Every run generates a random suffix (`alice47213`, `verify-47213`, …), so runs
neither collide with each other nor with real data. Accounts are obviously
synthetic, use `@e2e.invalid` addresses, and share one recognisable password
constant — which is what lets the log and metric scans assert that string never
appears anywhere.

Data is **not** deleted afterwards. Removing rows from an append-only audit log is
impossible by design, and deleting submissions would exercise a path that does not
otherwise exist. The suffix is what keeps repeated runs clean.

### Idempotence

Running it twice in a row is safe. It tolerates an already-running stack, existing
volumes, data left by a previous run, and a previous run that failed part way. It
assumes nothing about the last run having succeeded.

### Timeouts

Every wait is bounded and polls a real condition — there are no fixed sleeps that
assume success.

| Wait | Bound |
|---|---|
| Each service becoming healthy | 240s |
| Backend readiness after a restart | 120s |
| A submission reaching a verdict | 180s |
| Redis answering after a restart | 60s |

A service that never becomes healthy fails the stage with diagnostics rather than
hanging.

### Logs and diagnostics

Full output goes to `.verify-logs/` (gitignored). The terminal shows a bounded
summary; on failure the pipeline prints the failing assertions, `docker compose
ps`, each service's health, and the last few dozen error lines from the container
logs.

Diagnostics are filtered, not dumped: no passwords, tokens, cookies, source code
or hidden test data.

---

## Reading the output

```
[8/14] Core end-to-end flow
      PASS  PASS 63   FAIL 0
```

and at the end:

```
  Repository             PASS
  Backend                PASS
  ...
  Duration               38m 12s
  Logs                   .verify-logs/

========================================
CODEARENA VERIFICATION: PASS
========================================
```

A failing stage names the suite and the assertion:

```
[11/14] Rate limiting
      FAIL  rate-limiting suite failed
        FAIL  submissions refused after N attempts
           -> 40 submissions all accepted
```

Then read the matching file in `.verify-logs/`.

---

## CI suitability

Structurally ready, with real caveats:

**Ready:** one entrypoint, a meaningful exit code, bounded timeouts everywhere, no
interactive prompts, logs to a known directory, and no dependence on a previous
run.

**Caveats:**

- **It needs a Docker daemon it can create sibling containers with.** The judge
  runs real containers, so Docker-in-Docker or a mounted socket is required.
- **~40 minutes.** Use `--quick` for per-commit runs and the full suite nightly.
- **Resource-hungry** — six services plus sandbox containers. Give it 8 GB.
- **Not parallel-safe against a shared daemon.** Two runs on one host will compete
  for Docker; the Docker-backed judge tests fail with `SYSTEM_ERROR` under
  contention.
- `.env` must be provided by the CI environment, with real values.

There is no CI configuration in the repository yet — that is Phases 14–16.

---

## Troubleshooting the pipeline

| Symptom | Cause |
|---|---|
| Stage 1: `the Docker daemon is not reachable` | Docker Desktop is not running |
| Stage 1: `.env is missing` | `cp .env.example .env` and set the two required values |
| Stage 1: `Java 21 or newer is required` | `./mvnw -v` reports the JDK in use |
| Stage 2 fails with `SYSTEM_ERROR` in judge tests | Docker contention. Stop the compose worker and executor and re-run — the test suite and a running stack compete for the daemon |
| Stage 5: a service never becomes healthy | Read the printed diagnostics, then `docker compose logs <service>` |
| Stage 6: migrations mismatch | A migration failed. `SELECT * FROM flyway_schema_history WHERE success = false` |
| Stage 8–11: 429s everywhere | Rate-limit buckets spent by an earlier run. The suites clear them; if you are running one by hand, clear them first |
| Stage 14: tracked files modified | Something wrote into the working tree during verification. That is a bug worth chasing |

Related: [operations.md](operations.md) for the runbook,
[observability.md](observability.md) for what the metrics mean.
