package com.codearena.worker.observability;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What this worker is doing, in numbers.
 *
 * <h2>Bounded dimensions only</h2>
 * Every tag used here is a closed set — a language, a verdict, a category of failure. There
 * is no submission id, no problem id, no user and no contest anywhere in a label, and that
 * is a hard rule rather than a preference: a metric tagged with an identifier creates one
 * time series per value, so a busy evening would quietly turn the monitoring system into
 * the outage. Identifiers belong in logs, where they cost one line each and can be searched
 * by the submission id that ties the whole pipeline together.
 *
 * <p>Nothing derived from submitted code appears here either. These are counts and
 * durations; they say how much work happened and how it turned out, never what was in it.
 *
 * <h2>The two timers, and why they are separate</h2>
 * Queue wait and judging duration answer different questions and have different fixes. A
 * long wait with a short judgement means there is not enough judging capacity for the
 * arrival rate; a short wait with a long judgement means the work itself got slower. Summed
 * into one number, neither is visible — and "submissions are slow" would be true without
 * saying which lever to pull.
 */
@Component
public class JudgeMetrics {

    private final MeterRegistry registry;

    /** In-flight judgements on this worker. Read by the heartbeat as well as by the gauge. */
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong judged = new AtomicLong();
    private final AtomicLong infrastructureFailures = new AtomicLong();

    private final Timer queueWait;
    private final Timer judgeDuration;
    private final Counter claimsSkipped;
    private final Counter deferred;
    private final Counter malformedJobs;

    public JudgeMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.queueWait = Timer.builder("codearena.judge.queue.wait")
                .description("From a submission being accepted to a worker claiming it")
                .publishPercentileHistogram()
                .register(registry);

        this.judgeDuration = Timer.builder("codearena.judge.duration")
                .description("Compiling and running one submission against every test case")
                .publishPercentileHistogram()
                .register(registry);

        this.claimsSkipped = Counter.builder("codearena.judge.claims.skipped")
                .description("Redeliveries of a submission somebody else already has or finished")
                .register(registry);

        this.deferred = Counter.builder("codearena.judge.deferred")
                .description("Judgements abandoned because the execution service was unreachable")
                .register(registry);

        this.malformedJobs = Counter.builder("codearena.judge.jobs.malformed")
                .description("Queue entries that were not a submission id")
                .register(registry);

        Gauge.builder("codearena.judge.active", active, AtomicInteger::get)
                .description("Submissions this worker is judging right now")
                .register(registry);

        // Every verdict/language series, created at zero. A counter Micrometer has never
        // seen is absent from a scrape, so a worker that has judged nothing exposes no
        // verdict counter -- and a dashboard shows a gap where it should show a zero.
        // During an incident, "no submissions" and "the metric is missing" look the same
        // and mean opposite things.
        for (SubmissionStatus verdict : SubmissionStatus.values()) {
            if (!verdict.isTerminal()) {
                continue;   // QUEUED and RUNNING are states, never outcomes
            }
            for (Language language : Language.values()) {
                registry.counter("codearena.judge.submissions",
                        "verdict", verdict.name(), "language", language.name());
            }
        }
    }

    /** A submission has been claimed and is about to be judged. */
    public void judgementStarted() {
        active.incrementAndGet();
    }

    /**
     * A judgement finished, however it turned out.
     *
     * <p>The verdict is a tag rather than a counter of its own, so that "how many
     * submissions were judged" and "how did they turn out" are one query instead of a
     * number that has to be kept in step with the enum by hand.
     */
    public void judgementFinished(SubmissionStatus verdict, Language language, Duration took) {
        active.decrementAndGet();
        judged.incrementAndGet();
        if (verdict == SubmissionStatus.SYSTEM_ERROR) {
            infrastructureFailures.incrementAndGet();
        }
        registry.counter("codearena.judge.submissions",
                "verdict", verdict.name(),
                "language", language.name()).increment();
        judgeDuration.record(took);
    }

    /** A judgement that never produced a verdict, because the sandbox could not be reached. */
    public void judgementDeferred() {
        active.decrementAndGet();
        infrastructureFailures.incrementAndGet();
        deferred.increment();
    }

    /**
     * How long this submission waited between being accepted and being picked up.
     *
     * <p>Recorded from the row's creation time, which is the only clock both sides agree
     * on: the API server writes it and this worker reads it, so the number does not depend
     * on two machines' clocks matching, only on the database's.
     */
    public void recordQueueWait(Duration waited) {
        // A negative wait means the two clocks disagree, which is an observation about the
        // deployment rather than a measurement of the queue. Dropping it is better than
        // recording a nonsense sample that drags a percentile into fiction.
        if (!waited.isNegative()) {
            queueWait.record(waited);
        }
    }

    public void claimSkipped() {
        claimsSkipped.increment();
    }

    public void malformedJob() {
        malformedJobs.increment();
    }

    public int active() {
        return active.get();
    }

    public long judged() {
        return judged.get();
    }

    public long infrastructureFailures() {
        return infrastructureFailures.get();
    }
}
