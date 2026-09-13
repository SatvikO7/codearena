package com.codearena.contest;

import com.codearena.common.ValidationException;
import com.codearena.user.User;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A contest: a schedule, an ordered set of problems, and the people taking part.
 *
 * <h2>What this class is responsible for</h2>
 * Every rule about <em>when</em> a contest may be changed lives here rather than in a
 * service, because those rules are the difference between a fair contest and an unfair one.
 * A service can be called from a new endpoint by someone who does not know the rules; an
 * aggregate that refuses cannot be.
 *
 * <h2>Immutability once it starts</h2>
 * The schedule, the problem set, the ordering and the points are frozen the moment the
 * contest becomes LIVE. Not by convention — {@link #requireEditable()} throws.
 *
 * <p>That is deliberately strict. Changing what a problem is worth while people are solving
 * it silently rewrites the standings of everyone who already submitted; moving {@code endAt}
 * forward invalidates the penalty calculation for every solve so far. Neither is an edit,
 * and an administrator who genuinely needs to stop a contest has {@link #cancel}, which is
 * visible to everyone rather than quiet. There is no emergency override, because an
 * override that exists is an override that gets used.
 */
@Entity
@Table(name = "contests")
@EntityListeners(AuditingEntityListener.class)
public class Contest {

    /** Nothing shorter is a contest; the check exists to catch a swapped unit, not to nag. */
    public static final Duration MIN_DURATION = Duration.ofMinutes(5);

    /**
     * A ceiling on scheduled length. Not a competitive-programming rule — a guard against a
     * mistyped year producing a contest that is LIVE for a decade and never ends.
     */
    public static final Duration MAX_DURATION = Duration.ofDays(14);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "slug", nullable = false, length = 200)
    private String slug;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle", nullable = false, length = 16)
    private ContestLifecycle lifecycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @OneToMany(mappedBy = "contest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    private List<ContestProblem> problems = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Contest() {
        // Required by JPA.
    }

    public static Contest create(String title, String slug, String description,
                                 Instant startAt, Instant endAt, User createdBy) {
        Contest contest = new Contest();
        contest.publicId = UUID.randomUUID();
        contest.title = title;
        contest.slug = slug;
        contest.description = description;
        contest.lifecycle = ContestLifecycle.DRAFT;
        contest.createdBy = createdBy;
        contest.applySchedule(startAt, endAt);
        return contest;
    }

    // ------------------------------------------------------------------ status

    /** The status as of {@code at}. Always computed; never read from a column. */
    public ContestStatus statusAt(Instant at) {
        return ContestStatus.of(lifecycle, startAt, endAt, at);
    }

    /**
     * Whether a submission made at {@code at} falls inside the contest.
     *
     * <p>The only place this question is answered. {@code startAt <= at < endAt}, evaluated
     * against a server-supplied instant — a client's clock never reaches this method.
     */
    public boolean acceptsSubmissionAt(Instant at) {
        return statusAt(at).acceptsSubmissions();
    }

    // ------------------------------------------------------------------ mutation

    public void updateDetails(String title, String slug, String description, Instant now) {
        requireEditable(now);
        this.title = title;
        this.slug = slug;
        this.description = description;
    }

    public void reschedule(Instant startAt, Instant endAt, Instant now) {
        requireEditable(now);
        applySchedule(startAt, endAt);
    }

    /**
     * Releases the contest to users.
     *
     * <p>Refused for a contest with no problems: publishing an empty contest lets people
     * register for something they cannot compete in, and the problems would then have to be
     * added while it was already visible.
     */
    public void publish(Instant now) {
        requireLifecycleTransition(ContestLifecycle.PUBLISHED);
        if (problems.isEmpty()) {
            throw new ValidationException("problems",
                    "A contest must have at least one problem before it is published");
        }
        if (!endAt.isAfter(now)) {
            throw new ValidationException("endAt",
                    "Cannot publish a contest that has already ended");
        }
        this.lifecycle = ContestLifecycle.PUBLISHED;
    }

    /**
     * Calls the contest off.
     *
     * <p>Permitted from DRAFT and from PUBLISHED, <b>including while LIVE</b>. Cancelling a
     * running contest is a real operational need — a broken problem, a leaked test set — and
     * refusing it would leave an administrator with no honest option but to let a spoiled
     * contest finish.
     *
     * <p>The semantics are deliberately blunt: submissions stop immediately, because
     * CANCELLED does not accept them; the submissions already made are kept, because they
     * are a record of what happened; and the standings remain readable as a historical
     * record, marked cancelled. Nothing is deleted and nothing is rewritten. A cancelled
     * contest is never resurrected — that is what {@link ContestLifecycle#canTransitionTo}
     * enforces — so a contestant is never told a contest is off and then that it is on again.
     *
     * <p>An <em>ended</em> contest cannot be cancelled. Its result is already history.
     */
    public void cancel(Instant now) {
        requireLifecycleTransition(ContestLifecycle.CANCELLED);
        if (statusAt(now) == ContestStatus.ENDED) {
            throw new ValidationException("status",
                    "A contest that has already ended cannot be cancelled");
        }
        this.lifecycle = ContestLifecycle.CANCELLED;
    }

    // ------------------------------------------------------------------ problems

    public void addProblem(ContestProblem problem, Instant now) {
        requireEditable(now);
        problems.add(problem);
    }

    public void removeProblem(ContestProblem problem, Instant now) {
        requireEditable(now);
        problems.remove(problem);
    }

    /**
     * Refuses any change once the contest has started.
     *
     * <p>Applies to the schedule, the problem set, the ordering and the points alike,
     * because all four change what a contestant is competing for.
     */
    public void requireEditable(Instant now) {
        ContestStatus status = statusAt(now);
        if (!status.isEditable()) {
            throw new ValidationException("status",
                    "A %s contest cannot be modified. Its schedule, problems and points were "
                    .formatted(status)
                    + "fixed when it started; cancel it instead if it must be stopped.");
        }
    }

    private void requireLifecycleTransition(ContestLifecycle target) {
        if (!lifecycle.canTransitionTo(target)) {
            throw new ValidationException("lifecycle",
                    "A %s contest cannot become %s".formatted(lifecycle, target));
        }
    }

    /**
     * Validates a schedule before accepting it.
     *
     * <p>The database enforces {@code end_at > start_at} as well. This exists so that a bad
     * schedule is a clear 400 naming the field rather than a constraint-violation 500, not
     * because the database check is redundant.
     */
    private void applySchedule(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw new ValidationException("startAt", "A contest needs a start and an end");
        }
        if (!endAt.isAfter(startAt)) {
            throw new ValidationException("endAt", "A contest must end after it starts");
        }
        Duration duration = Duration.between(startAt, endAt);
        if (duration.compareTo(MIN_DURATION) < 0) {
            throw new ValidationException("endAt",
                    "A contest must run for at least " + MIN_DURATION.toMinutes() + " minutes");
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            throw new ValidationException("endAt",
                    "A contest must run for at most " + MAX_DURATION.toDays() + " days");
        }
        this.startAt = startAt;
        this.endAt = endAt;
    }

    // ------------------------------------------------------------------ accessors

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getTitle() {
        return title;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public ContestLifecycle getLifecycle() {
        return lifecycle;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public List<ContestProblem> getProblems() {
        return problems;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Never prints the description; a title and a schedule are enough to identify one. */
    @Override
    public String toString() {
        return "Contest{publicId=%s, slug=%s, lifecycle=%s}".formatted(publicId, slug, lifecycle);
    }
}
