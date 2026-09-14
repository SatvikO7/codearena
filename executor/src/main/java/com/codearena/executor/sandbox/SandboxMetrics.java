package com.codearena.executor.sandbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters and timers for the things an operator needs to see without reading logs.
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
 * The same rule governs the tags: there are none. Every meter below is a single time series,
 * because there is no dimension of an execution that is both bounded and worth splitting by
 * — language would be, but this service is told a language and an image table, and tagging
 * by it would say more about what is running here than the numbers are worth.
 *
 * <h2>Two representations, one source</h2>
 * The values are kept in {@link LongAdder}s and mirrored into Micrometer. That is not
 * duplication for its own sake: {@link #snapshot()} is read by the health indicator and by
 * tests, which need the numbers as data rather than as an exposition format, while
 * Micrometer is what a scrape reads. Deriving the snapshot from the registry would make a
 * health endpoint depend on a monitoring dependency being present.
 *
 * <h2>Why the timers are here rather than on the worker</h2>
 * The worker already times a whole judgement. That number includes queueing inside this
 * service, the container start, and every test case; when it rises, it does not say which.
 * Sandbox startup is the interesting component because it is the part that is pure overhead
 * — the time between deciding to run a program and the program beginning to run.
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

    private final Counter executionCounter;
    private final Counter timeoutCounter;
    private final Counter outOfMemoryCounter;
    private final Counter outputLimitCounter;
    private final Counter fileLimitCounter;
    private final Counter dockerErrorCounter;
    private final Counter creationFailureCounter;
    private final Counter cleanupFailureCounter;
    private final Counter reapedCounter;

    private final Timer startupTimer;
    private final Timer executionTimer;

    public SandboxMetrics(MeterRegistry registry) {
        this.executionCounter = counter(registry, "codearena.sandbox.executions",
                "Programs started in a sandbox");
        this.timeoutCounter = counter(registry, "codearena.sandbox.timeouts",
                "Executions killed for exceeding their wall-clock budget");
        this.outOfMemoryCounter = counter(registry, "codearena.sandbox.out.of.memory",
                "Executions killed by the kernel for exceeding their memory cgroup");
        this.outputLimitCounter = counter(registry, "codearena.sandbox.output.limit",
                "Executions abandoned for producing more output than allowed");
        this.fileLimitCounter = counter(registry, "codearena.sandbox.file.limit",
                "Executions killed by RLIMIT_FSIZE");
        this.dockerErrorCounter = counter(registry, "codearena.sandbox.docker.errors",
                "Failures from the container runtime itself, not from submitted code");
        this.creationFailureCounter = counter(registry, "codearena.sandbox.creation.failures",
                "Workspaces that could not be created");
        this.cleanupFailureCounter = counter(registry, "codearena.sandbox.cleanup.failures",
                "Resources that could not be removed, and so leaked until reaped");
        this.reapedCounter = counter(registry, "codearena.sandbox.reaped",
                "Stray containers or volumes removed by the reaper");

        this.startupTimer = Timer.builder("codearena.sandbox.startup")
                .description("Preparing a workspace: the overhead before a program can run")
                .publishPercentileHistogram()
                .register(registry);
        this.executionTimer = Timer.builder("codearena.sandbox.execution")
                .description("One program's run, from container start to exit or kill")
                .publishPercentileHistogram()
                .register(registry);
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public void executionStarted() {
        executions.increment();
        executionCounter.increment();
    }

    public void timedOut() {
        timeouts.increment();
        timeoutCounter.increment();
    }

    public void outOfMemory() {
        outOfMemory.increment();
        outOfMemoryCounter.increment();
    }

    public void outputLimitExceeded() {
        outputLimit.increment();
        outputLimitCounter.increment();
    }

    public void fileLimitExceeded() {
        fileLimit.increment();
        fileLimitCounter.increment();
    }

    public void dockerError() {
        dockerErrors.increment();
        dockerErrorCounter.increment();
    }

    public void sandboxCreationFailed() {
        creationFailures.increment();
        creationFailureCounter.increment();
    }

    public void cleanupFailed() {
        cleanupFailures.increment();
        cleanupFailureCounter.increment();
    }

    public void reaped(int count) {
        reaped.add(count);
        reapedCounter.increment(count);
        // Logged as well as counted: a stray resource means something died unexpectedly,
        // and that is worth a line in the log rather than only a number on an endpoint.
        log.info("event=SANDBOX_REAPED count={}", count);
    }

    /** How long a workspace took to prepare. Pure overhead, and the first thing to watch. */
    public void recordStartup(Duration took) {
        startupTimer.record(took);
    }

    /** How long one program ran, whatever ended it. */
    public void recordExecution(Duration took) {
        executionTimer.record(took);
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
