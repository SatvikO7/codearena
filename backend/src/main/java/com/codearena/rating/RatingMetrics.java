package com.codearena.rating;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the rating system is doing, in numbers.
 *
 * <p>Same rule as everywhere else in this system: <b>every label is a closed set</b>. There is
 * no contest id and no user id in a tag here. A metric tagged with a contest would create a
 * time series per contest that ever ran, each frozen at its final value forever — and the
 * series would accumulate for as long as the deployment lived. Contest and user identifiers
 * belong in logs and in the audit log, both of which are searchable and neither of which
 * multiplies.
 *
 * <h2>Why finalisation failures get their own counter</h2>
 * A failed finalisation is invisible from the outside: the contest simply stays unrated, the
 * sweeper tries again later, and nobody notices until a competitor asks why their rating did
 * not move. A number somebody can alert on is the difference between finding that out from
 * monitoring and finding it out from a complaint.
 */
@Component
public class RatingMetrics {

    private final Counter finalizedRated;
    private final Counter finalizedUnrated;
    private final Counter skipped;
    private final Counter failed;
    private final Counter participantsRated;
    private final Timer finalizationDuration;
    private final Timer rankingQuery;

    /** Cumulative, so a dashboard can show the size of the rated population over time. */
    private final AtomicLong lastRatedFieldSize = new AtomicLong();

    public RatingMetrics(MeterRegistry registry) {
        this.finalizedRated = Counter.builder("codearena.rating.finalizations")
                .tag("outcome", "rated")
                .description("Contests finalised that moved ratings")
                .register(registry);
        this.finalizedUnrated = Counter.builder("codearena.rating.finalizations")
                .tag("outcome", "unrated")
                .description("Contests finalised that were not rated")
                .register(registry);
        this.skipped = Counter.builder("codearena.rating.finalizations")
                .tag("outcome", "already-finalized")
                .description("Finalisation attempts that found the work already done")
                .register(registry);
        this.failed = Counter.builder("codearena.rating.finalizations")
                .tag("outcome", "failed")
                .description("Finalisations that rolled back")
                .register(registry);

        this.participantsRated = Counter.builder("codearena.rating.participants")
                .description("Competitors who received a rating change")
                .register(registry);

        this.finalizationDuration = Timer.builder("codearena.rating.finalization.duration")
                .description("Computing and persisting one contest's rating changes")
                .publishPercentileHistogram()
                .register(registry);

        this.rankingQuery = Timer.builder("codearena.rating.ranking.query")
                .description("Serving one page of the global ranking")
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("codearena.rating.last.field.size", lastRatedFieldSize, AtomicLong::get)
                .description("Competitors rated by the most recent finalisation")
                .register(registry);
    }

    public void finalized(boolean rated, int participants, long durationMs) {
        if (rated) {
            finalizedRated.increment();
        } else {
            finalizedUnrated.increment();
        }
        participantsRated.increment(participants);
        lastRatedFieldSize.set(participants);
        finalizationDuration.record(Duration.ofMillis(durationMs));
    }

    public void finalizationSkipped() {
        skipped.increment();
    }

    public void finalizationFailed() {
        failed.increment();
    }

    public void rankingServed(Duration took) {
        rankingQuery.record(took);
    }
}
