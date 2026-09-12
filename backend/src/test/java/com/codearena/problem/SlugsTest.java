package com.codearena.problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @ParameterizedTest
    @CsvSource({
            "Two Sum,                     two-sum",
            "Longest Increasing Subsequence, longest-increasing-subsequence",
            "  Merge   Intervals  ,       merge-intervals",
            "Valid Parentheses!,          valid-parentheses",
            "K-th Largest Element,        k-th-largest-element",
            "Count 1's in Binary,         count-1-s-in-binary",
    })
    void derivesAUrlSafeSlugFromATitle(String title, String expected) {
        assertThat(Slugs.from(title)).isEqualTo(expected);
    }

    /**
     * Accents are decomposed and stripped rather than discarded wholesale, so a title in
     * another language still yields a readable slug instead of losing its vowels.
     */
    @Test
    void stripsAccentsInsteadOfDroppingTheCharacters() {
        assertThat(Slugs.from("Añadir Números")).isEqualTo("anadir-numeros");
        assertThat(Slugs.from("Café Crème")).isEqualTo("cafe-creme");
    }

    @Test
    void collapsesRunsOfSeparatorsIntoASingleHyphen() {
        assertThat(Slugs.from("A -- B __ C")).isEqualTo("a-b-c");
    }

    @Test
    void neverProducesLeadingOrTrailingHyphens() {
        assertThat(Slugs.from("!!! Edge Case !!!")).isEqualTo("edge-case");
        assertThat(Slugs.from("---hello---")).isEqualTo("hello");
    }

    @Test
    void returnsEmptyWhenTheTitleHasNothingUsable() {
        assertThat(Slugs.from("!!!")).isEmpty();
        assertThat(Slugs.from("   ")).isEmpty();
        assertThat(Slugs.from(null)).isEmpty();
    }

    /** Truncation must not leave a trailing hyphen, which the format rule forbids. */
    @Test
    void truncatesLongTitlesWithoutBreakingTheFormat() {
        String longTitle = "word ".repeat(60);

        String slug = Slugs.from(longTitle);

        assertThat(slug.length()).isLessThanOrEqualTo(Slugs.MAX_LENGTH);
        assertThat(slug).doesNotEndWith("-").doesNotStartWith("-");
        assertThat(Slugs.isValid(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"two-sum", "a", "a-b-c", "problem-123", "1-2-3"})
    void acceptsWellFormedSlugs(String slug) {
        assertThat(Slugs.isValid(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Two-Sum",      // upper case
            "two_sum",      // underscore
            "two--sum",     // doubled hyphen
            "-two-sum",     // leading hyphen
            "two-sum-",     // trailing hyphen
            "two sum",      // space
            "two/sum",      // path separator, would break routing
            "",             // empty
    })
    void rejectsMalformedSlugs(String slug) {
        assertThat(Slugs.isValid(slug)).isFalse();
    }

    @Test
    void rejectsNullAndOverlongSlugs() {
        assertThat(Slugs.isValid(null)).isFalse();
        assertThat(Slugs.isValid("a".repeat(Slugs.MAX_LENGTH + 1))).isFalse();
    }

    /**
     * Everything the generator produces must satisfy the validator, or the two rules have
     * drifted apart and a derived slug could be rejected by the database.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Two Sum", "Añadir Números", "!!! Edge !!!", "A -- B", "Count 1's in Binary",
            "K-th Largest", "  spaced  out  ", "MiXeD CaSe TiTlE",
    })
    void generatedSlugsAlwaysSatisfyTheValidator(String title) {
        String slug = Slugs.from(title);

        assertThat(slug).isNotEmpty();
        assertThat(Slugs.isValid(slug))
                .as("generated slug '%s' must be valid", slug)
                .isTrue();
    }
}
