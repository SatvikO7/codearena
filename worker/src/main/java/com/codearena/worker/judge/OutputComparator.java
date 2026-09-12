package com.codearena.worker.judge;

import org.springframework.stereotype.Component;

/**
 * Decides whether a program's output matches the expected answer.
 *
 * <h2>The policy, exactly</h2>
 * Both sides are normalised identically before comparison:
 * <ol>
 *   <li><b>Line endings.</b> {@code \r\n} and a lone {@code \r} both become {@code \n}. A
 *       solution written on Windows must not fail against an answer file written on Linux;
 *       that is a property of the author's editor, not of the algorithm.</li>
 *   <li><b>Trailing whitespace on each line</b> is removed. Printing {@code "3 "} instead
 *       of {@code "3"} is invisible to a human reader and says nothing about correctness.</li>
 *   <li><b>Trailing blank lines</b> are removed from the end of the output. Whether a
 *       program ends with a final newline depends on whether the author used {@code println}
 *       or {@code print}, which is not what is being tested.</li>
 * </ol>
 *
 * <p>Everything else is <b>exact</b>. Whitespace <em>within</em> a line is significant, so
 * {@code "1 2 3"} does not match {@code "1  2  3"} — collapsing internal spacing would let
 * a program that prints its numbers in the wrong columns pass, and output formatting is
 * usually part of what a problem specifies. Leading whitespace is significant for the same
 * reason. Blank lines in the <em>middle</em> of the output are significant.
 *
 * <p>There is deliberately <b>no floating-point tolerance</b>. Comparing {@code 0.1 + 0.2}
 * against {@code 0.3} within an epsilon requires knowing the problem's intended precision,
 * and the problem model has no field for it. Inventing a default epsilon would silently
 * accept wrong answers on some problems and reject right ones on others. A problem needing
 * it should declare it, and that belongs with a checker framework rather than here.
 *
 * <p>The policy is pure and total: same inputs, same verdict, every time, with no clock,
 * locale or platform dependency.
 */
@Component
public class OutputComparator {

    /** @return true when the actual output is an acceptable match for the expected one */
    public boolean matches(String expected, String actual) {
        return normalise(expected).equals(normalise(actual));
    }

    /**
     * Applies the documented normalisation.
     *
     * <p>Exposed so the tests can assert on the normal form itself, rather than only on
     * whether two strings happen to agree.
     */
    public String normalise(String value) {
        if (value == null) {
            return "";
        }

        String unified = value.replace("\r\n", "\n").replace('\r', '\n');

        String[] lines = unified.split("\n", -1);
        int lastMeaningful = lines.length - 1;
        StringBuilder[] trimmed = new StringBuilder[lines.length];
        for (int i = 0; i < lines.length; i++) {
            trimmed[i] = new StringBuilder(stripTrailing(lines[i]));
        }
        // Walk back over the empty tail so only genuinely trailing blank lines are dropped.
        while (lastMeaningful >= 0 && trimmed[lastMeaningful].isEmpty()) {
            lastMeaningful--;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i <= lastMeaningful; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(trimmed[i]);
        }
        return result.toString();
    }

    /** Only trailing whitespace; leading whitespace is part of the answer. */
    private String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
