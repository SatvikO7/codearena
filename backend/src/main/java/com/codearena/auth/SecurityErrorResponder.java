package com.codearena.auth;

import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import com.codearena.common.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
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

    /** Only denials on this prefix are audited; see {@link #handle}. */
    private static final String ADMIN_PREFIX = "/api/admin/";

    private final ObjectMapper objectMapper;
    private final ObjectProvider<AuditService> auditService;

    /**
     * The audit service is injected lazily.
     *
     * <p>This component is part of the security filter chain, which Spring builds early;
     * asking for a transactional service directly creates a dependency cycle between the
     * security configuration and the persistence layer. An {@link ObjectProvider} defers
     * the lookup to the moment a denial actually happens.
     */
    public SecurityErrorResponder(ObjectMapper objectMapper, ObjectProvider<AuditService> auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    /** No credentials, or credentials that did not establish a session. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, AUTHENTICATION_REQUIRED,
                "Authentication is required to access this resource");
    }

    /**
     * Authenticated, but the role does not permit this operation.
     *
     * <p>Denials on the <b>administrative</b> surface are audited. One is a misclick; a
     * pattern of them is somebody probing what they can reach, and that is precisely what
     * the audit log exists to make visible.
     *
     * <p>Denials elsewhere are not audited. They are ordinary authorisation working as
     * designed on the normal API, and recording every one of them would bury the
     * administrative denials that matter under noise — the failure mode that makes an audit
     * log unusable rather than merely large.
     *
     * <p>Recorded in its own transaction: this runs in a servlet filter, before any
     * transaction exists, and the request is about to end in a 403 regardless.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (request.getRequestURI() != null && request.getRequestURI().startsWith(ADMIN_PREFIX)) {
            auditService.ifAvailable(service -> service.recordIndependently(
                    AuditAction.ADMIN_ACCESS_DENIED, AuditOutcome.DENIED, null, null,
                    AuditMetadata.of()
                            .put("method", request.getMethod())
                            .put("path", request.getRequestURI())
                            .build()));
        }
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
