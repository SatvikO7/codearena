package com.codearena.auth;

import com.codearena.common.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders authentication and authorisation failures in the project's standard
 * {@link ApiErrorResponse} envelope.
 *
 * <p>These failures are raised by servlet filters, which run before Spring MVC and so
 * never reach {@code @RestControllerAdvice}. Without this, Spring Security would answer
 * a rejected API call with an HTML error page or an empty body — a different contract
 * from every other error the API produces, and one the frontend would have to special-case.
 *
 * <p>The distinction matters to clients: 401 means "you are not authenticated, logging
 * in may help", 403 means "you are authenticated but not allowed, logging in again will
 * not help".
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    private final ObjectMapper objectMapper;

    public SecurityErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No credentials, or credentials that did not establish a session. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, AUTHENTICATION_REQUIRED,
                "Authentication is required to access this resource");
    }

    /** Authenticated, but the role does not permit this operation. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpServletResponse.SC_FORBIDDEN, ACCESS_DENIED,
                "You do not have permission to access this resource");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       int status, String errorCode, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiErrorResponse.of(status, errorCode, message, request.getRequestURI()));
    }
}
