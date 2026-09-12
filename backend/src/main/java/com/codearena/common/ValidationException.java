package com.codearena.common;

/**
 * A field-level rule failed somewhere the bean-validation annotations could not express
 * it — a password that contains the username, for instance. Maps to HTTP 400 with the
 * same {@code fieldErrors} shape that annotation-driven validation produces, so clients
 * handle both identically.
 */
public class ValidationException extends RuntimeException {

    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
