package com.codearena.worker.queue;

import com.codearena.shared.SubmissionEvent;
import com.codearena.shared.SubmissionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Tells the API server that a submission moved.
 *
 * <p>Called only <strong>after</strong> the transition is committed to PostgreSQL. Announcing
 * first would let a browser show RUNNING for a submission that is still QUEUED in the
 * database, and then appear to go backwards on the next refresh.
 *
 * <p>Failures are logged and swallowed. The judgement is already durable, and a browser that
 * hears a second late — through its reconnect snapshot or its fallback poll — is a far better
 * outcome than a worker that abandons a finished submission because Redis hiccuped.
 */
@Component
public class WorkerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkerEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public WorkerEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void publish(UUID submissionId, SubmissionStatus status) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new SubmissionEvent(submissionId, status, Instant.now()));
            redis.convertAndSend(SubmissionEvent.CHANNEL, payload);
        } catch (Exception e) {
            log.warn("event=SUBMISSION_EVENT_PUBLISH_FAILED submission={} status={} reason={}",
                    submissionId, status, e.toString());
        }
    }
}
