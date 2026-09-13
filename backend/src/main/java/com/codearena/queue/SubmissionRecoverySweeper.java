package com.codearena.queue;

import com.codearena.events.SubmissionEventPublisher;
import com.codearena.submission.Submission;
import com.codearena.submission.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Repairs the pipeline after a crash.
 *
 * <p>This is the component that lets the outbox claim to be reliable. Without it, two
 * failures would strand a submission permanently:
 *
 * <ul>
 *   <li><b>The API died between commit and push.</b> The row exists in QUEUED with no
 *       {@code enqueuedAt}; no worker will ever hear about it. The sweeper republishes it.</li>
 *   <li><b>A worker died holding a claim.</b> The row sits in RUNNING forever, because the
 *       only process that was going to finish it is gone. The sweeper treats the claim as
 *       an expired lease and returns the submission to the queue.</li>
 * </ul>
 *
 * <p>It also republishes QUEUED submissions whose publication was confirmed long ago but
 * which are evidently still waiting — a job lost to a Redis restart, say. Redis is
 * configured with {@code appendonly yes}, so that should be rare, but "rare" is not
 * "never", and the recovery costs nothing when there is nothing to recover.
 *
 * <p>Every action it takes is idempotent and safe to duplicate: republishing a job that is
 * already queued produces a second delivery, which the worker's atomic claim discards.
 * Running several API instances therefore needs no distributed lock — the worst case is
 * duplicate messages, which the design already tolerates by construction.
 */
@Component
public class SubmissionRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(SubmissionRecoverySweeper.class);

    /** Bounded so one sweep cannot turn into an unbounded scan under a large backlog. */
    private static final int BATCH_SIZE = 100;

    private final SubmissionRepository submissionRepository;
    private final SubmissionQueuePublisher publisher;
    private final SubmissionEventPublisher events;
    private final Duration publishStaleAfter;
    private final Duration claimLease;
    private final int maxAttempts;

    public SubmissionRecoverySweeper(
            SubmissionRepository submissionRepository,
            SubmissionQueuePublisher publisher,
            SubmissionEventPublisher events,
            @Value("${codearena.queue.publish-stale-after:PT2M}") Duration publishStaleAfter,
            @Value("${codearena.queue.claim-lease:PT5M}") Duration claimLease,
            @Value("${codearena.queue.max-attempts:3}") int maxAttempts) {
        this.submissionRepository = submissionRepository;
        this.publisher = publisher;
        this.events = events;
        this.publishStaleAfter = publishStaleAfter;
        this.claimLease = claimLease;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${codearena.queue.sweep-interval-ms:30000}")
    public void sweep() {
        republishUnpublished();
        recoverExpiredClaims();
    }

    /** Delivers submissions the fast path failed to publish. */
    @Transactional
    public int republishUnpublished() {
        List<Submission> pending = submissionRepository.findUnpublished(
                Instant.now().minus(publishStaleAfter), PageRequest.of(0, BATCH_SIZE));

        for (Submission submission : pending) {
            log.warn("event=SUBMISSION_REPUBLISHED submission={} reason={}",
                    submission.getPublicId(),
                    submission.getEnqueuedAt() == null ? "never_published" : "publication_stale");
            publisher.publish(submission.getPublicId());
        }
        return pending.size();
    }

    /**
     * Reclaims submissions from workers that never came back.
     *
     * <p>Attempts are bounded on purpose. A submission that reliably kills whichever worker
     * takes it — by exhausting its memory, say — would otherwise cycle through the pool
     * forever, taking a worker down each time. After {@code maxAttempts} it is failed as
     * SYSTEM_ERROR, which is the honest verdict: the judge could not determine anything
     * about this code.
     */
    @Transactional
    public int recoverExpiredClaims() {
        List<Submission> expired = submissionRepository.findExpiredClaims(
                Instant.now().minus(claimLease), PageRequest.of(0, BATCH_SIZE));

        for (Submission submission : expired) {
            if (submission.getAttempts() >= maxAttempts) {
                log.error("event=SUBMISSION_ABANDONED submission={} attempts={} worker={}",
                        submission.getPublicId(), submission.getAttempts(), submission.getClaimedBy());
                submission.abandonAsSystemError(
                        "The judge could not complete this submission after %d attempts.".formatted(maxAttempts));
            } else {
                log.warn("event=SUBMISSION_LEASE_EXPIRED submission={} attempts={} worker={}",
                        submission.getPublicId(), submission.getAttempts(), submission.getClaimedBy());
                submission.returnToQueue();
            }
            // Announce the recovery so a watching browser sees the submission go back to
            // QUEUED, or reach SYSTEM_ERROR, instead of sitting on RUNNING until it gives up.
            // Published inside the transaction is acceptable here and nowhere else: the
            // subscriber re-reads the row, so a rolled-back sweep produces a redundant event
            // that resolves to the unchanged state rather than a wrong one.
            events.publish(submission.getPublicId(), submission.getStatus(), Instant.now());
        }
        return expired.size();
    }
}
