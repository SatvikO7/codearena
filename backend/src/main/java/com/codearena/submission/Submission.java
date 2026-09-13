package com.codearena.submission;

import com.codearena.contest.Contest;
import com.codearena.problem.Problem;
import com.codearena.shared.Language;
import com.codearena.shared.SubmissionStatus;
import com.codearena.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * A submitted solution and its judging state.
 *
 * <p>Almost every field here is written by the server. Nothing a client sends reaches
 * {@code status}, {@code runtimeMs}, {@code attempts} or any timestamp — the submission
 * request carries a language and a source string, and that is all.
 *
 * <p>The status field is never assigned directly. It moves through {@link #markRunning},
 * {@link #recordResult} and {@link #returnToQueue}, each of which consults
 * {@link SubmissionStatus#canTransitionTo} first, so a verdict once recorded cannot be
 * overwritten by a straggling worker.
 */
@Entity
@Table(name = "submissions")
@EntityListeners(AuditingEntityListener.class)
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The only identifier the API exposes. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false, updatable = false)
    private Problem problem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * The contest this was submitted to, or null for a practice submission.
     *
     * <p>Nullable is the whole design. Practice is the absence of a contest rather than a
     * separate kind of submission, so every existing row is already correct, the judging
     * pipeline needs no knowledge of contests, and there is exactly one submissions table
     * to reason about. {@code updatable = false}: which contest a submission belongs to is
     * decided when it is created and is never revised, because revising it would rewrite
     * a contest's history.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id", updatable = false)
    private Contest contest;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 16, updatable = false)
    private Language language;

    @Column(name = "source_code", nullable = false, updatable = false, columnDefinition = "text")
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SubmissionStatus status;

    @Column(name = "tests_total")
    private Integer testsTotal;

    @Column(name = "tests_passed")
    private Integer testsPassed;

    @Column(name = "runtime_ms")
    private Integer runtimeMs;

    @Column(name = "memory_kb")
    private Integer memoryKb;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "failed_test_index")
    private Integer failedTestIndex;

    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Submission() {
        // Required by JPA.
    }

    /** Creates a practice submission in QUEUED with no queue publication yet recorded. */
    public static Submission queue(Problem problem, User user, Language language, String sourceCode) {
        return queue(problem, user, language, sourceCode, null);
    }

    /**
     * Creates a submission, optionally within a contest.
     *
     * <p>The contest is the <em>only</em> difference between a contest submission and a
     * practice one. Same table, same status machine, same queue, same worker, same sandbox.
     * A second execution path for contests would be a second place for a judging bug to
     * live, and the one that runs less often would be the one nobody noticed was broken.
     */
    public static Submission queue(Problem problem, User user, Language language,
                                   String sourceCode, Contest contest) {
        Submission submission = new Submission();
        submission.publicId = UUID.randomUUID();
        submission.problem = problem;
        submission.user = user;
        submission.language = language;
        submission.sourceCode = sourceCode;
        submission.status = SubmissionStatus.QUEUED;
        submission.attempts = 0;
        submission.contest = contest;
        return submission;
    }

    /** Records that the job reached Redis. Until this is set, the sweeper will republish. */
    public void markEnqueued(Instant at) {
        this.enqueuedAt = at;
    }

    /**
     * Returns a submission whose worker died to the queue.
     *
     * <p>The only backwards move in the lifecycle, and it exists because the alternative is
     * worse: a submission stranded in RUNNING forever because the process holding it was
     * killed. It clears the lease and the publication marker so the sweeper republishes it.
     */
    public void returnToQueue() {
        requireTransitionTo(SubmissionStatus.QUEUED);
        this.status = SubmissionStatus.QUEUED;
        this.claimedBy = null;
        this.claimedAt = null;
        this.enqueuedAt = null;
        this.startedAt = null;
    }

    /**
     * Gives up on a submission the judge could not process.
     *
     * <p>SYSTEM_ERROR is the honest verdict here: the submitted code was never shown to be
     * wrong, the infrastructure failed to find out.
     */
    public void abandonAsSystemError(String reason) {
        requireTransitionTo(SubmissionStatus.SYSTEM_ERROR);
        this.status = SubmissionStatus.SYSTEM_ERROR;
        this.errorMessage = reason;
        this.finishedAt = Instant.now();
    }

    private void requireTransitionTo(SubmissionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalSubmissionTransitionException(publicId, status, target);
        }
    }

    // ------------------------------------------------------------------ accessors

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Problem getProblem() {
        return problem;
    }

    public User getUser() {
        return user;
    }

    /** The contest this belongs to, or null for practice. */
    public Contest getContest() {
        return contest;
    }

    public boolean isContestSubmission() {
        return contest != null;
    }

    public Language getLanguage() {
        return language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public Integer getTestsTotal() {
        return testsTotal;
    }

    public Integer getTestsPassed() {
        return testsPassed;
    }

    public Integer getRuntimeMs() {
        return runtimeMs;
    }

    public Integer getMemoryKb() {
        return memoryKb;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getFailedTestIndex() {
        return failedTestIndex;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Submission submission) || id == null) {
            return false;
        }
        return id.equals(submission.id);
    }

    @Override
    public int hashCode() {
        return Submission.class.hashCode();
    }

    /** Deliberately excludes the source code so it cannot reach a log line. */
    @Override
    public String toString() {
        return "Submission{publicId=%s, language=%s, status=%s, attempts=%d}"
                .formatted(publicId, language, status, attempts);
    }

    /** Raised when something attempts a move the lifecycle forbids. */
    public static class IllegalSubmissionTransitionException extends RuntimeException {
        private final transient SubmissionStatus from;
        private final transient SubmissionStatus to;

        public IllegalSubmissionTransitionException(UUID publicId, SubmissionStatus from, SubmissionStatus to) {
            super("Submission %s cannot move from %s to %s".formatted(publicId, from, to));
            this.from = from;
            this.to = to;
        }

        public SubmissionStatus getFrom() {
            return from;
        }

        public SubmissionStatus getTo() {
            return to;
        }
    }
}
