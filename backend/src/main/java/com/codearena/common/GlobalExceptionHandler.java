package com.codearena.common;

import com.codearena.auth.AuthenticationFailedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates exceptions into the {@link ApiErrorResponse} contract.
 *
 * <p>Two rules govern everything here:
 * <ol>
 *   <li>Clients receive a stable error code and a message that leaks nothing about
 *       internals (no class names, SQL, stack traces or configuration).</li>
 *   <li>Anything unexpected is logged at ERROR with its full stack trace so that
 *       failures remain diagnosable server-side.</li>
 * </ol>
 *
 * <p>Handlers for domain-specific exceptions are added by the modules that define
 * them; this class holds only the framework-level and catch-all cases.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * A rejected login. The status and message were chosen deliberately by the
     * authentication layer; this only renders them.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailed(
            AuthenticationFailedException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus()).body(ApiErrorResponse.of(
                ex.getStatus().value(), ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    /** A duplicate username or email. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(
            ConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                409, ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A rule the annotations could not express. Rendered in the same shape as
     * annotation-driven validation so clients need only one code path for both.
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainValidation(
            ValidationException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.validation(
                "Request validation failed", request.getRequestURI(),
                List.of(new ApiErrorResponse.FieldViolation(ex.getField(), ex.getMessage()))));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErrorResponse.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.validation("Request validation failed", request.getRequestURI(), violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                400, "MALFORMED_REQUEST", "Request body could not be parsed", request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of(
                404, "RESOURCE_NOT_FOUND", "No handler found for the requested path",
                request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiErrorResponse.of(
                405, "METHOD_NOT_ALLOWED", "HTTP method is not supported for this endpoint",
                request.getRequestURI()));
    }

    /**
     * Catch-all. The client learns only that something went wrong; the operator gets
     * the stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while serving {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(
                500, "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI()));
    }
}
