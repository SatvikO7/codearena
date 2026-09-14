# Operations

Running CodeArena: starting it, checking it, fixing it when it breaks, and getting it back
after it is lost.

Written to be usable by somebody who did not build it. Every command here is one you can
paste. Where a step cannot be done, it says so rather than describing something plausible.

See also: [observability.md](observability.md) for what the numbers mean,
[rate-limiting.md](rate-limiting.md) for the abuse controls, [audit.md](audit.md) for the
audit log.

---

## Contents

1. [Starting and stopping](#starting-and-stopping)
2. [Is it working?](#is-it-working)
3. [Runbook](#runbook)
4. [Alerts worth having](#alerts-worth-having)
5. [Resource boundaries](#resource-boundaries)
6. [Storage](#storage)
7. [Capacity, measured](#capacity-measured)
8. [Backpressure](#backpressure)
9. [Backup and restore](#backup-and-restore)
10. [Disaster recovery](#disaster-recovery)
11. [Deployment configuration](#deployment-configuration)

---

## Starting and stopping

```bash
cp .env.example .env          # then set POSTGRES_PASSWORD and EXECUTOR_TOKEN
openssl rand -hex 32          # a suitable EXECUTOR_TOKEN

docker compose up -d --build
docker compose ps             # all six services should reach (healthy)
```

The API is on `localhost:8080`, the web client on `localhost:5173`. PostgreSQL and Redis are
deliberately **not** published: they sit on an internal network.

```bash
docker compose down           # stop, keep the data
docker compose down -v        # stop and DESTROY the volumes. See "Backup" first.
```

### Shutdown is ordered, and the order matters

Each service declares how long it needs to stop, and `stop_grace_period` in
`docker-compose.yml` covers it. This is worth knowing because getting it wrong is silent:

| Service | Drain | Grace | What the drain does |
|---|---|---|---|
| backend | 5s | 20s | Finishes in-flight requests. Short because SSE streams are designed never to end |
| worker | 30s | 45s | Stops claiming, marks itself draining, finishes in-flight judgements |
| executor | 30s | 45s | Lets running sandboxes finish so their containers are removed by the code that made them |

> Docker's default grace is **10 seconds**. Before these were set, the worker's 30-second
> drain was cut short by SIGKILL on every single stop — so it never deregistered, and every
> deployment left a worker that looked stale for five minutes.

**Nothing is lost if a drain is cut short.** A submission whose worker vanished mid-judgement
is recovered exactly as if the process had been killed: the claim lease expires and the
recovery sweeper returns it to the queue, bounded by `attempts`.

---

## Is it working?

Three different questions with three different answers. Do not use one to answer another.

```bash
# Is the process alive?  (What an orchestrator restarts on.)
curl -s localhost:8080/actuator/health/liveness

# Can it serve traffic?  (What a load balancer routes on. Includes PostgreSQL and Redis.)
curl -s localhost:8080/actuator/health/readiness

# Is anything wrong?  (What a human reads. ADMIN session required.)
curl -s -b cookies localhost:8080/api/admin/system/status | python -m json.tool
```

Or open **`/admin/system`** in the browser as an administrator, which renders the same thing.

### Reading the status

```json
{
  "state": "DEGRADED",
  "queue": { "pending": 82, "processing": 0, "oldestPendingAgeSeconds": 900,
             "retrying": 0, "systemErrorsLastHour": 0 },
  "workers": [ { "id": "9e8a…", "healthy": false, "lastSeenAt": "…", "judged": 152 } ]
}
```

| `state` | Means |
|---|---|
| `READY` | Dependencies answer and the queue is moving |
| `DEGRADED` | **The API is fine and work is not being judged.** Read the workers |
| `UNAVAILABLE` | A dependency is not answering |

`pending: null` means Redis could not be asked. That is not the same as zero, and the page
says `unknown` rather than pretending otherwise.

---

## Runbook

### The queue is backing up

**Symptom:** `oldestPendingAgeSeconds` climbing in real time; `state` is `DEGRADED`.

```bash
curl -s -b cookies localhost:8080/api/admin/system/status | python -m json.tool
```

1. **Are there healthy workers?** If `workers` is empty or all `healthy: false`, go to
   [stale worker](#a-worker-is-stale-or-missing).
2. **Are they busy?** `activeJobs` equal to `concurrency` on every worker means the pool is
   saturated, not broken. This is a capacity problem: add a worker
   (`docker compose up -d --scale worker=3`) or raise `WORKER_CONCURRENCY`.
3. **Is `retrying` rising?** Judgements are being interrupted and recovered. Check the
   executor — see [executor failure](#the-executor-is-failing).
4. **Is `systemErrorsLastHour` rising?** Judgements are giving up. Same place to look.

A deep queue with a *young* oldest item is a busy system draining normally. Leave it.

### A worker is stale or missing

**Stale** means the record exists and nobody has touched it for more than 35 seconds. The
process may well still be running — that is the point of the check, and it is what a
container health check cannot tell you.

```bash
docker compose ps worker
docker compose logs worker --tail 200 | grep -E "ERROR|WARN|CONSUMER_ERROR|HEARTBEAT_FAILED"
docker compose exec redis redis-cli KEYS 'codearena:workers:*'
docker compose exec redis redis-cli HGETALL codearena:workers:<id>
```

- `HEARTBEAT_FAILED` — the worker cannot reach Redis. It is probably not judging either.
- `CONSUMER_ERROR` repeating — a consumer thread is looping on a failure.
- Nothing at all in the logs — the process is wedged.

**Restart it:**

```bash
docker compose restart worker
```

Safe at any time. In-flight judgements are returned to the queue by the recovery sweeper
once their lease expires; nothing is lost, and the submissions are re-judged.

**A worker that stopped cleanly removes its own record**, so a worker that is *missing*
rather than stale was stopped deliberately — or crashed more than five minutes ago, after
which the record expires.

### No workers at all

```bash
docker compose up -d worker
docker compose logs worker --tail 50
```

Common causes, in order of likelihood: `EXECUTOR_TOKEN` unset or wrong (the worker refuses to
start without it), Redis unreachable, the database unreachable.

### Redis is down

**Everything authenticated stops**, because sessions live in Redis. Expect 401s across the
board; this is not a separate bug.

```bash
docker compose ps redis
docker compose logs redis --tail 100
docker compose restart redis
docker compose exec redis redis-cli PING     # expect PONG
```

**What survives:** every submission, verdict, problem, contest and audit event — all of that
is in PostgreSQL. Redis is persisted with an append-only file, so the queue survives a
restart too.

**What does not:** active sessions if the AOF is lost (everyone signs in again), and
rate-limit buckets (everyone starts with a full allowance, which is the safe direction).

**While it is down:** login, registration and submission are refused with 429 — the abuse
controls fail closed, deliberately (see [rate-limiting.md](rate-limiting.md)). Reads that do
not need a session keep working.

### PostgreSQL is down

**The system is down.** Readiness fails, which is correct: an instance that cannot reach its
database cannot serve.

```bash
docker compose ps postgres
docker compose logs postgres --tail 100
docker compose restart postgres
docker compose exec postgres pg_isready -U codearena -d codearena
```

Liveness stays green throughout, deliberately — restarting the API server will not repair
the database, and a restart loop would only make the recovery harder.

### The executor is failing

The executor is the only component holding Docker control. When it fails, judgements return
`SYSTEM_ERROR` rather than wrong verdicts.

```bash
docker compose logs executor --tail 200 | grep -E "ERROR|SANDBOX|DOCKER"
docker compose exec executor curl -s -H "X-CodeArena-Executor-Token: $EXECUTOR_TOKEN" \
  localhost:8082/api/execution/status
docker compose restart executor
```

- **Permission denied on the Docker socket** — `DOCKER_SOCKET_GID` is wrong for this host.
  Linux: `stat -c %g /var/run/docker.sock`. Docker Desktop: `0`.
- **Capacity exceeded** — `SANDBOX_MAX_OPEN_WORKSPACES` reached. Either genuine load, or
  workspaces are leaking; check `codearena_sandbox_cleanup_failures`.
- **Image not found** — the sandbox images are built with `--pull never`. Build them.

### Submissions are failing with SYSTEM_ERROR

`SYSTEM_ERROR` always means *we* failed, never the submitter.

```bash
docker compose exec postgres psql -U codearena -d codearena -c \
  "SELECT public_id, attempts, error_message, finished_at FROM submissions
   WHERE status='SYSTEM_ERROR' ORDER BY finished_at DESC LIMIT 20;"
```

Then take one submission id and follow it across every service — this is what the
correlation identifiers are for:

```bash
docker compose logs worker executor | grep <submission-id>
```

### Investigating a specific request

The API returns `X-Request-Id` on every response, and audit events record it.

```bash
docker compose logs backend | grep "req=<request-id>"

docker compose exec postgres psql -U codearena -d codearena -c \
  "SELECT occurred_at, actor_username, action, outcome, entity_id
   FROM audit_events WHERE request_id='<request-id>';"
```

### Checking the audit log

```bash
# Recent administrative activity
docker compose exec postgres psql -U codearena -d codearena -c \
  "SELECT occurred_at, actor_username, action, outcome, entity_type, entity_id
   FROM audit_events ORDER BY id DESC LIMIT 40;"

# Anything refused
docker compose exec postgres psql -U codearena -d codearena -c \
  "SELECT occurred_at, actor_username, action, metadata
   FROM audit_events WHERE outcome <> 'SUCCESS' ORDER BY id DESC LIMIT 40;"
```

Or use the admin UI at `/admin/audit`, which filters and pages.

The table is append-only and enforced by a database trigger: `UPDATE` and `DELETE` are
refused. If you need to prune it, that is a governed operation — see
[audit.md](audit.md#retention-stated-honestly).

### Checking sandbox cleanup

Stray containers or volumes mean something died unexpectedly.

```bash
docker ps -a --filter "name=codearena-sandbox" --format "{{.Names}} {{.Status}}"
docker volume ls --filter "name=codearena-ws-"
```

Both should be empty when the system is idle. The reaper sweeps every 5 minutes with a
15-minute grace, so a handful during active judging is normal. A number that only grows is
not: check `codearena_sandbox_cleanup_failures` and the executor logs.

### Everything looks fine and nothing is judged

The case the `DEGRADED` state exists for.

1. `state` is `DEGRADED`, `workers` is empty or all stale → the workers are gone.
2. `workers` are healthy with `activeJobs: 0` and `pending > 0` → the workers are running
   and not claiming. Check the worker's Redis connectivity; check that the worker and the
   API agree on the queue key (`codearena:submissions:pending`).
3. `retrying` climbing with `judged` flat → judgements start and never finish. Check the
   executor.

---

## Alerts worth having

Nothing evaluates these — there is no Alertmanager in this deployment. They are written with
the metric and the threshold so that wiring them up is mechanical rather than a design task.

| Alert | Condition | Why it matters |
|---|---|---|
| API errors | `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05 × total` | The API is failing |
| **Judging stopped** | `codearena_workers{state="healthy"} == 0 and codearena_queue_depth{state="pending"} > 0` | **Nothing is being judged and every request still succeeds** |
| Queue backlog | `codearena_queue_depth{state="pending"} > 200` for 10m | More arriving than leaving |
| Queue stuck | `codearena_queue_oldest_age_seconds > 300` | Not a slow problem — a missing worker |
| Worker stale | `codearena_workers{state="stale"} > 0` for 5m | A worker's process exists and has stopped working |
| Judge failures | `rate(codearena_judge_submissions_total{verdict="SYSTEM_ERROR"}[15m])` above baseline | The judge is giving up |
| Sandbox leak | `increase(codearena_sandbox_cleanup_failures_total[1h]) > 0` | Resources are leaking |
| Redis unavailable | `up{job="codearena-backend"} == 1 and codearena_ratelimit_decisions_total{outcome="failed-closed"} > 0` | Abuse controls are failing closed |
| **Audit write failures** | `increase(codearena_audit_writes_total{outcome="failure"}[1h]) > 0` | **The log is recording less than it claims** |
| Rate-limit spike | `rate(codearena_ratelimit_decisions_total{outcome="rejected"}[5m])` far above baseline | An attack, or a limit set too tight |

The two in bold are the ones that are invisible without this phase's work: both leave every
health check green.

---

## Resource boundaries

Every service has a memory and CPU ceiling in `docker-compose.yml`, and the reason is
specific. Each JVM runs with `-XX:MaxRAMPercentage=75`, which is a percentage **of the
container's limit** — with no limit declared, the JVM reads the *host's* memory and sizes its
heap at 75% of the whole machine. Three services did that simultaneously.

| Service | Memory | CPUs |
|---|---|---|
| postgres | 1g | 2 |
| redis | 512m | 1 |
| backend | 1g | 2 |
| worker | 1g | 2 |
| executor | 512m | 1 |
| frontend | 128m | 0.5 |

### Sandboxes are not covered by any of those

They are sibling containers created through the host daemon, so they are charged to the host
rather than to the executor. Their total is bounded by arithmetic:

```
worker replicas × WORKER_CONCURRENCY × per-execution memory
```

With the defaults: `1 × 2 × 256 MB`. The per-execution ceiling is `SANDBOX_MAX_MEMORY_MB`
(1024), so the worst case a problem author can reach is `1 × 2 × 1024 MB`.

`SANDBOX_MAX_OPEN_WORKSPACES` (16) bounds *prepared workspaces*, which hold a volume but are
not running a container. It is not a bound on concurrent memory.

> **Scaling workers multiplies this.** Three workers at concurrency 2 is six concurrent
> sandboxes, up to 6 GB at the per-execution ceiling. Size the host for
> `replicas × concurrency × SANDBOX_MAX_MEMORY_MB`, not for the default problem limit.

### The other bounds

| Bound | Setting | Default |
|---|---|---|
| Submission source size | `MAX_SOURCE_BYTES` | 64 KiB |
| API database connections | `DB_POOL_SIZE` | 10 |
| Worker database connections | `DB_POOL_SIZE` (worker) | 5 |
| Judging concurrency per worker | `WORKER_CONCURRENCY` | 2 |
| Sandbox wall clock / memory / PIDs / output | `SANDBOX_MAX_*` | 60s / 1024 MB / 128 / 1 MiB |
| Per-user request rates | `RATE_LIMIT_*` | see [rate-limiting.md](rate-limiting.md) |

PostgreSQL's own `max_connections` must cover every API replica plus every worker:
`replicas × 10 + workers × 5`, plus headroom for `psql`.

---

## Storage

Measured on a development stack after sustained load testing:

| Store | Size | Grows with | Bounded by |
|---|---|---|---|
| `postgres-data` | 219 MB | submissions, audit events | **nothing — see below** |
| `redis-data` | 12.7 MB | sessions, queue, rate-limit buckets | TTLs on everything except the queue |
| `audit_events` | 960 kB | every security-sensitive action | **nothing. Append-only by design** |
| `submissions` | 608 kB | every submission, including its source | nothing |
| Sandbox volumes | 0 | — | removed after each execution; reaped every 5 minutes |
| Logs | — | traffic | Docker's driver; set `max-size` in production |

### What is honestly not enforced

> **There is no global disk quota.** Nothing stops `postgres-data` growing until the host
> fills. `audit_events` in particular can only ever grow: `DELETE` is refused by a database
> trigger, which is the point of it.

What *is* enforced, per execution: `RLIMIT_FSIZE` (64 MB) bounds what one program can write,
and the sandbox's writable area is a tmpfs charged to the container's memory cgroup — so
filling it is an OOM kill rather than a full host disk. Phase 6 established that
`--storage-opt` is accepted and ignored on overlayfs without project quotas, and this phase
does not claim otherwise.

**Monitor it** rather than assume it:

```bash
docker system df -v | grep codearena
docker compose exec postgres psql -U codearena -d codearena -c \
  "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS size
   FROM pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;"
docker compose exec redis redis-cli INFO memory | grep used_memory_human
```

Redis has **no eviction policy**, deliberately. Evicting would silently drop sessions and
queued jobs; being OOM-killed and restarting from the append-only file loses neither.

### Cleanup

- **Sandbox artefacts** — automatic. The reaper sweeps every 5 minutes; verify with the
  commands in [Checking sandbox cleanup](#checking-sandbox-cleanup).
- **Logs** — Docker's json-file driver is unbounded by default. In production set
  `max-size` and `max-file` on the logging driver.
- **Submissions** — no pruning exists. Deleting old submission source is the obvious first
  reclamation and is not implemented.
- **Audit events** — see [audit.md](audit.md). Pruning means dropping the trigger, pruning
  under supervision, and restoring it. Not an ordinary `DELETE`.

---

## Capacity, measured

`scripts/loadtest.py` drives the paths that cost something and reports percentiles.

```bash
python scripts/loadtest.py --users 15 --duration 15
```

429 is reported in its own column, never as an error: under load it is the limiter shedding
work before the queue fills, which is the system working.

### Results

Single machine, Docker Desktop on Windows, all six services with the ceilings above. 15
concurrent clients, 15 seconds per scenario, judge workers active on a live backlog
throughout.

| Scenario | Throughput | p50 | p95 | p99 | Failures |
|---|---|---|---|---|---|
| Browse problems | 106.8/s | 36.7 ms | 82.4 ms | 136.3 ms | 0 |
| Search problems | 136.2/s | 18.7 ms | 46.3 ms | 71.5 ms | 0 |
| Submission history | 127.1/s | 24.6 ms | 53.6 ms | 78.2 ms | 0 |
| Failed logins | 147.0/s | 25.1 ms | 46.8 ms | 79.1 ms | 0 |
| Submissions | 174.5/s | 29.9 ms | 72.9 ms | 176.8 ms | 0 |

Queue behaviour during the run: depth rose to 82 pending with 2 processing, oldest wait 25
seconds, `systemErrorsLastHour: 0`, and it drained afterwards without intervention.

### What the first run found

The numbers above are the *second* measurement. The first produced **p95 of 10.3 seconds on
submissions and 10 outright failures**, and the metrics added in this phase said why in one
line:

```
hikaricp_connections_timeout_total  10
hikaricp_connections_acquire_seconds  3487 acquisitions, 103.6s total
```

The API's connection pool was the binding constraint, and its 10-second timeout set the tail
latency — *which is exactly the browser's own HTTP timeout*. Requests were succeeding at
10.2 seconds, just after the client had given up: the submission existed, the user was shown
a failure, and the natural response was to submit again.

The pool timeout is now 5 seconds, so the server always answers before the client stops
listening. A duplicate submission caused by a timeout race is worse than a clear refusal.

**This is what the phase was for.** The bug was there in Phase 9; nothing could see it.

---

## Backpressure

What happens when submissions arrive faster than they can be judged.

1. **Rate limiting sheds first.** Per user, 10 burst then 6/minute, shared between practice
   and contests. Measured above: 2,512 of 2,622 submission attempts were refused with 429
   and none failed.
2. **The queue absorbs the rest.** A Redis list, bounded by Redis's memory limit rather than
   by a queue-length setting.
3. **Worker concurrency bounds execution.** `WORKER_CONCURRENCY` sandboxes per worker,
   whatever the queue depth. The queue growing does not make the host run more containers.
4. **The database pool bounds writes**, and now fails fast rather than racing the client.
5. **The status view says so.** Depth and oldest-age rise; `state` becomes `DEGRADED` past
   five minutes.

Degradation is *latency*, not failure: submissions wait longer to be judged and the API
keeps answering. Verified in the load test — zero 5xx throughout, PostgreSQL and Redis both
responsive, the queue draining afterwards.

> **What is not bounded:** queue length. A sustained flood from many users would grow the
> pending list until Redis hit its memory limit. The per-user limit makes that expensive but
> there is no system-wide submission cap.

---

## Backup and restore

### What must be backed up

**PostgreSQL. Everything else is derived or recoverable.**

| Data | Where | Authoritative? |
|---|---|---|
| Users, problems, test cases | PostgreSQL | **Yes** |
| Submissions, verdicts, per-test results | PostgreSQL | **Yes** |
| Contests, participants | PostgreSQL | **Yes** |
| Audit events | PostgreSQL | **Yes** — and irreplaceable, being append-only |
| Sessions | Redis | No. Losing them signs everyone out |
| Judging queue | Redis | No. Recoverable from PostgreSQL — see below |
| Rate-limit buckets | Redis | No. Losing them grants full allowances |

> **Redis is not the authoritative store for any domain state.** The queue holds submission
> ids and nothing else, precisely so it can never become a second copy of the truth.

### Taking a backup

```bash
docker compose exec -T postgres pg_dump -U codearena -Fc codearena > codearena-$(date +%F).dump
```

`-Fc` is the custom format: compressed, and restorable selectively. Store it off the host —
a backup on the machine you are protecting against is not a backup.

Verify it rather than assuming:

```bash
docker compose exec -T postgres pg_restore -l /dev/stdin < codearena-$(date +%F).dump | head
```

### Restoring

```bash
docker compose stop backend worker            # nothing may write during a restore
docker compose exec -T postgres dropdb   -U codearena codearena
docker compose exec -T postgres createdb -U codearena codearena
docker compose exec -T postgres pg_restore -U codearena -d codearena --no-owner < backup.dump
docker compose start backend worker
```

### Migrations

Flyway runs **only in the API server**, at startup, so several workers can never race to
migrate. A restored dump already carries `flyway_schema_history`; starting the API applies
anything newer. Restoring a dump from a *newer* schema into an older build is not supported —
`ddl-auto: validate` will refuse to start, which is the correct outcome.

---

## Disaster recovery

### PostgreSQL lost entirely

```bash
docker compose down
docker volume rm codearena_postgres-data
docker compose up -d postgres
# wait for healthy, then:
docker compose exec -T postgres createdb -U codearena codearena
docker compose exec -T postgres pg_restore -U codearena -d codearena --no-owner < backup.dump
docker compose up -d
```

**Recovery point:** the last backup. Everything after it is gone — submissions, verdicts and
audit events alike.

**Queued work:** submissions that were `QUEUED` or `RUNNING` at backup time are returned to
the queue by the recovery sweeper once the API starts, and re-judged. Submissions accepted
after the backup do not exist and cannot be recovered; their authors must submit again.

### Redis lost entirely

```bash
docker compose down
docker volume rm codearena_redis-data
docker compose up -d
```

No restore is needed and no backup is taken. What happens:

- **Sessions** — everyone signs in again.
- **Rate-limit buckets** — everyone starts with a full allowance. The safe direction.
- **The queue** — rebuilt from PostgreSQL. Submissions still `QUEUED` are republished by the
  recovery sweeper once their publication looks stale (2 minutes by default). **Nothing is
  lost**, because the submission row *is* the outbox.
- **Worker heartbeats** — rewritten within 10 seconds.

### Both lost

Restore PostgreSQL as above and start everything. Redis rebuilds itself.

### What is honestly not claimed

> **There is no zero-RPO or zero-RTO story here, and no high availability.** Concretely:
>
> - **RPO = the age of your last backup.** There is no streaming replication, no WAL
>   archiving and no point-in-time recovery configured.
> - **RTO = a manual restore.** Nothing fails over. Every service is a single instance.
> - **No off-site anything.** The backup command above writes to the machine you ran it on.
> - **Losing the audit log is permanent.** It is append-only and cannot be reconstructed
>   from anything else.
>
> A deployment that needs better should configure WAL archiving and replication. Saying so
> is more useful than a procedure that implies guarantees this does not provide.

---

## Deployment configuration

Three environments, one image. Everything below is an environment variable; see
`.env.example` for the complete list with defaults.

### Development

Defaults work as they are. Rate limiting stays **on** — a limit that only exists in
production is a limit nobody has tested.

### Test

The integration suite runs against real PostgreSQL and real Redis through Testcontainers.
Rate limiting stays on there too; what the harness removes is interference, by clearing the
limiter's own keys between tests.

### Production

| Setting | Value | Why |
|---|---|---|
| `SESSION_COOKIE_SECURE` | `true` | **Required** behind HTTPS |
| `POSTGRES_PASSWORD` | a real secret | No default exists; compose refuses to start without it |
| `EXECUTOR_TOKEN` | `openssl rand -hex 32` | Both services refuse to start without it |
| `CORS_ALLOWED_ORIGINS` | the real origin | Never a wildcard; credentials are allowed |
| `LOG_STRUCTURED_FORMAT` | `ecs` | JSON logs with correlation ids as keys |
| `LOG_LEVEL_ROOT` / `LOG_LEVEL_APP` | `INFO` | **Never DEBUG** — Spring prints request bodies, which here contain passwords and source code |
| `RATE_LIMIT_TRUST_FORWARDED` | `true` **only** behind a proxy that overwrites `X-Forwarded-For` | Otherwise a client picks its own bucket |
| `DB_POOL_SIZE` | sized against concurrent writers | The measured binding constraint |
| `*_MEM_LIMIT` / `*_CPUS` | sized for the host | `MaxRAMPercentage` is relative to these |
| Docker logging driver | `max-size`, `max-file` | Logs are otherwise unbounded |

**Secrets never go in the image.** They are environment variables, `.env` is in
`.gitignore` and `.dockerignore`, and `**/.env` is excluded at every depth — a gap this
phase closed, because `frontend/.env` was being copied into the frontend build.

### Behind a reverse proxy

The shipped compose stack has **no proxy in front of the API**: nginx serves the built
frontend and does not proxy `/api`. A deployment that adds one must:

1. Terminate TLS and set `SESSION_COOKIE_SECURE=true`.
2. Overwrite `X-Forwarded-For` rather than appending blindly, then set
   `RATE_LIMIT_TRUST_FORWARDED=true`. Until both are true, anonymous rate limiting is
   weaker than it looks — see [rate-limiting.md](rate-limiting.md).
3. Not proxy `/actuator/**` from the public internet. It is ADMIN-restricted, but there is
   no reason to expose it.
