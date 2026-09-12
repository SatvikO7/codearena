package com.codearena.common;

/**
 * A request that cannot be satisfied because it collides with existing state, such as
 * registering a username somebody already holds. Maps to HTTP 409.
 *
 * @param errorCode stable, machine-readable code returned to the client
 */
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
