package com.codearena.worker.queue;

import com.codearena.worker.config.WorkerProperties;
import com.codearena.worker.judge.JudgeRepository;
import com.codearena.worker.judge.JudgeRepository.ClaimedSubmission;
import com.codearena.worker.judge.JudgeRepository.JudgeResult;
import com.codearena.worker.judge.JudgeRepository.JudgeTestCase;
import com.codearena.worker.judge.JudgeService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pulls submissions off Redis and judges them.
 *
 * <h2>Delivery semantics</h2>
 * <b>At-least-once, made safe by an idempotent claim.</b> Exactly-once is not offered,
 * because it cannot be: a worker can die at any instant, including between taking a job and
 * recording that it did. Rather than pretend otherwise, the design makes a second delivery
 * harmless — {@link JudgeRepository#claim} succeeds for exactly one caller, and every other
 * delivery of the same submission finds it no longer QUEUED and drops the job.
 *
 * <h2>What happens at each crash point</h2>
 * <ul>
 *   <li><b>Before the claim.</b> The job sits in the processing list and the row stays
 *       QUEUED. The API's sweeper republishes it once the publication looks stale.</li>
 *   <li><b>After the claim, during execution.</b> The row is RUNNING with a lease that will
 *       expire. The sweeper returns it to the queue, or fails it as SYSTEM_ERROR once its
 *       attempts are exhausted.</li>
 *   <li><b>After the result is written.</b> The row is terminal. A redelivered job cannot
 *       claim it, so it is discarded — the verdict is never recomputed or overwritten.</li>
 * </ul>
 *
 * <h2>Retries</h2>
 * Only infrastructure failures are retried, and only by the sweeper returning an unfinished
 * submission to the queue. A wrong answer, a compilation error, a runtime error or a
 * timeout is a <em>result</em>: it is written once and never reattempted, because running
 * the same program against the same tests would produce the same verdict and simply burn a
 * container doing it.
 */
@Component
public class SubmissionConsumer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubmissionConsumer.class);

    static final String PENDING = "codearena:submissions:pending";
    static final String PROCESSING = "codearena:submissions:processing";

    /**
     * How long a blocking pop waits before looping.
     *
     * <p>Finite so the loop can notice a shutdown request. An unbounded block would leave
     * the thread parked in Redis while the JVM tried to stop.
     */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;
    private final JudgeRepository judgeRepository;
    private final JudgeService judgeService;
    private final WorkerProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService consumers;

    public SubmissionConsumer(StringRedisTemplate redis,
                              JudgeRepository judgeRepository,
                              JudgeService judgeService,
                              WorkerProperties properties) {
        this.redis = redis;
        this.judgeRepository = judgeRepository;
        this.judgeService = judgeService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int concurrency = properties.concurrency();
        consumers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("judge-consumer-" + thread.threadId());
            // Daemon threads so a stuck consumer cannot keep a shutting-down JVM alive.
            thread.setDaemon(true);
            return thread;
        });

        for (int i = 0; i < concurrency; i++) {
            consumers.submit(this::consumeLoop);
        }
        log.info("event=CONSUMERS_STARTED worker={} concurrency={}", properties.id(), concurrency);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumers != null) {
            consumers.shutdown();
            try {
                // Give an in-flight judgement a chance to finish and clean up its container
                // rather than orphaning it.
                if (!consumers.awaitTermination(30, TimeUnit.SECONDS)) {
                    consumers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                consumers.shutdownNow();
            }
        }
        log.info("event=CONSUMERS_STOPPED worker={}", properties.id());
    }

    private void consumeLoop() {
        while (running.get()) {
            String job = null;
            try {
                // BLMOVE: atomically take from pending and place on processing. The job is
                // never in neither list, so a crash between the two is not a lost job.
                job = redis.opsForList().move(
                        PENDING, org.springframework.data.redis.connection.RedisListCommands.Direction.RIGHT,
                        PROCESSING, org.springframework.data.redis.connection.RedisListCommands.Direction.LEFT,
                        POLL_TIMEOUT);

                if (job == null) {
                    continue;   // poll timeout, nothing waiting
                }
                processJob(job);
            } catch (Exception e) {
                // Never let one bad job kill a consumer thread: a dead thread silently
                // reduces capacity until somebody notices the throughput drop.
                log.error("event=CONSUMER_ERROR worker={} reason={}", properties.id(), e.toString(), e);
                sleepBriefly();
            } finally {
                if (job != null) {
                    // Remove exactly this job from processing, whatever the outcome. The
                    // durable state is the database row; leaving the marker behind would
                    // only accumulate rubbish in Redis.
                    redis.opsForList().remove(PROCESSING, 1, job);
                }
            }
        }
    }

    private void processJob(String job) {
        UUID submissionId;
        try {
            submissionId = UUID.fromString(job);
        } catch (IllegalArgumentException e) {
            log.error("event=MALFORMED_JOB payload_length={}", job.length());
            return;     // discarding is correct; no retry can make this parse
        }

        MDC.put("submissionId", submissionId.toString());
        MDC.put("workerId", properties.id());
        try {
            judge(submissionId);
        } finally {
            MDC.clear();
        }
    }

    private void judge(UUID submissionId) {
        ClaimedSubmission claimed = judgeRepository.claim(submissionId, properties.id()).orElse(null);
        if (claimed == null) {
            // Somebody else has it, or it is already finished. Both mean there is nothing
            // to do, and both are expected under at-least-once delivery.
            log.info("event=CLAIM_SKIPPED submission={} reason=not_queued", submissionId);
            return;
        }

        log.info("event=SUBMISSION_CLAIMED submission={} worker={} attempt={} language={}",
                submissionId, properties.id(), claimed.attempts(), claimed.language());

        long startedAt = System.nanoTime();
        List<JudgeTestCase> testCases = judgeRepository.loadTestCases(claimed.problemId());
        JudgeResult result = judgeService.judge(claimed, testCases);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        boolean recorded = judgeRepository.recordResult(claimed.id(), properties.id(), result);

        log.info("event=SUBMISSION_JUDGED submission={} worker={} status={} tests={}/{} durationMs={} recorded={}",
                submissionId, properties.id(), result.status(),
                result.testsPassed(), result.testsTotal(), durationMs, recorded);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }
}
