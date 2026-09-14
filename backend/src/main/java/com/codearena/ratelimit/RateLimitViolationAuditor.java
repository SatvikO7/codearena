package com.codearena.ratelimit;

import com.codearena.audit.AuditAction;
import com.codearena.audit.AuditMetadata;
import com.codearena.audit.AuditOutcome;
import com.codearena.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Records that a limit was reached — once per identity per cooldown, never once per request.
 *
 * <h2>Why this is not simply "audit every rejection"</h2>
 * The audit table is append-only and has no retention tooling: nothing can delete from it,
 * by design. A row per rejection would therefore hand every rate-limited caller a way to
 * grow a table nobody can prune, using the very requests the limiter just refused. The
 * control against flooding would have become the flood.
 *
 * <p>So the first rejection in a window writes one event and claims a marker in Redis;
 * every rejection until that marker expires writes nothing. With the default ten-minute
 * cooldown one identity can produce at most six rows an hour under one policy, whatever
 * they do.
 *
 * <h2>Which policies may produce events at all</h2>
 * Only those whose identity is bounded by something the system owns: a user id, or a
 * network peer. The per-account login throttle is excluded, because its identity derives
 * from a username typed by the caller — auditing it would let anyone mint rows by inventing
 * account names, which is the same unbounded-growth problem wearing a different hat. That
 * is why {@link RateLimitPolicy#auditable()} exists rather than this class simply auditing
 * everything it is told about.
 *
 * <h2>What the event contains</h2>
 * The policy and the category of identity. Not the address, not the attempted username. An
 * audit row is permanent, and writing a caller's network address into permanent storage to
 * record that they refreshed too quickly is not a trade worth making; when the caller is
 * authenticated the actor fields already name them, which is the case where knowing who
 * matters.
 */
@Component
public class RateLimitViolationAuditor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitViolationAuditor.class);

    /** Separate from the buckets: these keys are markers, not allowances. */
    static final String MARKER_PREFIX = "codearena:rl-audit:";

    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final Duration cooldown;

    public RateLimitViolationAuditor(StringRedisTemplate redis,
                                     AuditService auditService,
                                     RateLimitProperties properties) {
        this.redis = redis;
        this.auditService = auditService;
        this.cooldown = properties.violationAuditCooldown();
    }

    /**
     * Notes a rejection, and says whether it began a fresh burst.
     *
     * <p>The claim is a single {@code SET NX EX}: atomic, self-expiring, and one key per
     * identity rather than one per rejection. Several API instances rejecting the same
     * caller at the same moment still produce one event between them.
     *
     * <p>The marker is claimed for <em>every</em> policy, auditable or not, because the
     * caller uses the return value to decide whether to write a log line. Rejections are
     * unbounded — an attacker chooses how many they receive — so a line per rejection would
     * be a log flood by invitation. One line per identity per cooldown says the same thing
     * and costs nothing.
     *
     * <p>Nothing here may throw. This runs while a request is already being refused; an
     * exception would turn a 429 into a 500 and hand an attacker a more interesting result
     * than the one they were given. A failure is reported as "not the first", so an outage
     * produces no logging and no auditing rather than all of it.
     *
     * @return true if this is the first rejection recorded for this identity and policy
     *         within the cooldown
     */
    public boolean noteRejection(RateLimitPolicy policy, CallerIdentity identity) {
        if (cooldown.isZero()) {
            return false;
        }
        try {
            Boolean claimed = redis.opsForValue()
                    .setIfAbsent(markerKey(policy, identity), "1", cooldown);
            if (!Boolean.TRUE.equals(claimed)) {
                return false;
            }
            if (policy.auditable()) {
                auditService.recordIndependently(
                        AuditAction.RATE_LIMIT_EXCEEDED, AuditOutcome.DENIED,
                        null, null,
                        AuditMetadata.of()
                                .put("policy", policy.id())
                                .put("identityKind", identity.kind().name().toLowerCase(Locale.ROOT))
                                .put("cooldownSeconds", cooldown.toSeconds())
                                .build());
            }
            return true;

        } catch (RuntimeException e) {
            // Redis being unavailable is already reported by the limiter itself; losing the
            // marker means at worst a missed or duplicated event, which is not worth
            // failing a response over.
            log.warn("event=RATE_LIMIT_AUDIT_SKIPPED policy={} detail={}", policy.id(), e.toString());
            return false;
        }
    }

    static String markerKey(RateLimitPolicy policy, CallerIdentity identity) {
        return MARKER_PREFIX + policy.id() + ":" + identity.keySegment();
    }
}
