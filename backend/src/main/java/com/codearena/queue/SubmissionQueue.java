package com.codearena.queue;

/**
 * Names the Redis keys the pipeline uses, so the API server and the worker cannot drift
 * apart over a typo.
 *
 * <p>Two lists, not one:
 * <ul>
 *   <li><b>pending</b> — jobs waiting for a worker.</li>
 *   <li><b>processing</b> — jobs a worker has taken but not yet finished with. A worker
 *       moves a job between the two atomically with {@code BLMOVE}, so there is no instant
 *       at which the job exists in neither list. Popping straight off the pending list
 *       would delete the job before the worker had done anything with it, and a crash in
 *       that window would lose it outright.</li>
 * </ul>
 *
 * <p>A job is nothing but a submission's UUID. The worker reads everything authoritative —
 * source, language, limits, test cases — from PostgreSQL. Putting the source in the message
 * would make Redis a second copy of the truth, and the two copies would eventually disagree.
 */
public final class SubmissionQueue {

    public static final String PENDING = "codearena:submissions:pending";
    public static final String PROCESSING = "codearena:submissions:processing";

    private SubmissionQueue() {
    }
}
