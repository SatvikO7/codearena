package com.codearena.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that keep an audit record from becoming a credential leak.
 *
 * <p>The primary defence is structural — metadata is built field by field, so nobody
 * accidentally serialises a request body — and these tests cover the second line: the
 * redaction that catches the mistake made anyway. An audit table is never deleted, so a
 * secret written into one is a secret written permanently.
 */
class AuditMetadataTest {

    // ------------------------------------------------------------------ redaction

    @Test
    void redactsAnythingThatLooksLikeACredential() {
        Map<String, Object> metadata = AuditMetadata.of()
                .put("password", "hunter2")
                .put("passwordHash", "$2a$12$abcdef")
                .put("sessionId", "ABC123")
                .put("csrfToken", "tok")
                .put("authorization", "Bearer abc")
                .build();

        assertThat(metadata.values()).containsOnly(AuditMetadata.REDACTED);
    }

    /** Matched on the name however it is spelled, because conventions vary by framework. */
    @Test
    void matchesSecretKeysWhateverTheirCasingOrSeparators() {
        for (String key : new String[]{
                "PASSWORD", "Password", "user_password", "userPassword",
                "X-XSRF-TOKEN", "xsrf_token", "SESSION_COOKIE", "executorToken",
                "clientSecret", "dbCredential"}) {
            assertThat(AuditMetadata.isForbidden(key)).as("%s must be forbidden", key).isTrue();
        }
    }

    /** Source code and answer keys must never reach an audit row by any route. */
    @Test
    void redactsSourceCodeAndExpectedOutput() {
        Map<String, Object> metadata = AuditMetadata.of()
                .put("sourceCode", "print('secret solution')")
                .put("expected_output", "42")
                .build();

        assertThat(metadata).containsEntry("sourceCode", AuditMetadata.REDACTED);
        assertThat(metadata).containsEntry("expected_output", AuditMetadata.REDACTED);
    }

    /** Redaction must not swallow the ordinary fields an audit record is made of. */
    @Test
    void keepsOrdinaryFields() {
        Map<String, Object> metadata = AuditMetadata.of()
                .put("slug", "two-sum")
                .put("points", 100)
                .put("previousStatus", "DRAFT")
                .build();

        assertThat(metadata)
                .containsEntry("slug", "two-sum")
                .containsEntry("points", 100)
                .containsEntry("previousStatus", "DRAFT");
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void truncatesAnOverlongValueAndMarksIt() {
        String long_ = "x".repeat(AuditMetadata.MAX_VALUE_LENGTH + 500);

        Object stored = AuditMetadata.of().put("note", long_).build().get("note");

        assertThat((String) stored).hasSize(AuditMetadata.MAX_VALUE_LENGTH + 1);
        assertThat((String) stored).endsWith("…");
    }

    /** A guard against a loop populating a map, which is how blobs get into audit tables. */
    @Test
    void stopsAcceptingEntriesPastTheCeiling() {
        AuditMetadata.Builder builder = AuditMetadata.of();
        for (int i = 0; i < AuditMetadata.MAX_ENTRIES + 25; i++) {
            builder.put("key" + i, i);
        }

        assertThat(builder.build()).hasSize(AuditMetadata.MAX_ENTRIES);
    }

    // ------------------------------------------------------------------ shape

    /**
     * A null value is dropped rather than stored. "Absent" and "null" mean the same thing to
     * somebody reading an audit record, and a map full of nulls is harder to read.
     */
    @Test
    void dropsNullValuesAndNullKeys() {
        Map<String, Object> metadata = AuditMetadata.of()
                .put("present", "yes")
                .put("absent", null)
                .put(null, "orphan")
                .build();

        assertThat(metadata).containsOnlyKeys("present");
    }

    @Test
    void storesEnumsByName() {
        Map<String, Object> metadata = AuditMetadata.of().put("outcome", AuditOutcome.DENIED).build();

        assertThat(metadata).containsEntry("outcome", "DENIED");
    }

    @Test
    void recordsATransitionAsBothEnds() {
        Map<String, Object> metadata = AuditMetadata.of().transition("DRAFT", "PUBLISHED").build();

        assertThat(metadata)
                .containsEntry("previousStatus", "DRAFT")
                .containsEntry("newStatus", "PUBLISHED");
    }

    /** Built maps are handed straight to persistence; a mutable one would be a shared surprise. */
    @Test
    void producesAnImmutableMap() {
        Map<String, Object> metadata = AuditMetadata.of().put("slug", "x").build();

        assertThat(metadata.getClass().getName()).contains("Immutable");
    }
}
