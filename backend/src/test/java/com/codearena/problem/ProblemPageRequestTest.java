package com.codearena.problem;

import com.codearena.common.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemPageRequestTest {

    @Test
    void appliesTheDocumentedDefaults() {
        Pageable pageable = ProblemPageRequest.of(null, null, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(ProblemPageRequest.DEFAULT_SIZE);
    }

    /**
     * An oversized request is clamped rather than rejected: asking for 5000 rows is far
     * more often naive than hostile, and serving a sane page is friendlier than a 400
     * while still refusing to read the whole table.
     */
    @Test
    void clampsAnOversizedPageInsteadOfRejectingIt() {
        Pageable pageable = ProblemPageRequest.of(0, 5000, null);

        assertThat(pageable.getPageSize()).isEqualTo(ProblemPageRequest.MAX_SIZE);
    }

    @Test
    void honoursASizeWithinTheLimit() {
        assertThat(ProblemPageRequest.of(2, 50, null).getPageSize()).isEqualTo(50);
        assertThat(ProblemPageRequest.of(2, 50, null).getPageNumber()).isEqualTo(2);
    }

    @Test
    void rejectsNonsensicalParameters() {
        assertThatThrownBy(() -> ProblemPageRequest.of(-1, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> ProblemPageRequest.of(0, 0, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least 1");
    }

    /**
     * Every ordering must end with a unique tiebreaker. Without one, rows sharing a sort
     * key have no defined order, so the same row can appear on two consecutive pages while
     * another is skipped entirely.
     */
    @Test
    void everySortEndsWithAUniqueTiebreaker() {
        for (ProblemSort sort : ProblemSort.values()) {
            Sort.Order last = sort.toSort().stream().reduce((first, second) -> second).orElseThrow();

            assertThat(last.getProperty())
                    .as("%s must end with the id tiebreaker so paging is stable", sort)
                    .isEqualTo("id");
        }
    }

    @Test
    void parsesSortNamesCaseInsensitivelyAndDefaultsToNewest() {
        assertThat(ProblemSort.parse(null)).isEqualTo(ProblemSort.NEWEST);
        assertThat(ProblemSort.parse("  ")).isEqualTo(ProblemSort.NEWEST);
        assertThat(ProblemSort.parse("title")).isEqualTo(ProblemSort.TITLE);
        assertThat(ProblemSort.parse("  DiFfIcUlTy ")).isEqualTo(ProblemSort.DIFFICULTY);
    }

    /**
     * An unknown sort is a client error naming the valid options, not a 500 and not a
     * silent fallback to an arbitrary column. Spring Data would otherwise accept any
     * property name, which is not something a public parameter should control.
     */
    @Test
    void rejectsAnUnknownSortRatherThanOrderingByAnArbitraryProperty() {
        assertThatThrownBy(() -> ProblemSort.parse("createdBy.passwordHash"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("NEWEST");
    }
}
