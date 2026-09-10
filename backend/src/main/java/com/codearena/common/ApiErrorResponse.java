package com.codearena.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape returned by every failing API call.
 *
 * <p>{@code error} is a stable, machine-readable code that clients may branch on;
 * {@code message} is human-readable and safe to display. Stack traces and internal
 * exception details are logged server-side and never serialised here.
 *
 * @param fieldErrors populated only for validation failures; omitted otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ApiErrorResponse validation(String message, String path, List<FieldViolation> violations) {
        return new ApiErrorResponse(Instant.now(), 400, "VALIDATION_FAILED", message, path, violations);
    }

    /** A single rejected input field. */
    public record FieldViolation(String field, String message) {
    }
}
