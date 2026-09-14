package com.codearena.worker.observability;

import com.codearena.shared.WorkerHeartbeat;
import com.codearena.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells the rest of the system that this worker exists and is working.
 *
 * <p>One small hash in Redis, rewritten every {@link WorkerHeartbeat#INTERVAL}. Redis is
 * already a dependency of both this process and the API server, which is the whole reason
 * it is the right place: a heartbeat that needed a port opened from the API network to the
 * worker network would have cost more in attack surface than it bought in visibility.
 *
 * <h2>A clean stop is not a fault</h2>
 * The record is deleted on shutdown. Without that, every deployment would leave a worker
 * that appears stale for five minutes, and an operator who sees stale workers after every
 * release quickly stops reading the field. A worker that <em>crashes</em> cannot delete
 * anything, which is exactly the case the staleness window is for.
 *
 * <h2>Why this hangs off ContextClosedEvent and not {@code @PreDestroy}</h2>
 * Because {@code @PreDestroy} is too late, and silently so. Spring closes a context in
 * three steps: it publishes {@link ContextClosedEvent}, then stops the lifecycle beans,
 * then destroys the beans. {@code LettuceConnectionFactory} is a lifecycle bean, so by the
 * time any {@code @PreDestroy} runs the Redis connection is already shut:
 *
 * <pre>
 *   event=DEREGISTER_FAILED reason=LettuceConnectionFactory has been STOPPED
 * </pre>
 *
 * <p>That is not a hypothetical. The first version of this class used {@code @PreDestroy},
 * every deployment left a worker looking stale for five minutes, and the only evidence was
 * one WARN line during shutdown that nobody was reading. The event fires before any of that
 * teardown, which is the only point at which a component can still use its dependencies to
 * say goodbye.
 *
 * <h2>Failures here are not failures of the worker</h2>
 * A heartbeat that cannot be written is logged and dropped. The alternative — letting a
 * Redis hiccup propagate — would mean the monitoring made the thing it monitors less
 * reliable, which is the wrong way round. The consequence of a missed beat is a worker that
 * looks stale for a few seconds while it carries on judging perfectly well; the consequence
 * of throwing would be a judging outage caused by a status page.
 */
@Component
public class WorkerHeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeatPublisher.class);

    private final StringRedisTemplate redis;
    private final WorkerProperties properties;
    private final JudgeMetrics metrics;
    private final String version;
    private final Instant startedAt = Instant.now();

    /** Set once shutdown begins, so the record says so before it disappears. */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public WorkerHeartbeatPublisher(StringRedisTemplate redis,
                                    WorkerProperties properties,
                                    JudgeMetrics metrics,
                                    @Value("${codearena.version:development}") String version) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
        this.version = version;
    }

    @Scheduled(fixedRateString = "#{T(com.codearena.shared.WorkerHeartbeat).INTERVAL.toMillis()}")
    public void publish() {
        try {
            String key = WorkerHeartbeat.keyFor(properties.id());

            Map<String, String> record = new LinkedHashMap<>();
            record.put(WorkerHeartbeat.FIELD_ID, properties.id());
            record.put(WorkerHeartbeat.FIELD_VERSION, version);
            record.put(WorkerHeartbeat.FIELD_STARTED_AT, Long.toString(startedAt.toEpochMilli()));
            record.put(WorkerHeartbeat.FIELD_LAST_SEEN_AT, Long.toString(System.currentTimeMillis()));
            record.put(WorkerHeartbeat.FIELD_CONCURRENCY, Integer.toString(properties.concurrency()));
            record.put(WorkerHeartbeat.FIELD_ACTIVE, Integer.toString(metrics.active()));
            record.put(WorkerHeartbeat.FIELD_JUDGED, Long.toString(metrics.judged()));
            record.put(WorkerHeartbeat.FIELD_FAILED, Long.toString(metrics.infrastructureFailures()));
            record.put(WorkerHeartbeat.FIELD_DRAINING, Boolean.toString(draining.get()));

            redis.opsForHash().putAll(key, record);
            // Refreshed on every beat, so the record outlives the last beat by a known
            // amount and no longer than that. A worker that stops beating disappears on its
            // own without anything having to notice and clean up.
            redis.expire(key, WorkerHeartbeat.RECORD_TTL);

        } catch (RuntimeException e) {
            log.warn("event=HEARTBEAT_FAILED worker={} reason={}", properties.id(), e.toString());
        }
    }

    /**
     * Removes the record, once the consumer has finished draining.
     *
     * <p>Ordered after {@code SubmissionConsumer}'s own shutdown listener, so the sequence an
     * operator sees is: the record flips to draining, stays that way while in-flight work
     * finishes, and then disappears. Deleting first would make a deliberate, orderly
     * departure indistinguishable from a process that fell over.
     *
     * <p>None of this affects whether a job is lost. That is settled by the claim lease and
     * the recovery sweeper, which have never depended on a worker shutting down tidily.
     */
    @EventListener(ContextClosedEvent.class)
    @Order(100)
    public void deregister() {
        draining.set(true);
        try {
            redis.delete(WorkerHeartbeat.keyFor(properties.id()));
            log.info("event=WORKER_DEREGISTERED worker={}", properties.id());
        } catch (RuntimeException e) {
            // The record expires on its own, so the worst case is that this worker looks
            // stale for a few minutes after a clean stop.
            log.warn("event=DEREGISTER_FAILED worker={} reason={}", properties.id(), e.toString());
        }
    }

    /** True once shutdown has begun. Used by the consumer to stop claiming new work. */
    public boolean draining() {
        return draining.get();
    }

    /**
     * Called at the start of shutdown, before the consumer drains.
     *
     * <p>Publishes immediately rather than waiting for the next scheduled beat. The
     * drain that follows can take half a minute, and the scheduler may already have been
     * shut down by then -- so without this, a worker would simply go quiet for the whole
     * of its departure and then vanish, which looks exactly like the failure this field
     * exists to distinguish it from.
     */
    public void beginDraining() {
        draining.set(true);
        publish();
    }
}
