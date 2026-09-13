package com.codearena.audit;

/**
 * Carries the current request's id to code that has no access to the servlet request.
 *
 * <p>{@link AuditService} is called from services, not controllers, and threading an id
 * through every business method signature purely so an audit row can carry it would put a
 * cross-cutting concern into every domain API. A thread-local is the smaller intrusion.
 *
 * <p>It is set and cleared by {@link RequestIdFilter} in a {@code finally}, which matters:
 * servlet threads are pooled, and a value left behind would stamp the next, unrelated
 * request with this one's id — which is worse than no id at all, because it is a wrong
 * answer rather than a missing one.
 *
 * <p>Work that leaves the request thread — the queue publisher, the SSE subscriber, the
 * recovery sweeper — sees no id, and records none. That is correct: those events were not
 * caused by a request, and {@link ActorType#SYSTEM} says so.
 */
public final class RequestContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    /** The current request's id, or null outside a request. */
    public static String requestId() {
        return REQUEST_ID.get();
    }

    static void clear() {
        REQUEST_ID.remove();
    }
}
