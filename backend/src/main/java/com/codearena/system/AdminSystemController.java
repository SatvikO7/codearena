package com.codearena.system;

import com.codearena.audit.AuditEventRepository;
import com.codearena.shared.WorkerHeartbeat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.codearena.ratelimit.RateLimitPolicy;
import com.codearena.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Operational status, for an administrator. ADMIN only.
 *
 * <h2>Why this is not just Actuator</h2>
 * Actuator's endpoints are a superset of what an administrator needs and a superset of what
 * is safe to show: {@code /env} lists every property including database and Redis
 * credentials, {@code /configprops} does much the same, and {@code /heapdump} hands over
 * process memory. The existing configuration already restricts them to ADMIN, but "an
 * administrator could read the database password from a web page" is a poor default even so.
 *
 * <p>So this is a <b>curated</b> view: a short, hand-written set of facts chosen because an
 * operator needs them, rather than a dump filtered afterwards. Adding a field here is a
 * deliberate act, which is the property worth having.
 *
 * <h2>The three kinds of health, kept distinct</h2>
 * <ul>
 *   <li><b>Liveness</b> — {@code /actuator/health/liveness}. "Is the process alive?" Used by
 *       an orchestrator to decide whether to restart it. Unauthenticated by necessity.</li>
 *   <li><b>Readiness</b> — {@code /actuator/health/readiness}. "Can it serve traffic?" What
 *       the compose health checks poll, and what gates startup ordering.</li>
 *   <li><b>This endpoint</b> — "what is going on?" Authenticated, richer, and for a human.
 *       It is deliberately <em>not</em> wired into any health check: an operational view
 *       that an orchestrator depends on stops being free to change.</li>
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 * No credentials, no connection strings, no environment variables, no file paths, no Docker
 * or executor configuration, no security settings. The checks below report whether a
 * dependency answers — not how to reach it.
 *
 * <p><b>Worker liveness is also absent, and that is a limitation rather than an oversight.</b>
 * Workers expose a health endpoint, but on a network the API server has no route to: the
 * worker sits on {@code internal} and {@code sandbox}, and this process cannot poll it.
 * Reporting "unknown" would be honest but useless; reporting "up" without checking would be
 * worse than useless. The queue depths below are the honest proxy — a rising pending count
 * against a static processing count is what a stopped worker looks like from here. A real
 * heartbeat means workers writing to a shared store, which is a change to the worker and
 * belongs with the queue-observability work rather than here.
 */
@RestController
@RequestMapping("/api/admin/system")
@Tag(name = "Admin: system", description = "Curated operational status")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Not an administrator", content = @Content)
})
public class AdminSystemController {

    private static final Logger log = LoggerFactory.getLogger(AdminSystemController.class);

    /**
     * How long the oldest waiting submission may wait before the system calls itself
     * degraded.
     *
     * <p>Five minutes is far longer than any single judgement: the slowest legitimate one
     * is a compile timeout plus every test running to its limit, which is under two.
     * Anything waiting longer than this is not waiting for a slow problem, it is waiting
     * for a worker that is not coming.
     */
    private static final long STUCK_QUEUE_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AuditEventRepository auditEvents;
    private final WorkerDirectory workers;
    private final QueueHealth queueHealth;
    private final Clock clock;
    private final String version;
    private final Instant startedAt;

    public AdminSystemController(JdbcTemplate jdbc,
                                 StringRedisTemplate redis,
                                 AuditEventRepository auditEvents,
                                 WorkerDirectory workers,
                                 QueueHealth queueHealth,
                                 Clock clock,
                                 @Value("${codearena.version:development}") String version) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.auditEvents = auditEvents;
        this.workers = workers;
        this.queueHealth = queueHealth;
        this.clock = clock;
        this.version = version;
        this.startedAt = clock.instant();
    }

    @GetMapping("/status")
    @Operation(summary = "Operational status",
               description = """
                       A curated operational view for an administrator: whether the
                       dependencies answer, how deep the judging queue is, and how much
                       history exists.

                       Deliberately **not** a dump of Actuator. It carries no credentials, no
                       connection strings, no environment variables, no paths and no security
                       configuration — each field here was chosen, rather than filtered out
                       afterwards.

                       This is not a health check. Liveness and readiness remain
                       `/actuator/health/liveness` and `/actuator/health/readiness`; those are
                       what the container health checks poll, and they stay unauthenticated so
                       an orchestrator can reach them.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current status"),
            @ApiResponse(responseCode = "429", description = "Polling the status too often", content = @Content)
    })
    // Shares the administrative allowance with the audit search: the dashboard polls this
    // every ten seconds, which is a small fraction of it.
    @RateLimited(RateLimitPolicy.ADMIN_READ)
    public SystemStatus status() {
        Instant now = clock.instant();
        Dependency database = checkDatabase();
        Dependency redisStatus = checkRedis();
        QueueHealth.Depth queue = queueHealth.measure();
        List<WorkerHeartbeat.Snapshot> judges = workers.workers();

        List<Worker> workerViews = judges.stream().map(Worker::from).toList();
        return new SystemStatus(
                version,
                now,
                Duration.between(startedAt, now).toSeconds(),
                serviceState(database, redisStatus, judges, queue),
                database,
                redisStatus,
                new QueueDepth(queue.pending(), queue.processing(), queue.oldestPendingAgeSeconds(),
                        queue.retrying(), queue.systemErrorsLastHour()),
                workerViews,
                auditHealth(),
                auditEvents.count());
    }

    /**
     * The three-state answer an operator actually wants.
     *
     * <p>Kept firmly separate from liveness and readiness, which are for an
     * orchestrator and mean different things (see docs/operations.md):
     *
     * <ul>
     *   <li><b>LIVE</b> -- the process is running. Never fails for a dependency, because
     *       restarting a healthy API server does not repair a database.</li>
     *   <li><b>READY</b> -- it can serve traffic: PostgreSQL and Redis both answer.
     *       Failing this takes the instance out of rotation, which is the correct
     *       response and a reversible one.</li>
     *   <li><b>DEGRADED</b> -- it is serving, and something is wrong anyway. Judging has
     *       stopped, or the queue is not draining. This is the state that existed
     *       before and could not be seen: every HTTP request succeeds, every dependency
     *       answers, and no submission gets judged.</li>
     * </ul>
     *
     * <p>DEGRADED deliberately does not affect readiness. An API server whose workers
     * have died can still serve the catalogue, the contests and the history; removing
     * it from rotation would turn a judging outage into a total one.
     */
    private String serviceState(Dependency database, Dependency redisStatus,
                                List<WorkerHeartbeat.Snapshot> judges, QueueHealth.Depth queue) {
        if (!database.up() || !redisStatus.up()) {
            return "UNAVAILABLE";
        }
        boolean noHealthyWorker = judges.stream().noneMatch(WorkerHeartbeat.Snapshot::healthy);
        boolean workWaiting = queue.pending() != null && queue.pending() > 0;
        // Work waiting with nobody to do it. Not "no workers" on its own: a quiet system
        // with no workers and no queue is idle, and paging somebody for an idle system
        // is how a status field gets ignored.
        if (workWaiting && noHealthyWorker) {
            return "DEGRADED";
        }
        if (queue.oldestPendingAgeSeconds() != null
                && queue.oldestPendingAgeSeconds() > STUCK_QUEUE_SECONDS) {
            return "DEGRADED";
        }
        return "READY";
    }

    /**
     * Whether the audit log can still be written.
     *
     * <p>A read, not a write: this endpoint must not add a row to an append-only table
     * every time somebody refreshes a dashboard. Reachability of the table is what can
     * honestly be checked from here, and the write path reports its own failures as
     * metrics and ERROR logs (ADR-037).
     */
    private AuditHealth auditHealth() {
        try {
            Long recent = jdbc.queryForObject("""
                    SELECT count(*) FROM audit_events WHERE occurred_at > now() - interval '1 hour'
                    """, Long.class);
            return new AuditHealth(true, recent == null ? 0 : recent);
        } catch (RuntimeException e) {
            log.error("event=ADMIN_STATUS_AUDIT_UNAVAILABLE reason={}", e.getClass().getSimpleName());
            return new AuditHealth(false, 0);
        }
    }

    /**
     * Whether PostgreSQL answers.
     *
     * <p>{@code SELECT 1} rather than a metadata query: it proves the pool can hand out a
     * working connection, which is the question, and touches nothing.
     */
    private Dependency checkDatabase() {
        long startedAt = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Dependency.up(elapsedMs(startedAt));
        } catch (RuntimeException e) {
            // The reason is logged, never returned: a driver's message can name a host, a
            // port, a database and sometimes a user.
            log.error("event=ADMIN_STATUS_DB_DOWN reason={}", e.toString());
            return Dependency.down(elapsedMs(startedAt));
        }
    }

    private Dependency checkRedis() {
        long startedAt = System.nanoTime();
        try {
            String reply = redis.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(reply)
                    ? Dependency.up(elapsedMs(startedAt))
                    : Dependency.down(elapsedMs(startedAt));
        } catch (RuntimeException e) {
            log.error("event=ADMIN_STATUS_REDIS_DOWN reason={}", e.toString());
            return Dependency.down(elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    // ------------------------------------------------------------------ payload

    @Schema(description = "A curated operational view. Carries no credentials or configuration.")
    public record SystemStatus(
            @Schema(description = "Build identifier, or 'development' when unset.")
            String version,
            Instant serverTime,
            @Schema(description = "Seconds since this API instance started.")
            long uptimeSeconds,
            @Schema(description = "READY, DEGRADED or UNAVAILABLE. Not a health probe: "
                                + "see docs/operations.md for how this differs from "
                                + "liveness and readiness.")
            String state,
            Dependency database,
            Dependency redis,
            QueueDepth queue,
            @Schema(description = "Judge workers that have reported recently. Empty means "
                                + "none are registered, which during a backlog is an incident.")
            List<Worker> workers,
            AuditHealth audit,
            @Schema(description = "How many audit events exist. The log is append-only.")
            long auditEventCount) {
    }

    @Schema(description = "Whether a dependency answered, and how quickly.")
    public record Dependency(boolean up, long responseMs) {

        static Dependency up(long responseMs) {
            return new Dependency(true, responseMs);
        }

        static Dependency down(long responseMs) {
            return new Dependency(false, responseMs);
        }
    }

    @Schema(description = "Judging queue depth and age. Nulls mean the store could not be "
                        + "reached, which is different from zero.")
    public record QueueDepth(
            Long pending,
            Long processing,
            @Schema(description = "How long the longest-waiting submission has waited. The "
                                + "field that separates a busy queue from a stuck one.")
            Long oldestPendingAgeSeconds,
            @Schema(description = "Submissions claimed more than once: judgements that were "
                                + "interrupted and recovered.")
            long retrying,
            @Schema(description = "Judgements that gave up in the last hour. The pipeline's "
                                + "own error rate, never the submitter's fault.")
            long systemErrorsLastHour) {
    }

    /**
     * One judge worker.
     *
     * <p>Counts, timestamps and an identity -- nothing else. No executor token, no
     * database URL, no filesystem path, no Docker detail, no submission content. An
     * operator needs to know that a worker exists, whether it is still speaking, what
     * build it is running and how much it has done; everything beyond that would be
     * turning a status page into a disclosure channel.
     */
    @Schema(description = "A judge worker, as it last reported itself.")
    public record Worker(
            String id,
            String version,
            Instant startedAt,
            Instant lastSeenAt,
            @Schema(description = "True while it is still reporting. False means the process "
                                + "may exist and has stopped saying so, which a container "
                                + "health check cannot tell you.")
            boolean healthy,
            @Schema(description = "True once it has been asked to stop and is finishing its "
                                + "work. A planned departure, not a fault.")
            boolean draining,
            int concurrency,
            int activeJobs,
            long judged,
            long infrastructureFailures) {

        static Worker from(WorkerHeartbeat.Snapshot snapshot) {
            return new Worker(
                    snapshot.id(), snapshot.version(), snapshot.startedAt(), snapshot.lastSeenAt(),
                    snapshot.healthy(), snapshot.draining(), snapshot.concurrency(),
                    snapshot.active(), snapshot.judged(), snapshot.failed());
        }
    }

    @Schema(description = "Whether the audit log is readable, and how much was written recently.")
    public record AuditHealth(boolean readable, long eventsLastHour) {
    }
}
