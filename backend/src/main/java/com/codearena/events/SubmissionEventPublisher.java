package com.codearena.events;

import com.codearena.shared.SubmissionEvent;
import com.codearena.shared.SubmissionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Announces a submission state change to anyone watching.
 *
 * <p>Publication is <strong>best-effort and deliberately non-fatal</strong>. A failure here
 * is logged and swallowed, because the caller has already committed the authoritative state
 * to PostgreSQL: throwing would roll back a perfectly good judgement in order to report that
 * a browser will find out a second later instead of instantly. The client converges either
 * way, through the snapshot it receives on connect and through the bounded fallback poll.
 *
 * <p>Always called <em>after</em> the state is durable, never before. Publishing first would
 * let a client observe ACCEPTED and then, on refresh, read RUNNING back from the database.
 */
@Component
public class SubmissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SubmissionEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void publish(UUID submissionId, SubmissionStatus status, Instant occurredAt) {
        SubmissionEvent event = new SubmissionEvent(submissionId, status, occurredAt);
        try {
            redis.convertAndSend(SubmissionEvent.CHANNEL, objectMapper.writeValueAsString(event));
            log.debug("event=SUBMISSION_EVENT_PUBLISHED submission={} status={}", submissionId, status);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("event=SUBMISSION_EVENT_PUBLISH_FAILED submission={} status={} reason={}",
                    submissionId, status, e.toString());
        }
    }
}
