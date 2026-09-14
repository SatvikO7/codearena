package com.codearena.ratelimit;

import com.codearena.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Works out whose allowance a request should be drawn from.
 *
 * <h2>Authenticated requests are limited by user</h2>
 * Never by anything the client controls. The user is taken from the server-side session
 * through {@code SecurityContextHolder}, which is the same source authorisation uses, so
 * there is no way to present one identity to the authorisation check and a different one
 * to the limiter. A body field, a header or a query parameter naming a user would be a
 * bypass by design.
 *
 * <h2>Anonymous requests, and the honest limits of client identity here</h2>
 * Only two endpoints accept anonymous callers — login and registration — and for those the
 * limiter needs some notion of "one client". This is where the deployment matters, so it is
 * worth stating plainly rather than assuming:
 *
 * <ul>
 *   <li><b>{@code X-Forwarded-For} is ignored by default.</b> Any client can send it, so
 *       trusting it without a proxy that overwrites it means an attacker picks their own
 *       bucket, and a new one per request — a limiter that cannot be reached.</li>
 *   <li><b>There is no reverse proxy in front of this API in the shipped deployment.</b>
 *       nginx serves the built frontend only; the browser calls the API directly. So
 *       {@code getRemoteAddr()} is the peer socket, which is the honest answer.</li>
 *   <li><b>Behind Docker's published port that peer is frequently the bridge gateway</b>,
 *       not the real client, so anonymous callers can collapse into a single shared
 *       identity. That is a real weakness and it is why the login policy does not lean on
 *       this: {@link RateLimitPolicy#LOGIN_ORIGIN} is a generous flood cap, and the control
 *       that actually stops guessing is {@link RateLimitPolicy#LOGIN_ACCOUNT}, which is
 *       keyed on the account rather than on the caller and so does not collapse.</li>
 * </ul>
 *
 * <p>{@code codearena.rate-limit.trust-forwarded-headers} turns the header on for a
 * deployment that really does terminate at a proxy which overwrites it. It is off unless
 * an operator asserts otherwise, because the failure of guessing wrong is silent.
 */
@Component
public class CallerIdentityResolver {

    /**
     * Used when no address can be determined at all. A constant, so such requests share
     * one bucket instead of each creating their own — the safe direction to fail in.
     */
    static final String UNKNOWN = "unknown";

    static final String FORWARDED_FOR = "X-Forwarded-For";

    /** IPv6 with a zone index is the longest plausible address. */
    private static final int MAX_ADDRESS_LENGTH = 64;

    private final boolean trustForwardedHeaders;

    public CallerIdentityResolver(RateLimitProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
    }

    /**
     * The user for an authenticated request, the client otherwise.
     *
     * <p>Used for policies that apply to anyone: an administrator reading the audit log
     * draws on their own allowance exactly as a user submitting code draws on theirs.
     */
    public CallerIdentity resolve(HttpServletRequest request) {
        AuthenticatedUser user = authenticatedUser();
        return user != null
                ? CallerIdentity.ofUser(user.getPublicId().toString())
                : CallerIdentity.ofClient(networkIdentity(request));
    }

    /** The calling client, ignoring any session. For policies about anonymous traffic. */
    public CallerIdentity resolveClient(HttpServletRequest request) {
        return CallerIdentity.ofClient(networkIdentity(request));
    }

    private AuthenticatedUser authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    /**
     * The network identity to limit an anonymous caller by.
     *
     * <p>When forwarding headers are trusted, the <em>last</em> entry of
     * {@code X-Forwarded-For} is used, not the first. A proxy appends the peer it actually
     * saw, so the final entry is the one the proxy wrote; every earlier entry may have been
     * supplied by the client. Reading the first entry — the common mistake — is reading
     * attacker-controlled text.
     */
    String networkIdentity(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                String nearest = hops[hops.length - 1];
                String sanitised = sanitiseAddress(nearest);
                if (!UNKNOWN.equals(sanitised)) {
                    return sanitised;
                }
            }
        }
        return sanitiseAddress(request.getRemoteAddr());
    }

    /**
     * Reduces an address to something safe to use as a bucket identity.
     *
     * <p>Bounded in length and restricted to the characters an address can contain, so a
     * caller cannot smuggle arbitrary text into the hash input — which is the step that
     * keeps key cardinality tied to real network peers rather than to attacker imagination.
     * Anything unrecognisable becomes the shared {@link #UNKNOWN} bucket rather than a
     * bucket of its own.
     */
    static String sanitiseAddress(String candidate) {
        if (candidate == null) {
            return UNKNOWN;
        }
        String trimmed = candidate.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_ADDRESS_LENGTH) {
            return UNKNOWN;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char character = trimmed.charAt(i);
            boolean allowed = (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == '.' || character == ':' || character == '%';
            if (!allowed) {
                return UNKNOWN;
            }
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
