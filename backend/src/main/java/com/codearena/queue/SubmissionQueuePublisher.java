package com.codearena.queue;

import com.codearena.submission.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Moves submissions from the database into the Redis queue.
 *
 * <p>This is the publishing half of the outbox. Two things call it:
 * <ol>
 *   <li>{@link #onSubmissionCreated}, fired <em>after</em> the creating transaction
 *       commits. This is the fast path and gives a submission its sub-second start.</li>
 *   <li>{@link SubmissionRecoverySweeper}, which republishes anything the fast path failed
 *       to deliver.</li>
 * </ol>
 *
 * <p>The ordering matters and is the whole point. Publishing <em>before</em> commit would
 * let a worker pick up a submission that does not exist yet, or that is about to be rolled
 * back. Publishing after commit means the opposite failure — the row exists but the push
 * never happened — and that one is recoverable, because the row itself records that it was
 * never published. A failure here is therefore a delay, never a loss.
 */
@Component
public class SubmissionQueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(SubmissionQueuePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final SubmissionRepository submissionRepository;

    public SubmissionQueuePublisher(StringRedisTemplate redisTemplate,
                                    SubmissionRepository submissionRepository) {
        this.redisTemplate = redisTemplate;
        this.submissionRepository = submissionRepository;
    }

    /**
     * Publishes as soon as the submission is durably committed.
     *
     * <p>Runs in its own transaction because the original one has already finished. If
     * Redis is unavailable the exception is swallowed deliberately: the submission is
     * safely stored and the sweeper will retry, so failing the user's HTTP request over a
     * transient queue problem would be the wrong trade.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubmissionCreated(SubmissionCreatedEvent event) {
        try {
            publish(event.submissionPublicId());
        } catch (RuntimeException e) {
            log.warn("submission={} could not be published immediately; the recovery sweeper will retry. reason={}",
                    event.submissionPublicId(), e.toString());
        }
    }

    /**
     * Pushes the job and records the publication.
     *
     * <p>The push happens before the marker is written, never the other way round. If the
     * process dies between the two, the submission looks unpublished and gets republished —
     * a duplicate, which the worker's atomic claim absorbs. Writing the marker first would
     * risk the opposite: a submission marked published that never reached Redis, which
     * nothing would ever notice.
     */
    @Transactional
    public void publish(UUID submissionPublicId) {
        redisTemplate.opsForList().leftPush(SubmissionQueue.PENDING, submissionPublicId.toString());
        submissionRepository.markEnqueued(submissionPublicId, Instant.now());
        log.info("event=SUBMISSION_ENQUEUED submission={}", submissionPublicId);
    }

    /** Carries only the identifier; the listener re-reads anything else it needs. */
    public record SubmissionCreatedEvent(UUID submissionPublicId) {
    }
}
