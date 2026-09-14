# Production readiness

A checklist across nine areas. Each line is **PASS** or **KNOWN LIMITATION**, and the rule
used throughout is the one that makes a checklist worth reading:

> **Nothing is marked PASS because it was not tested.** Where a claim rests on a test, the
> test is named. Where something is absent, it says so and says what it would take.

Assessed at the end of Phase 10 of 16. This is a portfolio-grade system that a small
deployment could run; it is not a hardened multi-tenant service, and several lines below say
exactly where that line falls.

---

## Security

| Item | Status | Evidence |
|---|---|---|
| Passwords stored with BCrypt (cost 12), never logged | **PASS** | `PasswordPolicyTest`, `AuthenticationIT` |
| Sessions server-side in Redis, HttpOnly, SameSite=Lax | **PASS** | `AuthenticationIT` |
| CSRF on every mutation, token in a JS-readable cookie | **PASS** | `AuthenticationIT` — tested over real HTTP, not with a mock shortcut |
| Authorisation enforced server-side on every request | **PASS** | `ProblemApiIT`, `ContestApiIT`, `AuditApiIT` |
| A user cannot read another's source by manipulating ids | **PASS** | `SubmissionApiIT` — 404, never 403, so it is not an oracle |
| Hidden test inputs and outputs never leave the server | **PASS** | `ProblemApiIT`, `JudgeVerdictIT` |
| Login is not an account-enumeration oracle | **PASS** | `AuthenticationIT`, `RateLimitIT` — identical answers for real and imaginary accounts |
| Untrusted code runs network-isolated, unprivileged, seccomp-filtered | **PASS** | `SandboxSecurityIT`, `SandboxIsolationIT` |
| Only the executor holds the Docker socket | **PASS** | ADR-028; the worker has no Docker client installed |
| Audit log append-only, enforced by a database trigger | **PASS** | `AuditApiIT` — `UPDATE`, `DELETE` and a blanket `DELETE` all refused |
| Rate limiting atomic and distributed | **PASS** | `RateLimitIT` — 20 concurrent against a capacity of 3 admits exactly 3 |
| Rate limiting never replaces authorisation | **PASS** | `RateLimitIT` — 401 and 403 still decide first |
| Metrics carry no secret, no credential, no path | **PASS** | `ObservabilityIT`, Phase 10 E2E |
| No identifier in any metric label | **PASS** | `ObservabilityIT` |
| Dangerous Actuator endpoints not enabled at all | **PASS** | `ObservabilityIT` — nine endpoints, 404 for an administrator |
| No secret in any image or committed file | **PASS** | `.env` excluded at every depth; verified by inspection |
| No secret in any log | **PASS** | Phase 8, 9 and 10 E2E all scan the logs of every service |
| **No trustworthy client IP** | **KNOWN LIMITATION** | No reverse proxy in front of the API; anonymous callers can share one identity. The per-account login throttle carries the protection. [rate-limiting.md](rate-limiting.md) |
| **Sandbox isolation is container-grade, not VM-grade** | **KNOWN LIMITATION** | A kernel exploit escapes it. gVisor or a rootless daemon is Phase 12 |
| **No tamper-evidence on the audit log** | **KNOWN LIMITATION** | No hash chaining, no signing. A database superuser could drop the trigger |
| **No user administration** | **KNOWN LIMITATION** | No endpoint changes a role or disables an account, so nothing of that kind exists to audit |

---

## Reliability

| Item | Status | Evidence |
|---|---|---|
| Submission durability: the row is the outbox | **PASS** | ADR-018; `SubmissionApiIT` |
| At-least-once delivery, made safe by an idempotent claim | **PASS** | `SubmissionApiIT`; a redelivered job cannot re-claim a settled submission |
| Lost or stranded work is recovered by a sweeper | **PASS** | Phase 10 E2E — work survives worker, executor, Redis and API restarts |
| Retries bounded by `attempts`, then SYSTEM_ERROR | **PASS** | `JudgeVerdictIT` |
| Terminal user-code failures are never retried | **PASS** | `JudgeFailureClassificationTest` — a wrong answer is a result, not a fault |
| Worker draining: stop claiming, finish in-flight, deregister | **PASS** | Phase 10 E2E — verified after fixing two bugs, below |
| Graceful shutdown windows actually fit the stop grace | **PASS** | `stop_grace_period` set per service; verified by a clean deregistration |
| Redis restart loses no queued work | **PASS** | Phase 10 E2E — append-only file; queue intact |
| API restart loses no queued work | **PASS** | Phase 10 E2E — work queued before the restart is still judged |
| Abuse controls have declared failure semantics | **PASS** | ADR-039; `RateLimitRedisOutageIT` breaks the connection for real |
| **Single instance of everything** | **KNOWN LIMITATION** | No HA, no failover. Each service is one container |
| **Queue length is unbounded** | **KNOWN LIMITATION** | Per-user limits make a flood expensive; there is no system-wide cap |

### Two reliability bugs this phase found

Both were present before Phase 10 and both were invisible:

1. **Worker deregistration never worked.** `@PreDestroy` runs after Spring stops
   `LettuceConnectionFactory`, so every attempt to remove the heartbeat failed with
   `LettuceConnectionFactory has been STOPPED`. Every deployment left a worker that appeared
   stale for five minutes.
2. **The worker's 30-second drain never completed.** Docker's default stop grace is 10
   seconds, so SIGKILL always arrived first.

---

## Performance

| Item | Status | Evidence |
|---|---|---|
| Reproducible load test | **PASS** | `scripts/loadtest.py`; results in [operations.md](operations.md#capacity-measured) |
| p95 under 100 ms on every scenario at 15 concurrent clients | **PASS** | Measured: 46–82 ms |
| Zero failures under load | **PASS** | Measured, after fixing the pool timeout |
| Query plans checked, indexes added from measurement | **PASS** | V8 — two partial indexes, cost 18,679 → 8.4 and 16,596 → 56.5 at one million rows |
| No N+1 in the hot paths | **PASS** | Standings is one aggregate query; submission history is one indexed page |
| Connection pool sized and documented | **PASS** | The measured binding constraint; `DB_POOL_SIZE` |
| **Single-machine measurement only** | **KNOWN LIMITATION** | One host, Docker Desktop. No multi-node or sustained soak test |
| **Judge throughput is the real ceiling** | **KNOWN LIMITATION** | `replicas × WORKER_CONCURRENCY` containers. The API absorbs far more than the judge can drain |

---

## Observability

| Item | Status | Evidence |
|---|---|---|
| Prometheus metrics on all three services | **PASS** | `ObservabilityIT`; Phase 10 E2E |
| Business metrics separate from HTTP metrics | **PASS** | `codearena_submissions_accepted` vs `codearena_judge_submissions` |
| Latency histograms for API, queue wait, judging, sandbox | **PASS** | Timers with percentile histograms |
| Structured logs with a consistent event vocabulary | **PASS** | `event=NAME key=value` throughout |
| Request id reaches the logs | **PASS** | Phase 10 E2E greps for it — **this was broken until Phase 10** |
| Submission id correlates API → worker → executor | **PASS** | Phase 10 E2E greps all three logs |
| Optional JSON logging with no extra dependency | **PASS** | `LOG_STRUCTURED_FORMAT` |
| Worker heartbeat with healthy / stale / gone | **PASS** | `WorkerHeartbeatTest`, `ObservabilityIT`, Phase 10 E2E |
| Queue health reports age, not only depth | **PASS** | `ObservabilityIT` |
| Operational dashboard | **PASS** | `/admin/system`; `AdminSystemPage.test.tsx` |
| **No distributed tracing** | **KNOWN LIMITATION** | Deliberate (ADR-040). No span timings across the queue |
| **No alert evaluation** | **KNOWN LIMITATION** | Ten alerts documented with metric and threshold; nothing evaluates them |
| **No log aggregation** | **KNOWN LIMITATION** | Logs go to stdout; shipping and retention are a deployment's concern |

---

## Data integrity

| Item | Status | Evidence |
|---|---|---|
| PostgreSQL is authoritative for every domain fact | **PASS** | Redis holds only ids, sessions and buckets |
| Schema owned by Flyway; `ddl-auto: validate` | **PASS** | `InfrastructureIT` asserts V1–V8 in order |
| Migrations applied by the API only | **PASS** | Workers never migrate, so replicas cannot race |
| Contest status is derived, never stored | **PASS** | ADR-032; `ContestStatusTest` |
| Scoring is a pure function of persisted results | **PASS** | `ContestScoringTest` |
| Audit events cannot be altered or removed | **PASS** | `AuditApiIT` |
| A success audit event cannot outlive a rolled-back change | **PASS** | `AuditApiIT` |
| Internal ids never exposed; UUID public ids only | **PASS** | Enforced in the DTOs; asserted across the API suites |
| **No soft delete or history for domain rows** | **KNOWN LIMITATION** | Deleting a contest is permanent. The audit log records that it happened, not what it contained |

---

## Deployment

| Item | Status | Evidence |
|---|---|---|
| One image per service, multi-stage, pinned bases | **PASS** | Four Dockerfiles |
| Every application service runs as a non-root user | **PASS** | `codearena` in three images; nginx unprivileged in the fourth |
| Health checks on all six services | **PASS** | `docker compose ps` |
| Memory and CPU ceilings on all six | **PASS** | Phase 10 E2E asserts each has one |
| Stop grace covers each declared drain | **PASS** | Verified by a clean deregistration |
| No Docker socket except the executor | **PASS** | One mount in the whole compose file |
| Configuration externalised; no secret in any image | **PASS** | `.env.example` documents every variable |
| `**/.env` excluded from build context at every depth | **PASS** | Fixed this phase — `frontend/.env` was being copied in |
| Production overrides documented | **PASS** | [operations.md](operations.md#deployment-configuration) |
| **No CI/CD pipeline** | **KNOWN LIMITATION** | Building and deploying are manual. Phases 13–16 |
| **No image signing or SBOM** | **KNOWN LIMITATION** | Base images are tag-pinned, not digest-pinned |
| **No TLS in the shipped stack** | **KNOWN LIMITATION** | Plain HTTP locally. `SESSION_COOKIE_SECURE=true` is required behind a real terminator |

---

## Recovery

| Item | Status | Evidence |
|---|---|---|
| Backup procedure documented and runnable | **PASS** | [operations.md](operations.md#backup-and-restore) |
| Restore procedure documented | **PASS** | Same |
| Redis loss is survivable with no backup | **PASS** | Phase 10 E2E — the queue rebuilds from PostgreSQL |
| Fault injection across every service | **PASS** | Phase 10 E2E — worker, executor, Redis and API each restarted under load |
| No submission lost across any restart | **PASS** | Phase 10 E2E |
| **RPO = age of the last backup** | **KNOWN LIMITATION** | No WAL archiving, no PITR, no replication |
| **RTO = a manual restore** | **KNOWN LIMITATION** | Nothing fails over |
| **No automated or off-site backups** | **KNOWN LIMITATION** | The documented command writes to the host it runs on |
| **Audit log loss is permanent** | **KNOWN LIMITATION** | Append-only and reconstructible from nothing |

---

## Testing

| Item | Status | Evidence |
|---|---|---|
| Unit tests for pure logic | **PASS** | Scoring, status transitions, password policy, rate-limit maths, heartbeat reading |
| Integration tests against real PostgreSQL and Redis | **PASS** | Testcontainers throughout; no in-memory substitute |
| Sandbox security tests against a real Docker daemon | **PASS** | `SandboxSecurityIT`, `SandboxIsolationIT` |
| Fault injection in tests, not only in scripts | **PASS** | `RateLimitRedisOutageIT` severs a real connection with a proxy |
| Concurrency tested where correctness depends on it | **PASS** | `RateLimitIT`, `AuditApiIT` |
| Frontend unit and component tests | **PASS** | Vitest + Testing Library |
| End-to-end suites against the live stack | **PASS** | Phase 8, 9 and 10 scripts |
| **No CI** | **KNOWN LIMITATION** | Every suite is run by hand |
| **No mutation or fuzz testing** | **KNOWN LIMITATION** | Not attempted |
| **No browser-level end-to-end** | **KNOWN LIMITATION** | The frontend is tested by unit and component tests, and by asserting the built bundle's behaviour. No Playwright |

---

## Documentation

| Item | Status | Evidence |
|---|---|---|
| Architecture, with what is *not* built | **PASS** | [architecture.md](architecture.md) |
| Threat model | **PASS** | [threat-model.md](threat-model.md) |
| 42 decision records with rejected options and trade-offs | **PASS** | [decisions.md](decisions.md) |
| Auditing, rate limiting, observability, operations | **PASS** | Four dedicated documents |
| Runbook usable by somebody else | **PASS** | [operations.md](operations.md#runbook) — every step is a runnable command |
| Backup, restore and disaster recovery | **PASS** | Including what is *not* guaranteed |
| Every configuration variable documented | **PASS** | `.env.example` |
| Known limitations stated rather than omitted | **PASS** | Every document ends with them |

---

## The honest summary

**What this system does well.** It is secure in the ways that matter for an online judge:
untrusted code is properly contained, one user cannot reach another's work, the audit log
cannot be rewritten, and abuse controls are real rather than decorative. It recovers from
every single-component failure without losing work, and this phase proved that by breaking
each one. It is now observable enough that its own failures are visible, which it was not
two phases ago.

**What it is not.** It is a single-instance deployment with no failover, no CI, no TLS of
its own, no automated backups and no alert evaluation. Its isolation is container-grade, not
VM-grade. Those are not oversights; they are the boundary of what sixteen phases have
reached so far, and each one is named above with what it would take to close it.

**The most valuable thing Phase 10 produced** was not a metric or a dashboard. It was three
bugs that had been shipped and were invisible: a connection-pool timeout racing the browser's
own timeout into duplicate submissions, a worker deregistration that had never once
succeeded, and a graceful drain that was killed on every stop. All three were found by
looking, which is the entire argument for the phase.
