package com.codearena.events;

import com.codearena.shared.SubmissionEvent;
import com.codearena.submission.SubmissionService;
import com.codearena.submission.dto.SubmissionStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Turns a Redis notification into an SSE message.
 *
 * <p>The event itself is never forwarded to the browser. It says only "submission X moved
 * to Y"; this class then <strong>re-reads the submission from PostgreSQL</strong> and sends
 * whatever the database says. That single decision is what makes the whole design safe:
 *
 * <ul>
 *   <li>A stale or replayed event cannot show a browser something out of date, because the
 *       payload is built from the current row rather than from the message.</li>
 *   <li>A forged or corrupted message cannot inject a verdict, because nothing in it
 *       reaches the client.</li>
 *   <li>There is exactly one place that decides what a submission looks like over the wire,
 *       shared with the REST endpoint, so the two cannot drift apart.</li>
 * </ul>
 *
 * <p>The cost is a database read per event per watched submission. That is one indexed
 * primary-key lookup, and it buys correctness that no amount of care with the message
 * format would.
 */
@Component
public class SubmissionEventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventSubscriber.class);

    /** SSE event name. The client listens for this specifically. */
    public static final String EVENT_NAME = "submission";

    private final ObjectMapper objectMapper;
    private final SubmissionStreamRegistry registry;
    private final SubmissionService submissionService;

    public SubmissionEventSubscriber(ObjectMapper objectMapper,
                                     SubmissionStreamRegistry registry,
                                     SubmissionService submissionService) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.submissionService = submissionService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        SubmissionEvent event;
        try {
            event = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), SubmissionEvent.class);
        } catch (Exception e) {
            // Nothing to retry: a message that will not parse now will not parse later.
            log.warn("event=SUBMISSION_EVENT_UNREADABLE reason={}", e.toString());
            return;
        }

        // No watcher means no work. This is the common case — most submissions are judged
        // while nobody has a stream open — so it is checked before touching the database.
        if (!registry.hasWatchers(event.submissionId())) {
            return;
        }

        Optional<SubmissionStatusResponse> current = submissionService.findStatus(event.submissionId());
        if (current.isEmpty()) {
            // The row is gone. Close the streams rather than leaving them waiting.
            registry.completeAll(event.submissionId());
            return;
        }

        SubmissionStatusResponse status = current.get();
        registry.broadcast(event.submissionId(), EVENT_NAME, status);

        if (status.status().isTerminal()) {
            // Nothing further can happen to this submission, so holding the connection open
            // would pin a thread waiting for an event that cannot come.
            registry.completeAll(event.submissionId());
            log.info("event=SUBMISSION_STREAM_CLOSED submission={} status={}",
                    event.submissionId(), status.status());
        }
    }
}
