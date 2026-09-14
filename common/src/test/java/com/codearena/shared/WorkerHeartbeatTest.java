package com.codearena.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alive / stale / gone distinction, which is the whole point of the heartbeat.
 *
 * <p>A container health check can say a worker process exists. It cannot say the process is
 * still judging — a wedged consumer thread, a lost Redis connection or an error loop all
 * leave the process up and the work undone. These tests pin the reading that makes that
 * difference visible, and the lenience that keeps the reading working when something is
 * already wrong.
 */
class WorkerHeartbeatTest {

    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    private static Map<String, String> record(Instant lastSeen) {
        Map<String, String> fields = new HashMap<>();
        fields.put(WorkerHeartbeat.FIELD_ID, "worker-1");
        fields.put(WorkerHeartbeat.FIELD_VERSION, "1.2.3");
        fields.put(WorkerHeartbeat.FIELD_STARTED_AT, Long.toString(NOW.minusSeconds(600).toEpochMilli()));
        fields.put(WorkerHeartbeat.FIELD_LAST_SEEN_AT, Long.toString(lastSeen.toEpochMilli()));
        fields.put(WorkerHeartbeat.FIELD_CONCURRENCY, "2");
        fields.put(WorkerHeartbeat.FIELD_ACTIVE, "1");
        fields.put(WorkerHeartbeat.FIELD_JUDGED, "417");
        fields.put(WorkerHeartbeat.FIELD_FAILED, "3");
        fields.put(WorkerHeartbeat.FIELD_DRAINING, "false");
        return fields;
    }

    @Test
    void readsAWorkerThatJustReported() {
        WorkerHeartbeat.Snapshot snapshot =
                WorkerHeartbeat.Snapshot.from(record(NOW.minusSeconds(3)), NOW);

        assertThat(snapshot.healthy()).isTrue();
        assertThat(snapshot.id()).isEqualTo("worker-1");
        assertThat(snapshot.version()).isEqualTo("1.2.3");
        assertThat(snapshot.concurrency()).isEqualTo(2);
        assertThat(snapshot.active()).isEqualTo(1);
        assertThat(snapshot.judged()).isEqualTo(417);
        assertThat(snapshot.failed()).isEqualTo(3);
        assertThat(snapshot.draining()).isFalse();
    }

    /**
     * One missed beat is not a dead worker. A status page that cries wolf at the first
     * slow scheduler tick gets ignored, and an ignored status page is worse than none.
     */
    @Test
    void toleratesAMissedBeat() {
        Instant oneBeatAgo = NOW.minus(WorkerHeartbeat.INTERVAL).minusSeconds(2);

        assertThat(WorkerHeartbeat.Snapshot.from(record(oneBeatAgo), NOW).healthy()).isTrue();
    }

    /** Three missed beats is. */
    @Test
    void reportsAWorkerThatHasStoppedSpeaking() {
        Instant tooLongAgo = NOW.minus(WorkerHeartbeat.STALE_AFTER).minusSeconds(1);

        assertThat(WorkerHeartbeat.Snapshot.from(record(tooLongAgo), NOW).healthy()).isFalse();
    }

    /** Exactly at the threshold counts as healthy, so the boundary is not ambiguous. */
    @Test
    void treatsTheThresholdItselfAsHealthy() {
        assertThat(WorkerHeartbeat.Snapshot.from(
                record(NOW.minus(WorkerHeartbeat.STALE_AFTER)), NOW).healthy()).isTrue();
    }

    /**
     * The record must outlive the staleness threshold by a long way. Were they equal, a
     * worker that died would vanish rather than appear stale — and "no workers are
     * registered" is a far weaker signal than "this worker stopped reporting four minutes
     * ago", which is the one an operator can act on.
     */
    @Test
    void keepsARecordLongEnoughForStalenessToBeVisible() {
        assertThat(WorkerHeartbeat.RECORD_TTL).isGreaterThan(WorkerHeartbeat.STALE_AFTER.multipliedBy(4));
        assertThat(WorkerHeartbeat.STALE_AFTER).isGreaterThan(WorkerHeartbeat.INTERVAL.multipliedBy(3));
    }

    /**
     * A worker running an older build may not write every field this reader expects. A
     * status page that throws over one missing value is broken exactly when it is needed,
     * so the reading is lenient by design.
     */
    @Test
    void readsAnIncompleteRecordRatherThanFailing() {
        Map<String, String> partial = new HashMap<>();
        partial.put(WorkerHeartbeat.FIELD_LAST_SEEN_AT, Long.toString(NOW.toEpochMilli()));

        WorkerHeartbeat.Snapshot snapshot = WorkerHeartbeat.Snapshot.from(partial, NOW);

        assertThat(snapshot.healthy()).isTrue();
        assertThat(snapshot.id()).isEqualTo("unknown");
        assertThat(snapshot.judged()).isZero();
        assertThat(snapshot.startedAt()).isNull();
    }

    /** Nonsense in a field must not become an exception, or a corrupt record hides the rest. */
    @Test
    void readsAnUnparsableRecordRatherThanFailing() {
        Map<String, String> broken = record(NOW);
        broken.put(WorkerHeartbeat.FIELD_JUDGED, "not-a-number");
        broken.put(WorkerHeartbeat.FIELD_STARTED_AT, "");

        WorkerHeartbeat.Snapshot snapshot = WorkerHeartbeat.Snapshot.from(broken, NOW);

        assertThat(snapshot.judged()).isZero();
        assertThat(snapshot.startedAt()).isNull();
    }

    /** No record at all is not healthy, whatever else is missing. */
    @Test
    void treatsAnEmptyRecordAsUnhealthy() {
        assertThat(WorkerHeartbeat.Snapshot.from(Map.of(), NOW).healthy()).isFalse();
    }

    @Test
    void namesOneKeyPerWorker() {
        assertThat(WorkerHeartbeat.keyFor("worker-7")).isEqualTo("codearena:workers:worker-7");
        assertThat(WorkerHeartbeat.keyFor("worker-7")).startsWith(WorkerHeartbeat.KEY_PREFIX);
    }
}
