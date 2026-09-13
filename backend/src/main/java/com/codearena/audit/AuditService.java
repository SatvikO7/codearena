package com.codearena.audit;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The one place audit events are written.
 *
 * <h2>Two recording modes, and why both exist</h2>
 *
 * <p><b>{@link #record} joins the caller's transaction.</b> This is the mode for
 * administrative mutations, and it is what makes a success event trustworthy: the event and
 * the change it describes commit together or not at all. A contest cancellation that rolls
 * back cannot leave behind a record saying it happened, and — just as important — a
 * cancellation cannot commit without its audit row, because a failure to write the row fails
 * the transaction.
 *
 * <p>That second direction is the deliberate part. For a security-critical administrative
 * change, "the mutation succeeded but we could not say who did it" is not an acceptable
 * outcome, so the audit write is allowed to veto the mutation. The alternative — swallowing
 * the failure — produces a system that appears to be audited and is not.
 *
 * <p><b>{@link #recordIndependently} uses its own transaction.</b> For events that describe
 * something which has already happened outside any transaction of ours, or which must be
 * recorded even though the surrounding operation is failing:
 *
 * <ul>
 *   <li><b>A failed login.</b> There is no domain transaction to join — nothing was
 *       changed — and the request is about to end in a 401 thrown from a controller. Joining
 *       a transaction that is rolling back would discard precisely the record worth keeping,
 *       since a burst of failed logins is the clearest signal of an attack this system has.</li>
 *   <li><b>A denied request.</b> Refused by a servlet filter before any transaction exists.</li>
 * </ul>
 *
 * <p>Here a database failure is logged at ERROR and swallowed, because the alternative is
 * turning a failed login into a 500 and handing an attacker a way to tell existing accounts
 * from missing ones by the error they produce. That trade is stated rather than assumed:
 * <b>a lost failure-audit row is possible; a lost success-audit row is not.</b> See ADR-037.
 *
 * <h2>The actor is never supplied by a caller</h2>
 * It is read from the security context. No method here takes an actor, so no endpoint can
 * attribute an action to somebody else, and no request body has a field that would reach one.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Records an event in the caller's transaction.
     *
     * <p>{@link Propagation#MANDATORY} on purpose: this must be called from inside a
     * transaction, and a caller who forgets gets an immediate, loud failure rather than an
     * audit row that silently commits on its own and survives a rolled-back mutation. The
     * mistake is caught the first time the code runs, not during an incident.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, AuditOutcome outcome,
                       String entityType, String entityId, Map<String, Object> metadata) {
        repository.save(build(action, outcome, entityType, entityId, metadata));
    }

    /** Convenience for events with no particular target. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, AuditOutcome outcome, Map<String, Object> metadata) {
        record(action, outcome, null, null, metadata);
    }

    /**
     * Records an event in its own transaction, independent of anything around it.
     *
     * <p>{@link Propagation#REQUIRES_NEW} so the row survives a caller that is about to roll
     * back — which is the normal case for the failures this is used for.
     *
     * <p>A failure here is logged and swallowed. That is the documented trade for events
     * which must not be able to break the request they describe; see the class note.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditAction action, AuditOutcome outcome,
                                    String entityType, String entityId,
                                    Map<String, Object> metadata) {
        try {
            repository.save(build(action, outcome, entityType, entityId, metadata));
        } catch (RuntimeException e) {
            // Never silent: an audit log that is quietly failing to record denials is worse
            // than one that is obviously broken, so this is ERROR and names the action.
            log.error("event=AUDIT_WRITE_FAILED action={} outcome={} reason={}",
                    action, outcome, e.toString());
        }
    }

    /**
     * Records an event for a known actor, in its own transaction.
     *
     * <p>Logout needs both halves: there is no domain transaction to join, and by the time
     * the event is recorded the security context has deliberately been torn down — so the
     * actor cannot be resolved from it and has to be the one captured before the handler
     * ran. Resolving it afterwards would attribute every logout to ANONYMOUS.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependentlyFor(UUID actorUserId, String actorUsername, ActorType actorType,
                                       AuditAction action, AuditOutcome outcome,
                                       String entityType, String entityId,
                                       Map<String, Object> metadata) {
        try {
            repository.save(new AuditEvent(actorUserId, actorUsername, actorType, action, outcome,
                    entityType, entityId, RequestContext.requestId(), metadata));
        } catch (RuntimeException e) {
            log.error("event=AUDIT_WRITE_FAILED action={} outcome={} reason={}",
                    action, outcome, e.toString());
        }
    }

    /**
     * Records an event attributed to a specific account rather than to the security context.
     *
     * <p>Used only where the actor is known but not yet, or no longer, authenticated:
     * registration creates the account before any session exists, and logout tears the
     * context down. The account is one the server has just resolved itself — never an
     * identifier taken from a request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFor(UUID actorUserId, String actorUsername, ActorType actorType,
                          AuditAction action, AuditOutcome outcome,
                          String entityType, String entityId, Map<String, Object> metadata) {
        repository.save(new AuditEvent(actorUserId, actorUsername, actorType, action, outcome,
                entityType, entityId, RequestContext.requestId(), metadata));
    }

    // ------------------------------------------------------------------ internals

    private AuditEvent build(AuditAction action, AuditOutcome outcome,
                             String entityType, String entityId, Map<String, Object> metadata) {
        Actor actor = currentActor();
        return new AuditEvent(actor.userId(), actor.username(), actor.type(),
                action, outcome, entityType, entityId, RequestContext.requestId(), metadata);
    }

    /**
     * Resolves who is acting, from the security context alone.
     *
     * <p>An unauthenticated caller is ANONYMOUS with a null id — not a guessed identity, and
     * not an identifier lifted from the request. Spring Security represents "nobody" with an
     * anonymous token rather than a null authentication, which is why the principal type is
     * checked rather than the authentication's presence.
     */
    private Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            return new Actor(null, null, ActorType.ANONYMOUS);
        }
        ActorType type = principal.getRole() == Role.ADMIN ? ActorType.ADMIN : ActorType.USER;
        return new Actor(principal.getPublicId(), principal.getUsername(), type);
    }

    /** Convenience for the common case of a UUID-identified entity. */
    public static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private record Actor(UUID userId, String username, ActorType type) {
    }
}
