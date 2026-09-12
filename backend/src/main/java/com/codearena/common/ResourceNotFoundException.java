package com.codearena.common;

/**
 * The requested resource does not exist, or the caller is not entitled to know that it
 * does. Maps to HTTP 404.
 *
 * <p>The second case matters. A draft problem returns 404 rather than 403 to a normal
 * user: answering "forbidden" would confirm that a problem with that slug exists, which
 * is exactly the information an unreleased problem is meant to withhold.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String errorCode;

    public ResourceNotFoundException(String message) {
        this("RESOURCE_NOT_FOUND", message);
    }

    public ResourceNotFoundException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
