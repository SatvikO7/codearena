package com.codearena.audit;

/**
 * The kinds of thing an audit event can be about.
 *
 * <p>Constants rather than free strings so that the values written by the services and the
 * values a filter accepts cannot drift apart — a filter for {@code "contest"} that silently
 * matches nothing because the writer used {@code "CONTEST"} is a bad way to lose an
 * investigation.
 */
public final class AuditEntityType {

    public static final String USER = "USER";
    public static final String PROBLEM = "PROBLEM";
    public static final String CONTEST = "CONTEST";
    public static final String CONTEST_PROBLEM = "CONTEST_PROBLEM";
    public static final String SUBMISSION = "SUBMISSION";

    private AuditEntityType() {
    }
}
