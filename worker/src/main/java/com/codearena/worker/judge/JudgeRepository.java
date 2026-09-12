package com.codearena.worker.judge;

import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The worker's view of the database.
 *
 * <p>Deliberately JDBC rather than the JPA entities the API server uses. The worker issues
 * four statements in total, one of which is an atomic claim that has to be written as SQL
 * anyway. Sharing an entity model would mean a shared module containing the whole domain,
 * two deployables locked to one persistence mapping, and lazy-loading hazards in a process
 * that runs outside a request scope. Explicit SQL is smaller, faster and easier to reason
 * about here; the two services share only the enums they must agree on.
 *
 * <p>Every statement is parameterised. Nothing from a submission is ever concatenated into
 * SQL.
 */
@Repository
public class JudgeRepository {

    private static final Logger log = LoggerFactory.getLogger(JudgeRepository.class);

    private final JdbcTemplate jdbc;

    public JudgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes exclusive ownership of a submission, or reports that somebody else has it.
     *
     * <p>This is the concurrency control for the whole pipeline, and it is one statement on
     * purpose. The {@code status = 'QUEUED'} predicate is evaluated inside the same
     * {@code UPDATE} that changes it, so PostgreSQL's row lock decides the winner: of two
     * workers racing for the same submission, exactly one sees a row returned and the other
     * sees none. Reading the row first and updating it afterwards — the obvious
     * implementation — would let both read QUEUED and both proceed.
     *
     * <p>{@code RETURNING} makes it a single round trip and guarantees the data returned
     * belongs to the claim that just succeeded.
     *
     * @return the claimed work, or empty when the submission was already taken or is no
     *         longer queued — which is exactly what makes duplicate queue delivery harmless
     */
    public Optional<ClaimedSubmission> claim(UUID submissionPublicId, String workerId) {
        List<ClaimedSubmission> claimed = jdbc.query("""
                UPDATE submissions s
                SET status = 'RUNNING',
                    claimed_by = ?,
                    claimed_at = now(),
                    started_at = COALESCE(s.started_at, now()),
                    attempts = s.attempts + 1,
                    updated_at = now()
                FROM problems p
                WHERE s.problem_id = p.id
                  AND s.public_id = ?
                  AND s.status = 'QUEUED'
                RETURNING s.id, s.public_id, s.language, s.source_code,
                          s.attempts, p.id AS problem_id,
                          p.time_limit_ms, p.memory_limit_mb
                """,
                (rs, rowNum) -> new ClaimedSubmission(
                        rs.getLong("id"),
                        rs.getObject("public_id", UUID.class),
                        Language.valueOf(rs.getString("language")),
                        rs.getString("source_code"),
                        rs.getInt("attempts"),
                        rs.getLong("problem_id"),
                        rs.getInt("time_limit_ms"),
                        rs.getInt("memory_limit_mb")),
                workerId, submissionPublicId);

        return claimed.stream().findFirst();
    }

    /**
     * Loads a problem's test cases in judging order.
     *
     * <p>This data is the answer key. It is read here, in the worker, and the expected
     * output never leaves this process: it is compared against the program's stdout in
     * Java, and is never written into the sandbox or into any response.
     */
    public List<JudgeTestCase> loadTestCases(long problemId) {
        return jdbc.query("""
                SELECT position, input, expected_output, weight
                FROM problem_test_cases
                WHERE problem_id = ?
                ORDER BY position ASC
                """,
                (rs, rowNum) -> new JudgeTestCase(
                        rs.getInt("position"),
                        rs.getString("input"),
                        rs.getString("expected_output"),
                        rs.getInt("weight")),
                problemId);
    }

    /**
     * Writes a terminal verdict.
     *
     * <p>Guarded by {@code status = 'RUNNING'} and by the claim holder's own id. A worker
     * whose lease expired — whose submission the sweeper has already returned to the queue
     * and which another worker may have finished — updates nothing, because neither
     * predicate holds any more. That is what stops a slow worker from overwriting a newer
     * result with a stale one.
     *
     * @return true when this worker's result was the one recorded
     */
    public boolean recordResult(long submissionId, String workerId, JudgeResult result) {
        int updated = jdbc.update("""
                UPDATE submissions
                SET status = ?,
                    tests_total = ?,
                    tests_passed = ?,
                    failed_test_index = ?,
                    runtime_ms = ?,
                    memory_kb = ?,
                    error_message = ?,
                    finished_at = ?,
                    updated_at = now()
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND claimed_by = ?
                """,
                result.status().name(),
                result.testsTotal(),
                result.testsPassed(),
                result.failedTestIndex(),
                result.runtimeMs(),
                result.memoryKb(),
                result.errorMessage(),
                Timestamp.from(Instant.now()),
                submissionId,
                workerId);

        if (updated == 0) {
            log.warn("event=RESULT_DISCARDED submission={} worker={} reason=claim_no_longer_held",
                    submissionId, workerId);
        }
        return updated == 1;
    }

    /** Work handed to the judge after a successful claim. */
    public record ClaimedSubmission(
            long id,
            UUID publicId,
            Language language,
            String sourceCode,
            int attempts,
            long problemId,
            int timeLimitMs,
            int memoryLimitMb) {

        /** Never prints the source. */
        @Override
        public String toString() {
            return "ClaimedSubmission{publicId=%s, language=%s, attempts=%d}"
                    .formatted(publicId, language, attempts);
        }
    }

    /** One test case. Confidential: neither field may leave the worker. */
    public record JudgeTestCase(int position, String input, String expectedOutput, int weight) {

        /** Never prints input or the expected answer. */
        @Override
        public String toString() {
            return "JudgeTestCase{position=%d, weight=%d}".formatted(position, weight);
        }
    }

    /** The outcome the worker writes back. */
    public record JudgeResult(
            SubmissionStatus status,
            Integer testsTotal,
            Integer testsPassed,
            Integer failedTestIndex,
            Integer runtimeMs,
            Integer memoryKb,
            String errorMessage) {
    }
}
