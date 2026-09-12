package com.codearena.problem;

import com.codearena.common.ValidationException;
import com.codearena.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An authored problem, and the aggregate root for its examples, test cases and tags.
 *
 * <p>Those collections are owned entirely by the problem: they are replaced wholesale on
 * edit and deleted with it. Modelling them as a real aggregate — rather than as three
 * independently-managed repositories — means an edit cannot leave a problem holding
 * examples that belong to a previous version of itself.
 *
 * <p>Status is not a settable property. It moves only through {@link #publish},
 * {@link #unpublish} and {@link #archive}, each of which enforces the transition rules,
 * so no update payload can push a problem into PUBLISHED without passing the
 * completeness checks.
 */
@Entity
@Table(name = "problems")
@EntityListeners(AuditingEntityListener.class)
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The only identifier the API exposes. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "statement", columnDefinition = "text")
    private String statement;

    @Column(name = "input_format", columnDefinition = "text")
    private String inputFormat;

    @Column(name = "output_format", columnDefinition = "text")
    private String outputFormat;

    @Column(name = "constraints", columnDefinition = "text")
    private String constraints;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProblemStatus status;

    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs;

    @Column(name = "memory_limit_mb", nullable = false)
    private int memoryLimitMb;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ProblemExample> examples = new ArrayList<>();

    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ProblemTestCase> testCases = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "problem_tags", joinColumns = @JoinColumn(name = "problem_id"))
    @Column(name = "tag", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<ProblemTag> tags = EnumSet.noneOf(ProblemTag.class);

    protected Problem() {
        // Required by JPA.
    }

    /** Creates a problem in DRAFT. Nothing is ever created already published. */
    public static Problem createDraft(String slug, String title, Difficulty difficulty, User author) {
        Problem problem = new Problem();
        problem.publicId = UUID.randomUUID();
        problem.slug = slug;
        problem.title = title;
        problem.difficulty = difficulty;
        problem.status = ProblemStatus.DRAFT;
        problem.createdBy = author;
        problem.timeLimitMs = 1000;
        problem.memoryLimitMb = 256;
        return problem;
    }

    // ------------------------------------------------------------------ content

    public void updateContent(String title, String statement, String inputFormat, String outputFormat,
                              String constraints, String explanation, Difficulty difficulty,
                              int timeLimitMs, int memoryLimitMb, Set<ProblemTag> tags, User editor) {
        this.title = title;
        this.statement = statement;
        this.inputFormat = inputFormat;
        this.outputFormat = outputFormat;
        this.constraints = constraints;
        this.explanation = explanation;
        this.difficulty = difficulty;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
        this.tags = tags.isEmpty() ? EnumSet.noneOf(ProblemTag.class) : EnumSet.copyOf(tags);
        this.updatedBy = editor;
    }

    /**
     * Changes the URL handle. Separate from {@link #updateContent} because a slug change
     * breaks existing links, so it must be an explicit act rather than a side effect of
     * editing the title.
     */
    public void changeSlug(String slug) {
        this.slug = slug;
    }

    /** Replaces every example. Positions are reassigned so ordering is always contiguous. */
    public void replaceExamples(List<ExampleContent> replacements) {
        examples.clear();
        for (int index = 0; index < replacements.size(); index++) {
            ExampleContent content = replacements.get(index);
            examples.add(new ProblemExample(this, index, content.input(), content.output(), content.explanation()));
        }
    }

    /** Replaces every test case. Positions are reassigned so ordering is always contiguous. */
    public void replaceTestCases(List<TestCaseContent> replacements) {
        testCases.clear();
        for (int index = 0; index < replacements.size(); index++) {
            TestCaseContent content = replacements.get(index);
            testCases.add(new ProblemTestCase(this, index, content.input(), content.expectedOutput(),
                    content.hidden(), content.weight()));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Makes the problem publicly visible.
     *
     * <p>Publication is gated on completeness because a published problem is one a user
     * is expected to solve: without a statement, an input and output format, a worked
     * example and at least one test case, there is nothing to solve and nothing to judge
     * against. Enforcing this at the transition — rather than when the fields are edited —
     * lets an author save an incomplete draft and come back to it.
     */
    public void publish(User actor) {
        requireTransitionTo(ProblemStatus.PUBLISHED);

        List<String> missing = missingForPublication();
        if (!missing.isEmpty()) {
            throw new ProblemNotPublishableException(missing);
        }
        this.status = ProblemStatus.PUBLISHED;
        this.updatedBy = actor;
    }

    /** Returns a published problem to DRAFT, removing it from the catalogue. */
    public void unpublish(User actor) {
        requireTransitionTo(ProblemStatus.DRAFT);
        this.status = ProblemStatus.DRAFT;
        this.updatedBy = actor;
    }

    public void archive(User actor) {
        requireTransitionTo(ProblemStatus.ARCHIVED);
        this.status = ProblemStatus.ARCHIVED;
        this.updatedBy = actor;
    }

    /** Restores an archived problem to DRAFT. It must be published again to reappear. */
    public void restoreToDraft(User actor) {
        requireTransitionTo(ProblemStatus.DRAFT);
        this.status = ProblemStatus.DRAFT;
        this.updatedBy = actor;
    }

    private void requireTransitionTo(ProblemStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
    }

    /** The content requirements publication enforces, listed so the error can name them. */
    public List<String> missingForPublication() {
        List<String> missing = new ArrayList<>();
        if (isBlank(statement)) {
            missing.add("statement");
        }
        if (isBlank(inputFormat)) {
            missing.add("inputFormat");
        }
        if (isBlank(outputFormat)) {
            missing.add("outputFormat");
        }
        if (isBlank(constraints)) {
            missing.add("constraints");
        }
        if (examples.isEmpty()) {
            missing.add("at least one example");
        }
        if (testCases.isEmpty()) {
            missing.add("at least one test case");
        }
        return missing;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------------ accessors

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getStatement() {
        return statement;
    }

    public String getInputFormat() {
        return inputFormat;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public String getConstraints() {
        return constraints;
    }

    public String getExplanation() {
        return explanation;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public ProblemStatus getStatus() {
        return status;
    }

    public int getTimeLimitMs() {
        return timeLimitMs;
    }

    public int getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ProblemExample> getExamples() {
        return Collections.unmodifiableList(examples);
    }

    /**
     * Every test case, hidden ones included. Only the admin API and, later, the judge may
     * call this; the catalogue service has no reason to and does not.
     */
    public List<ProblemTestCase> getTestCases() {
        return Collections.unmodifiableList(testCases);
    }

    public Set<ProblemTag> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Problem problem) || id == null) {
            return false;
        }
        return id.equals(problem.id);
    }

    @Override
    public int hashCode() {
        return Problem.class.hashCode();
    }

    @Override
    public String toString() {
        return "Problem{id=%d, publicId=%s, slug='%s', status=%s}".formatted(id, publicId, slug, status);
    }

    /** Example content, independent of persistence identity. */
    public record ExampleContent(String input, String output, String explanation) {
    }

    /** Test case content, independent of persistence identity. */
    public record TestCaseContent(String input, String expectedOutput, boolean hidden, int weight) {
    }

    /** Raised when a transition is not permitted by {@link ProblemStatus}. */
    public static class InvalidStatusTransitionException extends RuntimeException {
        private final transient ProblemStatus from;
        private final transient ProblemStatus to;

        InvalidStatusTransitionException(ProblemStatus from, ProblemStatus to) {
            super("A problem cannot move from %s to %s".formatted(from, to));
            this.from = from;
            this.to = to;
        }

        public ProblemStatus getFrom() {
            return from;
        }

        public ProblemStatus getTo() {
            return to;
        }
    }

    /** Raised when publication is attempted on an incomplete problem. */
    public static class ProblemNotPublishableException extends ValidationException {
        private final transient List<String> missing;

        ProblemNotPublishableException(List<String> missing) {
            super("status", "Cannot publish: missing " + String.join(", ", missing));
            this.missing = List.copyOf(missing);
        }

        public List<String> getMissing() {
            return missing;
        }
    }
}
