package com.codearena.submission;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.events.SubmissionStreamRegistry;
import com.codearena.submission.dto.SubmissionStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Live submission status over Server-Sent Events.
 *
 * <h2>Why SSE rather than WebSockets</h2>
 * Every message in this feature travels server to client. A WebSocket would add a duplex
 * channel nobody writes to, a second authentication path — the cookie handshake does not
 * apply to a raw socket the way it does to a GET — and a protocol the browser cannot
 * reconnect on its own. SSE is a plain authenticated GET that carries the session cookie
 * like any other, and {@code EventSource} reconnects by itself.
 *
 * <h2>The contract</h2>
 * <ol>
 *   <li>The connection is authorised exactly as {@code GET /api/submissions/{id}} is.
 *       Somebody else's submission answers 404, so the stream is not a weaker door to the
 *       same data.</li>
 *   <li>A <b>snapshot is sent immediately</b>, read from PostgreSQL. This is what makes
 *       reconnects converge: a client that missed every event while disconnected still
 *       learns the truth the moment it comes back.</li>
 *   <li>If the submission is already finished, the snapshot is sent and the stream is closed
 *       at once. Holding it open would pin a thread waiting for an event that cannot come,
 *       because terminal states are immutable.</li>
 *   <li>Otherwise the stream stays open until the verdict arrives or the timeout expires.</li>
 * </ol>
 *
 * <h2>Delivery semantics</h2>
 * <b>At-least-once, with client-side convergence — not exactly-once.</b> The transport is
 * Redis Pub/Sub, which keeps nothing: an instance that is restarting misses the message
 * outright. Every payload therefore carries {@code updatedAt}, and the client discards
 * anything older than what it already has. A duplicate is idempotent, an out-of-order event
 * is dropped, and a lost event costs latency rather than correctness because the snapshot
 * and the fallback poll both re-read the database.
 */
@RestController
@Tag(name = "Submissions", description = "Submitting solutions and reading verdicts")
public class SubmissionStreamController {

    private static final Logger log = LoggerFactory.getLogger(SubmissionStreamController.class);

    /** SSE event carrying the first state, before any transition. */
    private static final String SNAPSHOT_EVENT = "snapshot";

    private final SubmissionService submissionService;
    private final SubmissionStreamRegistry registry;
    private final Duration streamTimeout;

    public SubmissionStreamController(
            SubmissionService submissionService,
            SubmissionStreamRegistry registry,
            @Value("${codearena.events.stream-timeout:PT5M}") Duration streamTimeout) {
        this.submissionService = submissionService;
        this.registry = registry;
        this.streamTimeout = streamTimeout;
    }

    @GetMapping("/api/submissions/{submissionId}/events")
    @Operation(summary = "Watch a submission",
               description = """
                       A Server-Sent Events stream of a submission's status.

                       **Events**
                       - `snapshot` — the current state, sent immediately on connect. Always first.
                       - `submission` — sent on each subsequent state change.

                       Both carry the same `SubmissionStatusResponse` body, which includes
                       `terminal` and `updatedAt`.

                       **Semantics.** At-least-once with client-side convergence, not
                       exactly-once: the transport keeps nothing, so an event can be missed or
                       repeated. Apply `updatedAt` monotonically — discard any event not newer
                       than the last one applied — and treat `terminal: true` as absorbing.

                       The server closes the stream once the verdict is final, and after
                       `codearena.events.stream-timeout` regardless. A closed stream is normal:
                       reconnect, or fall back to polling the detail endpoint.

                       Authorised identically to `GET /api/submissions/{id}`; somebody else's
                       submission answers 404.
                       """)
    @ApiResponses({
            // The schema is named explicitly: without it the generated document says a
            // stream exists but never says what a frame contains, which is the only part a
            // client author actually needs.
            @ApiResponse(responseCode = "200",
                         description = "An event stream. Each `snapshot` and `submission` "
                                     + "frame carries this body.",
                         content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                            schema = @Schema(implementation = SubmissionStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such submission, or it belongs to someone else",
                         content = @Content)
    })
    public ResponseEntity<SseEmitter> stream(@PathVariable UUID submissionId,
                                             @AuthenticationPrincipal AuthenticatedUser viewer) {

        SubmissionStatusResponse snapshot;
        try {
            // Authorise before opening anything.
            snapshot = submissionService.requireReadableStatus(
                    submissionId, viewer.getPublicId(), viewer.getRole());
        } catch (ResourceNotFoundException e) {
            // Answered here rather than by the global handler, and without a body.
            //
            // `EventSource` always sends `Accept: text/event-stream`. The handler's JSON
            // error envelope cannot be negotiated against that, so letting the exception
            // propagate turns every unauthorised watch into a 500 — wrong, and a worse
            // security signal than the 404 the REST endpoint gives. A bodiless 404 keeps
            // the status consistent with every other submission endpoint, and a browser
            // could not have read the envelope anyway: EventSource exposes no response body
            // on failure.
            return ResponseEntity.notFound().build();
        }

        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());

        // Already judged: answer with the truth and close. No registration, no held thread.
        if (snapshot.terminal()) {
            send(emitter, SNAPSHOT_EVENT, snapshot);
            emitter.complete();
            return ResponseEntity.ok(emitter);
        }

        if (!registry.register(submissionId, emitter)) {
            // At capacity. The client is not left guessing: it gets the current state and a
            // closed stream, and its polling fallback takes over.
            send(emitter, SNAPSHOT_EVENT, snapshot);
            emitter.complete();
            return ResponseEntity.ok(emitter);
        }

        // Sent after registration, so a transition happening in between is not missed: the
        // worst case is the client seeing the same state twice, which convergence discards.
        send(emitter, SNAPSHOT_EVENT, snapshot);

        log.info("event=SUBMISSION_STREAM_OPENED submission={} viewer={} open={}",
                submissionId, viewer.getPublicId(), registry.openConnectionCount());
        return ResponseEntity.ok(emitter);
    }

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException | IllegalStateException e) {
            // The client hung up between the request and the first write. Not an error
            // worth propagating, and not something a retry could fix.
            log.debug("event=SSE_INITIAL_SEND_FAILED reason={}", e.toString());
            emitter.completeWithError(e);
        }
    }
}
