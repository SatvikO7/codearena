package com.codearena.executor.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for the things an operator needs to see without reading logs.
 *
 * <p>Every counter here answers a question that matters when something is wrong: is the
 * daemon failing, are submissions being killed more than usual, is cleanup falling behind?
 * A rise in {@code cleanupFailures} or {@code reaped} means resources are leaking somewhere;
 * a rise in {@code dockerErrors} means the runtime is sick and the verdicts being produced
 * are SYSTEM_ERRORs rather than judgements.
 *
 * <p><b>Nothing here is derived from a submission.</b> No source code, no test data, no
 * identifiers, no user. These are counts of events, which is all an operator needs and the
 * most that can be exposed without turning a metrics endpoint into a disclosure channel.
 */
@Component
public class SandboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(SandboxMetrics.class);

    private final LongAdder executions = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder outOfMemory = new LongAdder();
    private final LongAdder outputLimit = new LongAdder();
    private final LongAdder fileLimit = new LongAdder();
    private final LongAdder dockerErrors = new LongAdder();
    private final LongAdder creationFailures = new LongAdder();
    private final LongAdder cleanupFailures = new LongAdder();
    private final LongAdder reaped = new LongAdder();

    public void executionStarted() {
        executions.increment();
    }

    public void timedOut() {
        timeouts.increment();
    }

    public void outOfMemory() {
        outOfMemory.increment();
    }

    public void outputLimitExceeded() {
        outputLimit.increment();
    }

    public void fileLimitExceeded() {
        fileLimit.increment();
    }

    public void dockerError() {
        dockerErrors.increment();
    }

    public void sandboxCreationFailed() {
        creationFailures.increment();
    }

    public void cleanupFailed() {
        cleanupFailures.increment();
    }

    public void reaped(int count) {
        reaped.add(count);
        // Logged as well as counted: a stray resource means something died unexpectedly,
        // and that is worth a line in the log rather than only a number on an endpoint.
        log.info("event=SANDBOX_REAPED count={}", count);
    }

    /** A snapshot, for the health endpoint and for tests. */
    public Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("executions", executions.sum());
        values.put("timeouts", timeouts.sum());
        values.put("outOfMemory", outOfMemory.sum());
        values.put("outputLimitExceeded", outputLimit.sum());
        values.put("fileLimitExceeded", fileLimit.sum());
        values.put("dockerErrors", dockerErrors.sum());
        values.put("sandboxCreationFailures", creationFailures.sum());
        values.put("cleanupFailures", cleanupFailures.sum());
        values.put("reaped", reaped.sum());
        return values;
    }
}
