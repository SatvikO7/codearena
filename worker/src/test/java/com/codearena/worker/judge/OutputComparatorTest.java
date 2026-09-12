package com.codearena.worker.judge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison policy, pinned.
 *
 * <p>These tests are the specification. Every tolerance below exists because the thing it
 * tolerates is a property of the author's editor or standard library rather than of their
 * algorithm; every strictness exists because the thing it rejects is part of what a problem
 * asks for.
 */
class OutputComparatorTest {

    private final OutputComparator comparator = new OutputComparator();

    // ------------------------------------------------------------ what is tolerated

    @Test
    void acceptsIdenticalOutput() {
        assertThat(comparator.matches("42", "42")).isTrue();
    }

    /**
     * A solution written on Windows must not fail against an answer file written on Linux.
     *
     * <p>Written as explicit assertions rather than a CSV table: a CSV cell cannot carry a
     * real carriage return, so a table here would be comparing the two literal characters
     * {@code \} and {@code r} and quietly proving nothing.
     */
    @Test
    void normalisesEveryLineEndingConvention() {
        assertThat(comparator.matches("1\n2\n3", "1\r\n2\r\n3")).isTrue();   // Windows CRLF
        assertThat(comparator.matches("1\n2\n3", "1\r2\r3")).isTrue();       // classic Mac CR
        assertThat(comparator.matches("1\r\n2", "1\n2")).isTrue();           // and the reverse
        assertThat(comparator.normalise("a\r\nb\rc\nd")).isEqualTo("a\nb\nc\nd");
    }

    /** Printing "3 " instead of "3" is invisible to a reader and says nothing about correctness. */
    @Test
    void ignoresTrailingWhitespaceOnALine() {
        assertThat(comparator.matches("42", "42   ")).isTrue();
        assertThat(comparator.matches("1 2\n3 4", "1 2   \n3 4\t")).isTrue();
    }

    /** Whether the program ended with println or print is not what is being tested. */
    @Test
    void ignoresTrailingNewlinesAndBlankLines() {
        assertThat(comparator.matches("42", "42\n")).isTrue();
        assertThat(comparator.matches("42\n", "42")).isTrue();
        assertThat(comparator.matches("42", "42\n\n\n")).isTrue();
        assertThat(comparator.matches("42\n\n", "42")).isTrue();
    }

    @Test
    void treatsEmptyAndWhitespaceOnlyOutputAsEquivalent() {
        assertThat(comparator.matches("", "")).isTrue();
        assertThat(comparator.matches("", "\n\n  \n")).isTrue();
        assertThat(comparator.matches(null, "")).isTrue();
    }

    // ------------------------------------------------------------ what is rejected

    @Test
    void rejectsDifferentContent() {
        assertThat(comparator.matches("42", "43")).isFalse();
        assertThat(comparator.matches("yes", "YES")).isFalse();
    }

    /**
     * Internal spacing is significant. Collapsing it would let a program that prints its
     * numbers in the wrong columns pass, and output format is usually part of the problem.
     */
    @Test
    void keepsWhitespaceWithinALineSignificant() {
        assertThat(comparator.matches("1 2 3", "1  2  3")).isFalse();
        assertThat(comparator.matches("1 2 3", "123")).isFalse();
    }

    /** Leading whitespace is part of the answer, so indentation is not silently forgiven. */
    @Test
    void keepsLeadingWhitespaceSignificant() {
        assertThat(comparator.matches("42", "  42")).isFalse();
    }

    /** A blank line in the middle is structure, not padding. */
    @Test
    void keepsInteriorBlankLinesSignificant() {
        assertThat(comparator.matches("1\n2", "1\n\n2")).isFalse();
    }

    @Test
    void rejectsMissingOrExtraLines() {
        assertThat(comparator.matches("1\n2\n3", "1\n2")).isFalse();
        assertThat(comparator.matches("1\n2", "1\n2\n3")).isFalse();
    }

    /**
     * No floating-point tolerance. Applying an epsilon requires knowing the problem's
     * intended precision, and the model has no field for it; inventing a default would
     * accept wrong answers on some problems and reject right ones on others.
     */
    @Test
    void doesNotApplyFloatingPointTolerance() {
        assertThat(comparator.matches("0.3", "0.30000000000000004")).isFalse();
        assertThat(comparator.matches("1.0", "1")).isFalse();
    }

    // ------------------------------------------------------------ properties

    @Test
    void normalisationIsIdempotent() {
        String messy = "1 \r\n2\t\r\n\r\n\r\n";

        String once = comparator.normalise(messy);
        assertThat(comparator.normalise(once)).isEqualTo(once);
        assertThat(once).isEqualTo("1\n2");
    }

    /** Deterministic: no clock, locale or platform may influence a verdict. */
    @Test
    void isDeterministicAndSymmetric() {
        String a = "hello\r\nworld  \n";
        String b = "hello\nworld";

        assertThat(comparator.matches(a, b)).isTrue();
        assertThat(comparator.matches(b, a)).isTrue();
        for (int i = 0; i < 100; i++) {
            assertThat(comparator.matches(a, b)).isTrue();
        }
    }

    @Test
    void handlesLargeOutputWithoutSpecialCasing() {
        String big = "line\n".repeat(10_000);

        assertThat(comparator.matches(big, big + "\n\n")).isTrue();
        assertThat(comparator.matches(big, big + "extra")).isFalse();
    }
}
