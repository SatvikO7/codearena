package com.codearena.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the open SSE connections and fans events out to them.
 *
 * <p>One submission can have several watchers — the same person with the solve page and the
 * history page open in two tabs — so this is a map of submission id to a list of emitters,
 * not a map to a single one.
 *
 * <p><b>Connections are capped.</b> Each open SSE stream pins a servlet container thread for
 * its lifetime, so an unbounded number of them is a denial-of-service vector that needs no
 * authentication beyond an account: open a few thousand streams and the API stops answering
 * anything. The cap is enforced here rather than trusted to the client, and a rejected
 * connection is not an error the user sees — the frontend falls back to polling, which is
 * why the fallback exists.
 *
 * <p><b>Shutdown.</b> An SSE stream is an in-flight request that is designed never to finish
 * on its own, so graceful shutdown would wait the full grace period for every open one. This
 * is a {@link SmartLifecycle} at a phase that stops before the web server's graceful-shutdown
 * lifecycle, and closes the streams there. That is safe because the verdict lives in
 * PostgreSQL rather than in the connection: the browser reconnects and is handed a snapshot.
 */
@Component
public class SubmissionStreamRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SubmissionStreamRegistry.class);

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger openConnections = new AtomicInteger();
    private volatile boolean running;
    private final int maxConnections;

    public SubmissionStreamRegistry(
            @Value("${codearena.events.max-sse-connections:500}") int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * Registers an emitter, or refuses if the server is already at capacity.
     *
     * @return false when the cap is reached; the caller should serve the snapshot and close
     */
    public boolean register(UUID submissionId, SseEmitter emitter) {
        if (openConnections.get() >= maxConnections) {
            log.warn("event=SSE_REJECTED reason=at_capacity open={} max={}",
                    openConnections.get(), maxConnections);
            return false;
        }
        openConnections.incrementAndGet();
        emitters.computeIfAbsent(submissionId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        // Every terminal path for a connection has to decrement the counter and drop the
        // emitter, or the cap leaks until the server refuses everyone.
        emitter.onCompletion(() -> remove(submissionId, emitter));
        emitter.onTimeout(() -> {
            // A timeout is normal and expected: streams are deliberately short-lived so a
            // forgotten tab cannot hold a thread for ever. The client reconnects.
            emitter.complete();
            remove(submissionId, emitter);
        });
        emitter.onError(throwable -> remove(submissionId, emitter));

        log.debug("event=SSE_REGISTERED submission={} open={}", submissionId, openConnections.get());
        return true;
    }

    /**
     * Sends a payload to everyone watching this submission.
     *
     * <p>A failed send means the client has gone away — a closed tab, a dropped network — so
     * the emitter is completed and dropped rather than retried. There is nothing to retry
     * to.
     */
    public void broadcast(UUID submissionId, String eventName, Object payload) {
        List<SseEmitter> watchers = emitters.get(submissionId);
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : watchers) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("event=SSE_SEND_FAILED submission={} reason={}", submissionId, e.toString());
                remove(submissionId, emitter);
                safeComplete(emitter);
            }
        }
    }

    /**
     * Closes every stream for a submission that has reached a verdict.
     *
     * <p>Holding the connection open afterwards would pin a thread waiting for an event that
     * can never come: terminal states are immutable, so there is nothing further to say.
     */
    public void completeAll(UUID submissionId) {
        List<SseEmitter> watchers = emitters.remove(submissionId);
        if (watchers == null) {
            return;
        }
        for (SseEmitter emitter : watchers) {
            openConnections.decrementAndGet();
            safeComplete(emitter);
        }
        log.debug("event=SSE_COMPLETED submission={} closed={}", submissionId, watchers.size());
    }

    /**
     * Closes every open stream.
     *
     * <p>Runs at {@link #getPhase()}, which is the highest phase there is, so it happens
     * before {@code WebServerGracefulShutdownLifecycle} begins waiting for in-flight
     * requests — by which point there are none of ours left to wait for.
     */
    @Override
    public void stop() {
        running = false;
        int closed = 0;
        for (UUID submissionId : Set.copyOf(emitters.keySet())) {
            List<SseEmitter> watchers = emitters.remove(submissionId);
            if (watchers == null) {
                continue;
            }
            for (SseEmitter emitter : watchers) {
                openConnections.decrementAndGet();
                safeComplete(emitter);
                closed++;
            }
        }
        if (closed > 0) {
            log.info("event=SSE_SHUTDOWN closed={}", closed);
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops first. Phases stop in descending order, and the web server's graceful shutdown
     * sits at {@code DEFAULT_PHASE - 1024}, so anything above that closes before it waits.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public int openConnectionCount() {
        return openConnections.get();
    }

    public boolean hasWatchers(UUID submissionId) {
        List<SseEmitter> watchers = emitters.get(submissionId);
        return watchers != null && !watchers.isEmpty();
    }

    private void remove(UUID submissionId, SseEmitter emitter) {
        List<SseEmitter> watchers = emitters.get(submissionId);
        if (watchers != null && watchers.remove(emitter)) {
            openConnections.decrementAndGet();
            if (watchers.isEmpty()) {
                emitters.remove(submissionId, watchers);
            }
        }
    }

    /** Completing an already-dead emitter throws; that is not worth propagating. */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.trace("emitter was already closed: {}", e.toString());
        }
    }
}
