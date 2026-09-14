package com.codearena.shared;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * What a judge worker publishes about itself, and where.
 *
 * <p>Phase 8 deliberately left this out and said so: the API server sits on a different
 * network from the worker and cannot reach it, so "is a worker alive" was answered only
 * indirectly, by watching the queue. That was honest but thin — a pending count climbing
 * while processing stays flat is what a stopped worker looks like, and also what a slow
 * problem looks like.
 *
 * <p>This is the direct answer, and it travels the way everything else between these two
 * processes travels: through Redis, which both already depend on. No new service, no port
 * opened between networks, no HTTP call from the API to the worker.
 *
 * <h2>Alive, stale, gone</h2>
 * Three states, not two, because "no heartbeat" has two very different causes:
 *
 * <ul>
 *   <li><b>Healthy</b> — beat within {@link #STALE_AFTER}.</li>
 *   <li><b>Stale</b> — the record is still there but nobody has touched it. The worker
 *       process exists as far as anyone knows and has stopped saying so: the interesting
 *       case, and the one an operator must be able to see.</li>
 *   <li><b>Gone</b> — the record expired ({@link #RECORD_TTL}) or was removed. A worker
 *       that shuts down cleanly deletes its own record, so a planned stop is never
 *       reported as a fault.</li>
 * </ul>
 *
 * <p>The TTL is far longer than the staleness threshold on purpose. Were they equal, a
 * worker that died would vanish rather than appear stale, and "no workers are registered"
 * is a much weaker signal than "this worker stopped reporting four minutes ago".
 *
 * <h2>What is deliberately not in here</h2>
 * No executor token, no database URL, no filesystem path, no Docker detail, no submission
 * content. The record is read by an admin endpoint and would be visible to anyone who could
 * read Redis; it holds counts and timestamps, which is what an operator needs and the most
 * that can be published without turning a status page into a disclosure channel.
 */
public final class WorkerHeartbeat {

    /** One Redis hash per worker: {@code codearena:workers:{id}}. */
    public static final String KEY_PREFIX = "codearena:workers:";

    /** Matches every worker's key, for the admin view's scan. */
    public static final String KEY_PATTERN = KEY_PREFIX + "*";

    /** How often a worker writes its record. */
    public static final Duration INTERVAL = Duration.ofSeconds(10);

    /**
     * A worker that has not written within this is reported stale.
     *
     * <p>Three missed beats rather than one: a single slow scheduler tick, or a moment of
     * Redis latency, is not a dead worker, and a status page that cries wolf gets ignored.
     */
    public static final Duration STALE_AFTER = Duration.ofSeconds(35);

    /** How long a record outlives its last beat before the worker is considered gone. */
    public static final Duration RECORD_TTL = Duration.ofMinutes(5);

    // Hash fields. Named here so the writer and the reader cannot drift over a typo.
    public static final String FIELD_ID = "id";
    public static final String FIELD_VERSION = "version";
    public static final String FIELD_STARTED_AT = "startedAt";
    public static final String FIELD_LAST_SEEN_AT = "lastSeenAt";
    public static final String FIELD_CONCURRENCY = "concurrency";
    public static final String FIELD_ACTIVE = "active";
    public static final String FIELD_JUDGED = "judged";
    public static final String FIELD_FAILED = "failed";
    public static final String FIELD_DRAINING = "draining";

    private WorkerHeartbeat() {
    }

    public static String keyFor(String workerId) {
        return KEY_PREFIX + workerId;
    }

    /**
     * One worker as the admin view sees it.
     *
     * @param id          the worker's stable identity, which under compose is its hostname
     * @param version     build identifier, so a half-finished rollout is visible
     * @param startedAt   when this process began; a worker that keeps restarting shows here
     * @param lastSeenAt  the last beat
     * @param concurrency how many submissions it judges at once
     * @param active      how many it is judging right now
     * @param judged      submissions completed since it started
     * @param failed      of those, how many ended in an infrastructure failure
     * @param draining    true once it has been asked to stop and is finishing its work
     * @param healthy     derived: whether the last beat is within {@link #STALE_AFTER}
     */
    public record Snapshot(
            String id,
            String version,
            Instant startedAt,
            Instant lastSeenAt,
            int concurrency,
            int active,
            long judged,
            long failed,
            boolean draining,
            boolean healthy) {

        /**
         * Reads a record, tolerating fields that are missing or unparsable.
         *
         * <p>Lenient on purpose. A worker running an older build may not write a field this
         * one expects, and a status page that throws when a single value is absent is worse
         * than one that shows a zero — the whole point of the page is to work when something
         * is wrong.
         */
        public static Snapshot from(Map<String, String> fields, Instant now) {
            Instant lastSeen = instant(fields.get(FIELD_LAST_SEEN_AT));
            boolean healthy = lastSeen != null
                    && !lastSeen.isBefore(now.minus(STALE_AFTER));

            return new Snapshot(
                    fields.getOrDefault(FIELD_ID, "unknown"),
                    fields.getOrDefault(FIELD_VERSION, "unknown"),
                    instant(fields.get(FIELD_STARTED_AT)),
                    lastSeen,
                    (int) number(fields.get(FIELD_CONCURRENCY)),
                    (int) number(fields.get(FIELD_ACTIVE)),
                    number(fields.get(FIELD_JUDGED)),
                    number(fields.get(FIELD_FAILED)),
                    Boolean.parseBoolean(fields.get(FIELD_DRAINING)),
                    healthy);
        }

        private static Instant instant(String value) {
            long millis = number(value);
            return millis > 0 ? Instant.ofEpochMilli(millis) : null;
        }

        private static long number(String value) {
            if (value == null || value.isBlank()) {
                return 0;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
