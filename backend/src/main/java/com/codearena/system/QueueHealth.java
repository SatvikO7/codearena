package com.codearena.system;

import com.codearena.queue.SubmissionQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How the judging pipeline is coping, in the four numbers that actually decide it.
 *
 * <h2>Depth alone does not distinguish busy from broken</h2>
 * A pending count of two hundred means nothing on its own: it is a healthy Saturday
 * afternoon during a contest, and it is also what a completely stopped worker pool looks
 * like an hour in. What separates them is <b>age</b>. A deep queue that is draining has a
 * young oldest-item; a queue that is not being consumed has one that grows in real time,
 * second by second. That single field turns "the queue is deep" into "the queue is stuck",
 * which are different pages at three in the morning.
 *
 * <h2>Two sources, deliberately</h2>
 * Depths come from Redis, because the lists <em>are</em> the queue. Ages and retry counts
 * come from PostgreSQL, because the queue holds nothing but submission ids — by design,
 * so that Redis never becomes a second copy of the truth. Asking each store the question it
 * can actually answer is the cost of that design, and a small one.
 *
 * <h2>Payloads are never exposed</h2>
 * Nothing here reads a submission's source, a problem's tests, or who submitted what. These
 * are counts and intervals. An operator needs to know that the oldest waiting submission
 * has been waiting nine minutes; they do not need to see it, and a status endpoint that
 * could show it would be a way to read other people's code.
 */
@Component
public class QueueHealth {

    private static final Logger log = LoggerFactory.getLogger(QueueHealth.class);

    /**
     * How long a measurement is reused.
     *
     * <p>Measuring costs two Redis calls and three queries, and there are several callers:
     * the admin status endpoint, and one gauge per metric on every scrape. Without this, a
     * single Prometheus scrape would run a dozen queries to answer four questions that
     * cannot meaningfully differ within the same second.
     *
     * <p>Five seconds is well inside the resolution anyone reads these at -- the dashboard
     * polls every ten, Prometheus scrapes every fifteen -- so nothing observable is lost.
     */
    private static final Duration FRESH_FOR = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final AtomicReference<Measurement> cached = new AtomicReference<>();

    public QueueHealth(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    private record Measurement(long takenAtNanos, Depth depth) {
    }

    /**
     * Forgets the cached measurement.
     *
     * <p>For tests, which change the queue and assert on it immediately. Production
     * reads a five-second-old number on purpose.
     */
    void invalidate() {
        cached.set(null);
    }

    /**
     * @param pending          jobs waiting for a worker; null when Redis could not be asked
     * @param processing       jobs a worker has taken but not finished; null likewise
     * @param oldestPendingAgeSeconds how long the longest-waiting submission has been
     *                         waiting. The field that separates a busy queue from a stuck
     *                         one. Null when nothing is waiting
     * @param retrying         submissions that have been claimed more than once — each one
     *                         is a judgement that was interrupted and recovered
     * @param systemErrorsLastHour judgements that gave up. The pipeline's own error rate
     */
    public record Depth(
            Long pending,
            Long processing,
            Long oldestPendingAgeSeconds,
            long retrying,
            long systemErrorsLastHour) {
    }

    /**
     * The current state of the queue, recomputed at most every {@link #FRESH_FOR}.
     *
     * <p>Deliberately not synchronised. Two callers arriving together may both measure,
     * which costs one extra set of queries and settles immediately; a lock would make a
     * status endpoint able to block on another status endpoint, which is a worse failure
     * than a duplicated read.
     */
    public Depth measure() {
        Measurement previous = cached.get();
        if (previous != null && System.nanoTime() - previous.takenAtNanos() < FRESH_FOR.toNanos()) {
            return previous.depth();
        }
        Depth fresh = measureNow();
        cached.set(new Measurement(System.nanoTime(), fresh));
        return fresh;
    }

    private Depth measureNow() {
        Long pending = null;
        Long processing = null;
        try {
            pending = redis.opsForList().size(SubmissionQueue.PENDING);
            processing = redis.opsForList().size(SubmissionQueue.PROCESSING);
        } catch (RuntimeException e) {
            // Null rather than zero. Zero is a claim -- "nothing is queued" -- and it is the
            // wrong one to make when the truth is "we could not ask".
            log.warn("event=QUEUE_DEPTH_UNAVAILABLE reason={}", e.getClass().getSimpleName());
        }

        return new Depth(pending, processing, oldestPendingAgeSeconds(), retrying(), systemErrorsLastHour());
    }

    /**
     * The age of the longest-waiting submission, in seconds.
     *
     * <p>Uses the partial index on {@code (status, enqueued_at)} that already exists for the
     * recovery sweeper, so this is an index scan returning one row rather than an aggregate
     * over the table — it stays cheap however much history accumulates, which matters for a
     * query a dashboard polls.
     */
    private Long oldestPendingAgeSeconds() {
        try {
            return jdbc.queryForObject("""
                    SELECT EXTRACT(EPOCH FROM (now() - MIN(enqueued_at)))::bigint
                    FROM submissions
                    WHERE status = 'QUEUED' AND enqueued_at IS NOT NULL
                    """, Long.class);
        } catch (RuntimeException e) {
            log.warn("event=QUEUE_AGE_UNAVAILABLE reason={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Submissions currently being judged that have been claimed before.
     *
     * <p>Not an error count — the recovery path working is a feature — but a rising one
     * means judgements keep being interrupted, which is worth seeing before they exhaust
     * their attempts and become system errors.
     */
    private long retrying() {
        return count("""
                SELECT count(*) FROM submissions
                WHERE status IN ('QUEUED', 'RUNNING') AND attempts > 1
                """);
    }

    /**
     * The pipeline's own failure rate, over a window short enough to mean "now".
     *
     * <p>SYSTEM_ERROR is the one verdict that is never the submitter's fault: it means the
     * judge gave up. A handful is ordinary; a spike is an incident, and it is the most
     * direct signal this system produces that something is broken rather than merely slow.
     */
    private long systemErrorsLastHour() {
        return count("""
                SELECT count(*) FROM submissions
                WHERE status = 'SYSTEM_ERROR' AND finished_at > now() - interval '1 hour'
                """);
    }

    private long count(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            log.warn("event=QUEUE_STAT_UNAVAILABLE reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
