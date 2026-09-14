package com.codearena.system;

import com.codearena.audit.AuditEventRepository;
import com.codearena.queue.SubmissionQueue;
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
import java.time.Instant;

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

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AuditEventRepository auditEvents;
    private final Clock clock;
    private final String version;

    public AdminSystemController(JdbcTemplate jdbc,
                                 StringRedisTemplate redis,
                                 AuditEventRepository auditEvents,
                                 Clock clock,
                                 @Value("${codearena.version:development}") String version) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.auditEvents = auditEvents;
        this.clock = clock;
        this.version = version;
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
        return new SystemStatus(
                version,
                clock.instant(),
                checkDatabase(),
                checkRedis(),
                queueDepth(),
                auditEvents.count());
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

    /**
     * How many submissions are waiting, and how many are being judged.
     *
     * <p>The single most useful operational number here: a pending depth that climbs while
     * the processing depth stays flat means the workers have stopped consuming.
     *
     * <p>A Redis failure yields nulls rather than zeros. Zero is a claim — "nothing is
     * queued" — and the wrong one to make when the truth is "we could not ask".
     */
    private QueueDepth queueDepth() {
        try {
            Long pending = redis.opsForList().size(SubmissionQueue.PENDING);
            Long processing = redis.opsForList().size(SubmissionQueue.PROCESSING);
            return new QueueDepth(pending, processing);
        } catch (RuntimeException e) {
            log.warn("event=ADMIN_STATUS_QUEUE_UNAVAILABLE reason={}", e.getClass().getSimpleName());
            return new QueueDepth(null, null);
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
            Dependency database,
            Dependency redis,
            QueueDepth queue,
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

    @Schema(description = "Judging queue depth. Null when Redis could not be reached — "
                        + "which is different from zero.")
    public record QueueDepth(Long pending, Long processing) {
    }
}
