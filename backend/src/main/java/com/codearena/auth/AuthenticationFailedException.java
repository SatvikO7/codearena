package com.codearena.auth;

import org.springframework.http.HttpStatus;

/**
 * A login attempt that was rejected for a reason the client should be told about, with a
 * message already vetted as safe to display.
 *
 * <p>Kept separate from Spring Security's own {@code AuthenticationException} hierarchy
 * on purpose: those exceptions carry internal detail such as which check failed, and
 * this type exists precisely to decide what the caller is allowed to learn. Bad
 * credentials and an unknown username both arrive here as the same code and message.
 */
public class AuthenticationFailedException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public AuthenticationFailedException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
