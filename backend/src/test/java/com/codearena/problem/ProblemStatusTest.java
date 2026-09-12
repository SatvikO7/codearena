package com.codearena.problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemStatusTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT,     PUBLISHED",
            "DRAFT,     ARCHIVED",
            "PUBLISHED, DRAFT",
            "PUBLISHED, ARCHIVED",
            "ARCHIVED,  DRAFT",
    })
    void permitsTheDefinedTransitions(ProblemStatus from, ProblemStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // Archiving is reversible, but only back to DRAFT: an archived problem must not
            // be able to reappear in the catalogue without an explicit publish.
            "ARCHIVED,  PUBLISHED",
            // Re-entering the state you are already in is not a transition.
            "DRAFT,     DRAFT",
            "PUBLISHED, PUBLISHED",
            "ARCHIVED,  ARCHIVED",
    })
    void refusesEverythingElse(ProblemStatus from, ProblemStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void onlyPublishedIsVisibleToNormalUsers() {
        assertThat(ProblemStatus.PUBLISHED.isPubliclyVisible()).isTrue();
        assertThat(ProblemStatus.DRAFT.isPubliclyVisible()).isFalse();
        assertThat(ProblemStatus.ARCHIVED.isPubliclyVisible()).isFalse();
    }

    /**
     * Status is persisted by name. Reordering this enum must not reclassify existing rows,
     * so the names are pinned here and mirrored by the ck_problems_status constraint.
     */
    @ParameterizedTest
    @EnumSource(ProblemStatus.class)
    void persistsByNameSoReorderingCannotReclassifyRows(ProblemStatus status) {
        assertThat(ProblemStatus.valueOf(status.name())).isEqualTo(status);
    }

    @Test
    void hasExactlyTheThreeDocumentedStates() {
        assertThat(ProblemStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("DRAFT", "PUBLISHED", "ARCHIVED");
    }
}
