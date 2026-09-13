package com.codearena.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The structured detail attached to an audit event, and the rules that keep it safe.
 *
 * <h2>Curated, never captured</h2>
 * Metadata is built field by field by the code recording the event. Nothing here serialises
 * a request body, a DTO or an entity. A problem publication records
 * {@code {problemId, slug, previousStatus, newStatus}} — not the problem, which would carry
 * its statement and, worse, its test cases.
 *
 * <p>That is the primary defence and it is structural: a map somebody has to populate by
 * hand cannot accidentally acquire a password, because nobody writes {@code put("password",
 * …)}. The redaction below is the second line, for the mistake that gets made anyway.
 *
 * <h2>What must never appear</h2>
 * Passwords and hashes, session identifiers, CSRF tokens, the executor token, submitted
 * source code, hidden test inputs and expected outputs. The first three are caught by name;
 * the rest are prevented by never putting them in.
 *
 * <h2>Bounded</h2>
 * Audit rows are written on hot paths and are never deleted, so an unbounded blob is a
 * permanent cost. Keys are capped in number, values in length, and the whole map in
 * serialised size by a database constraint as well.
 */
public final class AuditMetadata {

    /** More than any curated event needs; a guard against a loop populating a map. */
    public static final int MAX_ENTRIES = 20;

    /** Long enough for a slug, a status or a short reason; far too short for a program. */
    public static final int MAX_VALUE_LENGTH = 500;

    /** What replaces a value whose key looks like a secret. */
    public static final String REDACTED = "[redacted]";

    /**
     * Key fragments that must never carry a value.
     *
     * <p>Matched case-insensitively as substrings, so {@code passwordHash},
     * {@code sessionId} and {@code X-XSRF-TOKEN} are all caught. Deliberately broad: a
     * false positive costs one redacted audit field, a false negative writes a credential
     * into a table that is never deleted.
     */
    private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
            "password", "passwd", "secret", "token", "credential",
            "session", "cookie", "csrf", "xsrf", "authorization",
            "sourcecode", "source_code", "expectedoutput", "expected_output");

    private AuditMetadata() {
    }

    /** Starts a metadata map. */
    public static Builder of() {
        return new Builder();
    }

    /**
     * Builds a metadata map, redacting and truncating as it goes.
     *
     * <p>Null values are dropped rather than stored: "this field was absent" and "this field
     * was null" are the same thing to somebody reading an audit record, and a map full of
     * nulls is harder to read than a short one.
     */
    public static final class Builder {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder put(String key, Object value) {
            if (key == null || value == null || values.size() >= MAX_ENTRIES) {
                return this;
            }
            values.put(key, isForbidden(key) ? REDACTED : truncate(value));
            return this;
        }

        /** Records a transition, which is most of what administrative metadata says. */
        public Builder transition(Object from, Object to) {
            return put("previousStatus", from).put("newStatus", to);
        }

        public Map<String, Object> build() {
            return Map.copyOf(values);
        }
    }

    /**
     * Whether a key's name suggests it carries something secret.
     *
     * <p>Public so that a test can assert the rule directly rather than by inference.
     */
    public static boolean isForbidden(String key) {
        String normalised = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return FORBIDDEN_KEY_FRAGMENTS.stream()
                .map(fragment -> fragment.replace("_", ""))
                .anyMatch(normalised::contains);
    }

    /**
     * Caps a value's length.
     *
     * <p>Applies to strings only; numbers, booleans and enums are already small. A truncated
     * value is marked, so a reader is never misled into thinking they have the whole thing.
     */
    private static Object truncate(Object value) {
        if (!(value instanceof String text)) {
            return value instanceof Enum<?> constant ? constant.name() : value;
        }
        if (text.length() <= MAX_VALUE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_VALUE_LENGTH) + "…";
    }
}
