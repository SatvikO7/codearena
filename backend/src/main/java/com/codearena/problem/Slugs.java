package com.codearena.problem;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL handle rules.
 *
 * <p>A slug is derived from a title only when the author does not supply one. After that
 * it is an independent field: renaming a problem must not silently change its URL and
 * break every existing link. Changing a slug is a separate, explicit operation.
 */
public final class Slugs {

    /** Lower-case alphanumeric groups separated by single hyphens. Mirrored by a database CHECK. */
    public static final Pattern VALID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    public static final int MAX_LENGTH = 120;

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+)|(-+$)");

    private Slugs() {
    }

    public static boolean isValid(String slug) {
        return slug != null && slug.length() <= MAX_LENGTH && VALID.matcher(slug).matches();
    }

    /**
     * Derives a slug from a title.
     *
     * <p>Accented characters are decomposed and stripped rather than dropped outright, so
     * "Añadir Números" becomes {@code anadir-numeros} instead of {@code adir-meros}.
     * Anything still non-alphanumeric collapses to a single hyphen.
     *
     * @return the derived slug, or empty string if the title contains nothing usable
     */
    public static String from(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD);
        String withoutAccents = DIACRITICS.matcher(decomposed).replaceAll("");
        String lowered = withoutAccents.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALPHANUMERIC.matcher(lowered).replaceAll("-");
        String trimmed = EDGE_HYPHENS.matcher(hyphenated).replaceAll("");

        if (trimmed.length() <= MAX_LENGTH) {
            return trimmed;
        }
        // Truncating mid-word can leave a trailing hyphen, which the format rule forbids.
        String truncated = trimmed.substring(0, MAX_LENGTH);
        return EDGE_HYPHENS.matcher(truncated).replaceAll("");
    }
}
