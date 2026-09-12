package com.codearena.problem;

import com.codearena.user.Role;
import com.codearena.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The publication rules, exercised on the entity itself.
 *
 * <p>These are pure domain tests: no Spring, no database. The rule that a problem cannot
 * be published while incomplete lives in the entity precisely so that it holds no matter
 * which service, controller or future import script attempts the transition.
 */
class ProblemLifecycleTest {

    private User admin;
    private Problem problem;

    @BeforeEach
    void setUp() {
        admin = User.create("root", "root@example.com", "{bcrypt}irrelevant", Role.ADMIN);
        problem = Problem.createDraft("two-sum", "Two Sum", Difficulty.EASY, admin);
    }

    @Test
    void isCreatedAsADraftNeverAsPublished() {
        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.DRAFT);
        assertThat(problem.getPublicId()).isNotNull();
        assertThat(problem.getCreatedBy()).isEqualTo(admin);
    }

    @Test
    void refusesToPublishAnEmptyDraftAndNamesEveryMissingPiece() {
        Problem.ProblemNotPublishableException thrown = catchThrowableOfType(
                Problem.ProblemNotPublishableException.class, () -> problem.publish(admin));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMissing()).contains(
                "statement", "inputFormat", "outputFormat", "constraints",
                "at least one example", "at least one test case");

        assertThat(problem.getStatus())
                .as("a refused publication must leave the status untouched")
                .isEqualTo(ProblemStatus.DRAFT);
    }

    /** A statement of whitespace is as unpublishable as no statement at all. */
    @Test
    void treatsBlankContentAsMissing() {
        fillContent(problem, "   ");
        problem.replaceExamples(List.of(new Problem.ExampleContent("1", "1", null)));
        problem.replaceTestCases(List.of(new Problem.TestCaseContent("1", "1", true, 1)));

        assertThatThrownBy(() -> problem.publish(admin))
                .isInstanceOf(Problem.ProblemNotPublishableException.class);
    }

    @Test
    void refusesToPublishWithoutAnyTestCase() {
        fillContent(problem, "content");
        problem.replaceExamples(List.of(new Problem.ExampleContent("1 2", "3", null)));

        assertThatThrownBy(() -> problem.publish(admin))
                .isInstanceOf(Problem.ProblemNotPublishableException.class)
                .hasMessageContaining("at least one test case");
    }

    @Test
    void refusesToPublishWithoutAnyExample() {
        fillContent(problem, "content");
        problem.replaceTestCases(List.of(new Problem.TestCaseContent("1 2", "3", true, 1)));

        assertThatThrownBy(() -> problem.publish(admin))
                .isInstanceOf(Problem.ProblemNotPublishableException.class)
                .hasMessageContaining("at least one example");
    }

    @Test
    void publishesOnceComplete() {
        makePublishable(problem);

        problem.publish(admin);

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.PUBLISHED);
        assertThat(problem.missingForPublication()).isEmpty();
        assertThat(problem.getUpdatedBy()).isEqualTo(admin);
    }

    @Test
    void movesThroughTheFullLifecycle() {
        makePublishable(problem);

        problem.publish(admin);
        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.PUBLISHED);

        problem.unpublish(admin);
        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.DRAFT);

        problem.archive(admin);
        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.ARCHIVED);

        problem.restoreToDraft(admin);
        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.DRAFT);
    }

    @Test
    void refusesToPublishAnArchivedProblemDirectly() {
        makePublishable(problem);
        problem.archive(admin);

        assertThatThrownBy(() -> problem.publish(admin))
                .isInstanceOf(Problem.InvalidStatusTransitionException.class);

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.ARCHIVED);
    }

    @Test
    void refusesToUnpublishSomethingThatIsNotPublished() {
        assertThatThrownBy(() -> problem.unpublish(admin))
                .isInstanceOf(Problem.InvalidStatusTransitionException.class);
    }

    // ------------------------------------------------------------- collections

    @Test
    void reassignsExamplePositionsSoOrderingIsAlwaysContiguous() {
        problem.replaceExamples(List.of(
                new Problem.ExampleContent("a", "1", "first"),
                new Problem.ExampleContent("b", "2", null),
                new Problem.ExampleContent("c", "3", "third")));

        assertThat(problem.getExamples()).extracting(ProblemExample::getPosition)
                .containsExactly(0, 1, 2);
        assertThat(problem.getExamples()).extracting(ProblemExample::getInput)
                .containsExactly("a", "b", "c");
    }

    @Test
    void replacingCollectionsDiscardsThePreviousContents() {
        problem.replaceExamples(List.of(new Problem.ExampleContent("old", "old", null)));
        problem.replaceExamples(List.of(new Problem.ExampleContent("new", "new", null)));

        assertThat(problem.getExamples()).hasSize(1);
        assertThat(problem.getExamples().getFirst().getInput()).isEqualTo("new");
    }

    @Test
    void preservesTestCaseVisibilityAndWeight() {
        problem.replaceTestCases(List.of(
                new Problem.TestCaseContent("a", "1", false, 1),
                new Problem.TestCaseContent("b", "2", true, 5)));

        assertThat(problem.getTestCases()).extracting(ProblemTestCase::isHidden)
                .containsExactly(false, true);
        assertThat(problem.getTestCases()).extracting(ProblemTestCase::getWeight)
                .containsExactly(1, 5);
    }

    /** A test case must not print its input or answer key into a log line. */
    @Test
    void testCaseToStringOmitsTheAnswerKey() {
        problem.replaceTestCases(List.of(
                new Problem.TestCaseContent("secret input", "secret answer", true, 1)));

        assertThat(problem.getTestCases().getFirst().toString())
                .doesNotContain("secret input")
                .doesNotContain("secret answer");
    }

    @Test
    void exposesCollectionsAsUnmodifiableSoCallersCannotMutateTheAggregate() {
        makePublishable(problem);

        assertThatThrownBy(() -> problem.getExamples().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> problem.getTestCases().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> problem.getTags().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void changingTheSlugIsSeparateFromEditingContent() {
        problem.updateContent("A New Title", "s", "i", "o", "c", null,
                Difficulty.HARD, 2000, 512, Set.of(ProblemTag.ARRAY), admin);

        assertThat(problem.getTitle()).isEqualTo("A New Title");
        assertThat(problem.getSlug())
                .as("renaming a problem must not silently change its URL")
                .isEqualTo("two-sum");

        problem.changeSlug("a-new-title");
        assertThat(problem.getSlug()).isEqualTo("a-new-title");
    }

    // -------------------------------------------------------------------- helpers

    private void fillContent(Problem target, String filler) {
        target.updateContent("Two Sum", filler, filler, filler, filler, null,
                Difficulty.EASY, 1000, 256, Set.of(), admin);
    }

    private void makePublishable(Problem target) {
        fillContent(target, "content");
        target.replaceExamples(List.of(new Problem.ExampleContent("1 2", "3", "adds them")));
        target.replaceTestCases(List.of(new Problem.TestCaseContent("1 2", "3", true, 1)));
    }
}
