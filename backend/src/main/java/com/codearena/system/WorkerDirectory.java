package com.codearena.system;

import com.codearena.shared.WorkerHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which judge workers exist, and which of them are still saying so.
 *
 * <p>Reads the records workers publish to Redis. The API server has no network route to a
 * worker — they sit on different compose networks, deliberately — so this is not a health
 * check that reaches out and asks. It is a register the workers write to and this reads,
 * which means a worker that has stopped writing is exactly as visible as one that has
 * stopped working, and that is the property worth having.
 *
 * <h2>A worker container that exists is not a worker that works</h2>
 * The distinction this makes possible is the point. {@code docker compose ps} will happily
 * report a worker as "Up" while its consumer threads are wedged, its Redis connection is
 * gone, or it is looping on an error. None of those stop the process; all of them stop the
 * judging. A heartbeat written by the same process that does the work cannot be green while
 * the work is not happening.
 */
@Component
public class WorkerDirectory {

    private static final Logger log = LoggerFactory.getLogger(WorkerDirectory.class);

    /**
     * Never scan an unbounded keyspace for a page an administrator is waiting on.
     *
     * <p>A deployment with more workers than this has bigger things to configure, and the
     * cap means a status endpoint cannot be made slow by the number of keys in Redis.
     */
    static final int MAX_WORKERS = 200;

    /**
     * How long a scan is reused.
     *
     * <p>Reading the register means a key scan plus a hash read per worker, and there
     * are three callers: the admin status view and one gauge per worker state on every
     * metrics scrape. Without this, one scrape would scan the keyspace three times to
     * answer questions that cannot differ within the same second.
     */
    private static final Duration FRESH_FOR = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final AtomicReference<Scan> cached = new AtomicReference<>();

    public WorkerDirectory(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    private record Scan(long takenAtNanos, List<WorkerHeartbeat.Snapshot> workers) {
    }

    /**
     * Forgets the cached scan.
     *
     * <p>Exists for tests, which write a record and read it back in the same breath.
     * Nothing in production calls it: a five-second-old view of which workers are alive
     * is a view of a fact that takes ten seconds to change.
     */
    void invalidate() {
        cached.set(null);
    }

    /**
     * Every worker that has reported recently enough to still have a record.
     *
     * <p>Ordered by identity so the admin view is stable between refreshes — a list that
     * reshuffles on every poll is unreadable, and the order Redis returns keys in is not
     * one anybody should depend on.
     *
     * <p>A Redis failure yields an empty list and a logged warning, never an exception. The
     * status endpoint exists to be read when things are wrong, and the reader can already
     * see from the Redis field that the answer is "we could not ask" rather than "there are
     * no workers".
     */
    public List<WorkerHeartbeat.Snapshot> workers() {
        Scan previous = cached.get();
        if (previous != null && System.nanoTime() - previous.takenAtNanos() < FRESH_FOR.toNanos()) {
            return previous.workers();
        }
        List<WorkerHeartbeat.Snapshot> fresh = scan();
        cached.set(new Scan(System.nanoTime(), fresh));
        return fresh;
    }

    private List<WorkerHeartbeat.Snapshot> scan() {
        try {
            Set<String> keys = redis.keys(WorkerHeartbeat.KEY_PATTERN);
            if (keys == null || keys.isEmpty()) {
                return List.of();
            }
            Instant now = clock.instant();
            return keys.stream()
                    .sorted()
                    .limit(MAX_WORKERS)
                    .map(this::read)
                    .filter(java.util.Objects::nonNull)
                    .map(fields -> WorkerHeartbeat.Snapshot.from(fields, now))
                    .sorted(Comparator.comparing(WorkerHeartbeat.Snapshot::id))
                    .toList();

        } catch (RuntimeException e) {
            log.warn("event=WORKER_DIRECTORY_UNAVAILABLE reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Map<String, String> read(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (raw.isEmpty()) {
            // The record expired between the scan and the read. Not an error; the worker is
            // simply gone, and reporting it as a worker with no fields would be worse.
            return null;
        }
        Map<String, String> fields = new HashMap<>(raw.size());
        raw.forEach((field, value) -> fields.put(String.valueOf(field), String.valueOf(value)));
        return fields;
    }
}
