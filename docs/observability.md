# Observability

How CodeArena is watched: what it measures, what it writes down, how a single request is
followed from a browser to a container and back, and what it deliberately does not collect.

Implemented in Phase 10. The architectural decisions are ADR-040, ADR-041 and ADR-042.

---

## The one rule

**Observing must not become disclosing.** A metrics endpoint is a read of a process's
internals and a log is a permanent record; both are easy to build by exposing everything and
filtering afterwards. Filtering afterwards is a rule somebody forgets to update.

So three constraints hold everywhere below, and each is asserted by a test rather than
merely intended:

- **No secret, credential, cookie, connection string or host path** appears in a metric, a
  log line or a status response.
- **No identifier appears in a metric label.** Not a user, a submission, a problem, a
  contest, an address or a username. Identifiers live in logs, one line each.
- **No submitted content is ever recorded** — not source code, not test inputs, not
  expected outputs. Counts and durations say how much work happened and how it turned out;
  they never say what was in it.

---

## Metrics

Micrometer, exposed in Prometheus format. The registry is the only dependency added —
application code talks to `MeterRegistry`, so the exposition format is a deployment choice
rather than something the code knows about.

| Service | Endpoint | Who can read it |
|---|---|---|
| backend | `/actuator/prometheus` | **ADMIN only** — the same rule as every other `/actuator/**` |
| worker | `/actuator/prometheus` | Internal compose network only; the worker publishes no ports |
| executor | `/actuator/prometheus` | Requires the executor token — only `/actuator/health` is exempt from that filter |

Nothing beyond `health`, `info` and `prometheus` is exposed anywhere. `env`, `configprops`,
`heapdump`, `threaddump`, `loggers`, `mappings` and `beans` are **not enabled at all**,
rather than enabled and restricted: an authorisation rule is one mistake away from being
wrong, and an endpoint that does not exist is not. A test asserts each returns 404 even for
an administrator.

### Why business metrics exist alongside HTTP metrics

Actuator already records `http_server_requests` — rate, latency and status per route
template — and that answers *is the API healthy*. It cannot answer *are submissions being
judged*, because a submission that is accepted, queued and never judged is a stream of
perfectly successful 202s.

> **The most diagnostic pair in the system is `codearena_submissions_accepted` against
> `codearena_judge_submissions`.** When the first keeps climbing and the second does not, the
> judge has stopped — and every HTTP metric still looks fine.

### What is measured

**API server**

| Metric | Type | Labels |
|---|---|---|
| `codearena_submissions_accepted` | counter | `language`, `kind` (practice/contest) |
| `codearena_auth_attempts` | counter | `operation` (login/register), `outcome` |
| `codearena_contests_registrations` | counter | — |
| `codearena_audit_writes` | counter | `outcome` (success/failure) |
| `codearena_ratelimit_decisions` | counter | `policy`, `outcome`, `identity` |
| `codearena_queue_depth` | gauge | `state` (pending/processing) |
| `codearena_queue_oldest_age_seconds` | gauge | — |
| `codearena_workers` | gauge | `state` (healthy/stale) |

**Worker**

| Metric | Type | Labels |
|---|---|---|
| `codearena_judge_submissions` | counter | `verdict`, `language` |
| `codearena_judge_duration` | timer | — |
| `codearena_judge_queue_wait` | timer | — |
| `codearena_judge_active` | gauge | — |
| `codearena_judge_claims_skipped` | counter | — |
| `codearena_judge_deferred` | counter | — |
| `codearena_judge_jobs_malformed` | counter | — |

**Executor**

`codearena_sandbox_executions`, `_timeouts`, `_out_of_memory`, `_output_limit`,
`_file_limit`, `_docker_errors`, `_creation_failures`, `_cleanup_failures`, `_reaped`, plus
two timers: `codearena_sandbox_startup` and `codearena_sandbox_execution`. No labels at all.

Every service also tags its meters with `application` and `version`, both bounded.

### Queue wait and judging duration are separate timers

They answer different questions and have different fixes:

- **Long wait, short judgement** — not enough judging capacity for the arrival rate. Add
  workers or raise `WORKER_CONCURRENCY`.
- **Short wait, long judgement** — the work itself got slower. Look at the sandbox timers.

Summed into one number, neither is visible, and "submissions are slow" is true without
saying which lever to pull.

### Sandbox startup is timed separately for the same reason

A whole judgement's duration includes queueing inside the executor, the container start and
every test case. Startup is the part that is pure overhead — the gap between deciding to run
a program and it beginning to run — and it is the first thing to check when judging slows
down without the programs changing.

### Why no label is ever an identifier

A metric tagged with a user creates one time series per user. The consequence is not
untidiness: a busy evening quietly turns the monitoring system into the outage, at the exact
moment somebody needs to look at it. `ObservabilityIT` asserts that no username and no
public id appears anywhere in a scrape.

---

## Logging

Structured, consistent and machine-readable, in the format the deployment asks for.

```
2026-09-14 08:29:56.515 INFO  [judge-consumer-42] sub=6f2c…  c.c.w.q.SubmissionConsumer - event=SUBMISSION_JUDGED submission=6f2c… worker=9e8a… status=ACCEPTED tests=8/8 durationMs=1840 recorded=true
```

Every message is `event=NAME key=value …`. Values are identifiers and counts; never content.

### A gap this phase closed

Phase 8 added `RequestIdFilter`, which puts a request id in the MDC, and documented request
correlation as a feature. **No log pattern in any of the three services printed the MDC.**
The id reached the audit table and nothing an operator could grep, so "find the log line for
this audit event" did not work. The patterns now carry it:

- backend — `req=%X{requestId:-none}`
- worker and executor — `sub=%X{submissionId:-none}`

### JSON when a deployment wants it

`LOG_STRUCTURED_FORMAT=ecs` (or `logstash`, or `gelf`) switches every line to JSON with the
MDC fields as first-class keys. It uses Spring Boot's built-in structured logging, so there
is no extra dependency and no second logging configuration to keep in step. Empty by
default, because somebody reading `docker compose logs` wants the readable form.

### Level policy

| Level | Means | Example |
|---|---|---|
| ERROR | A failed operation that needs investigating | `AUDIT_WRITE_FAILED`, `RATE_LIMIT_BACKEND_UNAVAILABLE` |
| WARN | A recoverable abnormality | `JUDGE_DEFERRED`, `HEARTBEAT_FAILED`, `RATE_LIMIT_REJECTED` |
| INFO | A meaningful lifecycle event | `SUBMISSION_CLAIMED`, `SUBMISSION_JUDGED`, `WORKER_DEREGISTERED` |
| DEBUG | Development diagnostics only | — |

> **DEBUG must never be enabled in a deployment.** Spring at DEBUG prints request bodies,
> and this application's request bodies contain passwords and submitted source code. This is
> most acute on the executor, where a debug-level HTTP log would be a source-code
> disclosure.

**Never logged, at any level:** passwords or hashes, session identifiers, CSRF tokens, the
executor token, submitted source, hidden test inputs or expected outputs, database or Redis
credentials.

### Log volume is bounded where a caller controls it

A rejected caller chooses how many rejections they cause, so a line per rejection would be a
log flood by invitation. Rate-limit rejections are collapsed to one line per identity per
cooldown, using the same Redis marker that bounds the audit events.

---

## Correlation

Two identifiers, each covering the half of the journey the other cannot.

```
browser ──request id──▶ API ──submission id──▶ Redis ──▶ worker ──▶ executor ──▶ container
           │                     │                                       │
           └── logs, audit ──────┴── logs on every service ──────────────┘
```

**Request id** — from `X-Request-Id` if the client sent a safe one, generated otherwise.
Sanitised to letters, digits, hyphens and underscores, bounded at 64 characters, because it
lands in log files and in a table that is never deleted, and a newline would let a caller
forge log lines. Echoed back in the response, so a frontend can correlate its own logs.

**Submission id** — the domain correlation key, and the one that crosses processes. An HTTP
request ends when the 202 is returned; the work continues for seconds or minutes afterwards,
in two other services. The submission id is in the MDC for every line that work produces.

The executor receives it in the prepare call and holds it against the workspace, so the
compile and run calls that follow — which carry only a workspace id — are logged against the
same submission. It is **sanitised before it reaches a log line**: the executor is one hop
from untrusted code, and a caller that could put a newline in a log line could forge entries
in the one component whose logs matter most.

### No distributed tracing, and why

OpenTelemetry was considered and not adopted. The chain that matters here is not deep — one
API call, one queue hop, two services — and it is already followable end to end by a single
identifier that appears in every log line and in the audit table. A tracing backend would
add a collector to run, a sampling policy to tune and an exporter in three services, to
answer questions that `grep` already answers on a system of this shape.

The cost of the decision is stated rather than hidden: **there are no span-level timings
across the queue boundary**, so "where did these 900ms go" is answered by comparing the
queue-wait and judging timers rather than by reading a waterfall. That is a real loss, and a
small one at four services. ADR-040 records the reasoning and what would change it.

---

## Worker heartbeat

Phase 8 deliberately left this out and said so: the API server sits on a different network
from the worker and cannot reach it. This is the direct answer, and it travels the way
everything else between those two processes travels — through Redis.

Each worker writes `codearena:workers:{id}` every 10 seconds: id, version, start time, last
seen, concurrency, active jobs, judged, infrastructure failures, draining. **No token, no
database URL, no path, no submission content.**

### Three states, not two

| State | Meaning |
|---|---|
| **Healthy** | Beat within 35 seconds (three intervals, so one slow tick is not a fault) |
| **Stale** | The record is there and nobody has touched it |
| **Gone** | The record expired (5 minutes) or the worker removed it on a clean stop |

The TTL is far longer than the staleness threshold on purpose. Were they equal, a worker
that died would *vanish* rather than appear stale — and "no workers are registered" is a much
weaker signal than "this worker stopped reporting four minutes ago".

> **A worker container that exists is not a worker that works.** `docker compose ps` reports
> a worker as "Up" while its consumer threads are wedged, its Redis connection is gone, or it
> is looping on an error. A heartbeat written by the same process that does the work cannot
> be green while the work is not happening.

### Two bugs this found

**Deregistration never worked.** The first version removed the record in `@PreDestroy`.
Spring closes a context in three steps — publish `ContextClosedEvent`, stop lifecycle beans,
destroy beans — and `LettuceConnectionFactory` is a lifecycle bean, so by the time
`@PreDestroy` ran the Redis connection was already shut. Every deployment left a worker
looking stale for five minutes, and the only evidence was one WARN line during shutdown.
Both the drain and the deregistration now hang off `ContextClosedEvent`.

**The drain never completed.** The worker allows 30 seconds to finish in-flight judgements;
Docker sends SIGKILL 10 seconds after SIGTERM by default. The drain was cut short on every
single stop. `stop_grace_period` now covers it — see [operations.md](operations.md).

---

## Queue health

Four numbers, from the two stores that can each answer part of the question. Depths come
from Redis, because the lists *are* the queue; ages and counts come from PostgreSQL, because
the queue holds nothing but submission ids by design.

| Field | Why it is there |
|---|---|
| `pending` / `processing` | Depth. Null, never zero, when the store could not be asked |
| `oldestPendingAgeSeconds` | **The field that gives depth meaning** |
| `retrying` | Judgements interrupted and recovered — the warning before the incident |
| `systemErrorsLastHour` | The pipeline's own error rate; never the submitter's fault |

A pending count of two hundred means nothing alone: it is a healthy contest afternoon, and
it is also what a completely stopped worker pool looks like an hour in. A deep queue that is
draining has a young oldest-item; a queue nobody is consuming has one that grows in real
time. That single field turns *the queue is deep* into *the queue is stuck*.

Queue payloads are never exposed. An operator needs to know the oldest submission has waited
nine minutes; they do not need to see it, and an endpoint that could show it would be a way
to read other people's code.

---

## Health semantics

Three kinds of health, kept apart because they answer different questions and doing the
wrong thing about each is expensive. Full reasoning in ADR-042.

| | Endpoint | Includes | Failing it means |
|---|---|---|---|
| **Liveness** | `/actuator/health/liveness` | `livenessState` only | Restart the process |
| **Readiness** | `/actuator/health/readiness` | `readinessState`, `db`, `redis` | Take it out of rotation |
| **Operational** | `/api/admin/system/status` | Everything below | A human should look |

**Liveness never depends on a dependency.** Restarting a healthy API server has never
repaired a database, and a liveness probe that fails when PostgreSQL is slow turns one
outage into a restart loop that guarantees a second.

**Readiness does**, because serving traffic genuinely requires both stores. Failing it is
correct *and reversible*.

**DEGRADED is neither.** It means the API is serving perfectly and something is still wrong:
work is waiting with no healthy worker, or the oldest item has been waiting more than five
minutes. It deliberately does not affect readiness — an API server whose workers have died
can still serve the catalogue, the contests and the history, and removing it from rotation
would turn a judging outage into a total one.

> This is the state that could not be seen before Phase 10: every dependency answers, every
> request succeeds, and no submission gets judged.

---

## The dashboard

`/admin/system` renders the status view, ordered the way somebody triaging would want it:
the verdict first, then the judge workers, then the queue, then dependencies, then the
build. Worker ages are computed against the **server's** clock, so a viewer whose laptop is
out by a minute does not see every worker as a minute staler than it is.

It polls every 10 seconds and backs off on a 429 rather than keeping its rhythm through a
refusal. A status page that ignores a rate limit is the shape of load the limit exists to
shed, arriving from every open dashboard at once.

---

## Known limitations

- **No distributed tracing.** Deliberate (ADR-040). No span timings across the queue.
- **No log aggregation.** Logs go to stdout in whichever format is configured; shipping,
  retention and search are a deployment's concern and nothing here pretends otherwise.
- **No alerting.** The alerts in [operations.md](operations.md) are documented with the
  metric and threshold for each, and nothing evaluates them. There is no Alertmanager.
- **Metrics are per instance and in memory.** A restart resets every counter; rates survive
  because Prometheus handles counter resets, but absolute totals do not.
- **Worker metrics are not aggregated by the API.** The admin view shows each worker's own
  counts; a fleet-wide rate comes from the Prometheus scrape, not from the status endpoint.
- **No profiling endpoint.** `heapdump` and `threaddump` stay disabled. Diagnosing a memory
  problem means enabling them deliberately and temporarily, which is the right friction for
  an endpoint that hands over process memory.
