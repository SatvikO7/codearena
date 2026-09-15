package com.codearena.rating;

import com.codearena.audit.ActorType;
import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditEntityType;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finalises contests that have ended, without anybody asking.
 *
 * <h2>Why this is a query and not a timer</h2>
 * The obvious design is to schedule a job when a contest is created, to fire at its end
 * instant. It is also the design that loses contests. A scheduled callback lives in one
 * process's memory: restart the application, deploy a new version, or lose the instance the
 * timer was on, and the contest ends with nobody listening. Nothing then notices — the
 * contest simply stays unrated, and the first report of the problem is a competitor asking
 * why their rating did not move.
 *
 * <p>So this asks the database instead: <em>which contests have ended, are rated, and have no
 * finalisation?</em> That question cannot be wrong. It is correct after a restart, after a
 * crash, after two weeks of downtime, and on a brand-new instance that has never heard of the
 * contest. The index behind it ({@code ix_contests_awaiting_finalisation}) is partial over
 * exactly that predicate, so the usual answer — none — costs almost nothing.
 *
 * <h2>Startup</h2>
 * A sweep runs on {@link ApplicationReadyEvent} as well as on the timer. Without it, a
 * deployment that happened to come up just after a contest ended would leave the field
 * waiting for the first scheduled tick. Contests that ended while the process was down are
 * found by the same query as everything else; there is no separate recovery path, because a
 * recovery path that only runs after a crash is a path that is never tested.
 *
 * <h2>Running more than one instance</h2>
 * Safe, and needs no lock. Every instance may see the same contest and every instance may try
 * to finalise it; the conditional UPDATE in {@link ContestFinalizationRepository} means
 * exactly one wins and the others are told the work is done. Duplicate effort here is a few
 * wasted queries, not a double rating.
 *
 * <h2>One transaction per contest</h2>
 * Each contest is finalised in its own transaction, and a failure is caught here rather than
 * allowed to abort the sweep. Ten contests ending in the same minute must not be held hostage
 * by whichever one hits a problem — and the failed one is left unclaimed, so the next sweep
 * picks it up again.
 */
@Component
public class ContestFinalizationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ContestFinalizationSweeper.class);

    /**
     * How many contests one sweep will finalise.
     *
     * <p>Bounded so that a backlog — a long outage, or a first deployment against a database
     * full of finished contests — is worked through over several sweeps instead of in one
     * transaction-heavy burst that competes with live traffic for the connection pool.
     */
    private static final int BATCH_SIZE = 20;

    private final ContestFinalizationRepository finalizationRepository;
    private final ContestFinalizationService finalizationService;
    private final AuditService auditService;
    private final RatingMetrics metrics;
    private final Clock clock;
    private final boolean enabled;

    public ContestFinalizationSweeper(ContestFinalizationRepository finalizationRepository,
                                      ContestFinalizationService finalizationService,
                                      AuditService auditService,
                                      RatingMetrics metrics,
                                      Clock clock,
                                      @Value("${codearena.rating.auto-finalize:true}") boolean enabled) {
        this.finalizationRepository = finalizationRepository;
        this.finalizationService = finalizationService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Catches up on anything that ended while this instance was not running.
     *
     * <p>Ordered late so the rest of the context — the connection pool, Flyway, the metrics
     * registry — is genuinely ready before the first sweep touches the database.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void sweepOnStartup() {
        if (!enabled) {
            log.info("event=RATING_AUTO_FINALIZE_DISABLED "
                     + "detail=contests_will_only_be_finalised_on_administrator_request");
            return;
        }
        int finalized = sweep();
        if (finalized > 0) {
            log.info("event=RATING_STARTUP_SWEEP finalized={} "
                     + "detail=contests_that_ended_while_this_instance_was_down", finalized);
        }
    }

    @Scheduled(fixedDelayString = "${codearena.rating.sweep-interval-ms:60000}")
    public void sweepOnSchedule() {
        if (enabled) {
            sweep();
        }
    }

    /**
     * Finalises everything currently outstanding, up to {@link #BATCH_SIZE}.
     *
     * @return how many contests this sweep actually finalised
     */
    public int sweep() {
        Instant now = clock.instant();
        List<UUID> awaiting = finalizationRepository.findAwaitingFinalization(now, BATCH_SIZE);
        if (awaiting.isEmpty()) {
            return 0;
        }

        log.info("event=RATING_SWEEP_STARTED outstanding={}", awaiting.size());

        int finalized = 0;
        for (UUID contestId : awaiting) {
            if (finalizeQuietly(contestId)) {
                finalized++;
            }
        }
        return finalized;
    }

    /**
     * Finalises one contest, absorbing whatever goes wrong.
     *
     * <p>The catch is deliberately broad, and deliberately does not re-throw. A sweep is a
     * background loop with no caller to report to; an exception escaping here would kill the
     * iteration and leave every contest after this one unfinalised for no better reason than
     * their position in the list.
     *
     * <p>It is not, however, silent. A failure is logged at ERROR, counted on
     * {@code codearena.rating.finalizations{outcome="failed"}} so it can be alerted on, and
     * written to the audit log — because a rating that never happened is invisible from the
     * outside, and "nobody noticed" is how it stays that way. The contest itself is untouched:
     * the transaction rolled back, the claim went with it, and the next sweep tries again.
     */
    private boolean finalizeQuietly(UUID contestId) {
        try {
            ContestFinalizationService.Result result = finalizationService.finalize(
                    contestId, ContestFinalizationService.Source.SWEEPER);
            if (result.alreadyFinalized()) {
                // Another instance, or an administrator, got there first. Normal.
                return false;
            }
            log.info("event=RATING_SWEEP_FINALIZED contest={} rated={} participants={}",
                    contestId, result.rated(), result.ratedParticipants());
            return true;
        } catch (RuntimeException e) {
            metrics.finalizationFailed();
            log.error("event=RATING_SWEEP_FAILED contest={} error={} "
                      + "detail=contest_left_unfinalised_and_will_be_retried",
                    contestId, e.getClass().getSimpleName(), e);
            recordFailure(contestId, e);
            return false;
        }
    }

    /**
     * Records the failure in its own transaction.
     *
     * <p>Its own, because the finalisation's transaction has just rolled back — writing the
     * event inside it would roll the event back too, which is the one outcome that must not
     * happen. {@link AuditService#recordIndependently} exists for exactly this: an event about
     * a failure has to survive the failure.
     *
     * <p>The message is included, the stack trace is not. A message names the constraint or
     * the missing row; a stack trace in an audit row is noise that nobody queries and that can
     * carry internals into a table administrators read.
     */
    private void recordFailure(UUID contestId, RuntimeException cause) {
        auditService.recordIndependentlyFor(null, null, ActorType.SYSTEM,
                AuditAction.CONTEST_FINALIZE_FAILED, AuditOutcome.FAILURE,
                AuditEntityType.CONTEST, contestId.toString(),
                AuditMetadata.of()
                        .put("source", "sweeper")
                        .put("error", cause.getClass().getSimpleName())
                        .put("message", cause.getMessage())
                        .build());
    }
}
