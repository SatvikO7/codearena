package com.codearena.events;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry's bookkeeping, and the shutdown guarantee in particular.
 *
 * <p>These are unit tests because the properties being asserted are arithmetic and ordering,
 * not behaviour that needs a servlet container. {@link SubmissionStreamIT} covers the parts
 * that do.
 */
class SubmissionStreamRegistryTest {

    private final SubmissionStreamRegistry registry = new SubmissionStreamRegistry(3);

    @Test
    void countsAnOpenStream() {
        assertThat(registry.register(UUID.randomUUID(), new SseEmitter())).isTrue();

        assertThat(registry.openConnectionCount()).isEqualTo(1);
    }

    @Test
    void refusesToRegisterPastTheCap() {
        for (int i = 0; i < 3; i++) {
            assertThat(registry.register(UUID.randomUUID(), new SseEmitter())).isTrue();
        }

        assertThat(registry.register(UUID.randomUUID(), new SseEmitter())).isFalse();
        assertThat(registry.openConnectionCount()).isEqualTo(3);
    }

    /**
     * A refused connection must not be counted, or the cap leaks downwards until the server
     * refuses everybody.
     */
    @Test
    void doesNotLeakTheCounterWhenAStreamCloses() {
        UUID submissionId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        registry.register(submissionId, emitter);

        registry.completeAll(submissionId);

        assertThat(registry.openConnectionCount()).isZero();
        assertThat(registry.hasWatchers(submissionId)).isFalse();
    }

    /**
     * The shutdown guarantee, part one: stopping actually closes what is open.
     *
     * <p>An SSE stream is an in-flight request that is designed never to finish on its own.
     * Left registered, it is something graceful shutdown will sit and wait for.
     */
    @Test
    void closesEveryOpenStreamOnShutdown() {
        AtomicInteger completions = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            registry.register(UUID.randomUUID(), new RecordingEmitter(completions));
        }
        registry.start();

        registry.stop();

        assertThat(completions.get()).isEqualTo(3);
        assertThat(registry.openConnectionCount()).isZero();
        assertThat(registry.isRunning()).isFalse();
    }

    /**
     * The shutdown guarantee, part two, and the one that actually matters: closing the
     * streams is only useful if it happens <em>before</em> the web server starts waiting for
     * in-flight requests to drain.
     *
     * <p>Lifecycle phases stop in descending order, so this must sit strictly above the web
     * server's graceful-shutdown phase. Asserting it against the real constant means a
     * Spring Boot upgrade that moves that phase fails here rather than silently
     * reintroducing a shutdown that stalls for the full grace period whenever anyone happens
     * to be watching a submission.
     */
    @Test
    void stopsBeforeTheWebServerWaitsForInFlightRequests() {
        assertThat(registry.getPhase())
                .isGreaterThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }

    /**
     * Counts {@code complete()} calls.
     *
     * <p>The obvious alternative — registering an {@code onCompletion} callback — does not
     * work outside a servlet container: {@code ResponseBodyEmitter} defers those callbacks
     * until an async handler is attached, so nothing fires and the test passes vacuously.
     * Overriding the method observes the call itself, which is the thing being asserted.
     */
    private static final class RecordingEmitter extends SseEmitter {

        private final AtomicInteger completions;

        private RecordingEmitter(AtomicInteger completions) {
            this.completions = completions;
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
            super.complete();
        }
    }

    @Test
    void shutdownIsIdempotent() {
        registry.register(UUID.randomUUID(), new SseEmitter());
        registry.start();

        registry.stop();
        registry.stop();

        assertThat(registry.openConnectionCount()).isZero();
    }
}
