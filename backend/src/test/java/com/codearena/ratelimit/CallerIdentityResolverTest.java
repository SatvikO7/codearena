package com.codearena.ratelimit;

import com.codearena.auth.AuthenticatedUser;
import com.codearena.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a request is attributed to — and, more importantly, who it is <em>not</em>.
 *
 * <p>Every test here is about a bypass. A limiter is only as good as its notion of "one
 * caller": if a client can choose their own identity, they can have a fresh allowance
 * whenever they like, and the whole mechanism becomes decoration.
 */
class CallerIdentityResolverTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000a11c");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private CallerIdentityResolver resolver(boolean trustForwardedHeaders) {
        return new CallerIdentityResolver(properties(trustForwardedHeaders));
    }

    private static RateLimitProperties properties(boolean trustForwardedHeaders) {
        RateLimitProperties.Bucket bucket =
                new RateLimitProperties.Bucket(10, Duration.ofSeconds(1));
        return new RateLimitProperties(true, trustForwardedHeaders,
                bucket, bucket, bucket, bucket, bucket, bucket, bucket, Duration.ofMinutes(10));
    }

    // ------------------------------------------------------------ forged headers

    /**
     * The default, and the important one. {@code X-Forwarded-For} is a header any client
     * can set; believing it without a proxy that overwrites it means the attacker picks
     * their own bucket — a new one per request if they like.
     */
    @Test
    void ignoresForwardedHeadersByDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader(CallerIdentityResolver.FORWARDED_FOR, "10.0.0.1");

        assertThat(resolver(false).resolveClient(request))
                .isEqualTo(CallerIdentity.ofClient("203.0.113.9"));
    }

    /** The same caller inventing a different header each time still gets one bucket. */
    @Test
    void givesOneBucketToACallerWhoVariesTheForgedHeader() {
        CallerIdentityResolver resolver = resolver(false);

        CallerIdentity first = resolver.resolveClient(requestFrom("203.0.113.9", "1.1.1.1"));
        CallerIdentity second = resolver.resolveClient(requestFrom("203.0.113.9", "2.2.2.2"));

        assertThat(first).isEqualTo(second);
    }

    /**
     * When a proxy really is trusted, the <em>last</em> entry is the one it appended — the
     * peer it actually saw. Everything before it may have been sent by the client, so
     * reading the first entry, which is the usual mistake, is reading attacker input.
     */
    @Test
    void takesTheProxysOwnEntryRatherThanTheClientsWhenForwardingIsTrusted() {
        MockHttpServletRequest request = requestFrom("172.18.0.1",
                "198.51.100.7, 203.0.113.9");

        assertThat(resolver(true).resolveClient(request))
                .isEqualTo(CallerIdentity.ofClient("203.0.113.9"));
    }

    /** A trusted deployment with no header present falls back to the socket peer. */
    @Test
    void fallsBackToThePeerWhenNoForwardedHeaderIsPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.1");

        assertThat(resolver(true).resolveClient(request))
                .isEqualTo(CallerIdentity.ofClient("172.18.0.1"));
    }

    // ------------------------------------------------------------ key poisoning

    /**
     * The sanitiser is what keeps key cardinality tied to real network peers. Without it, a
     * trusted-proxy deployment would let a caller put arbitrary text in the header and mint
     * a bucket per request — unbounded Redis growth chosen by the attacker.
     *
     * <p>Note what the fallback is: the socket peer, not a shared unknown bucket. Sending
     * rubbish therefore leaves the caller exactly where they started, which is the outcome
     * that gives them no reason to try.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-address",
            "10.0.0.1; DROP TABLE users",
            "10.0.0.1\nX-Injected: yes",
            "../../etc/passwd",
            "{\"json\":\"value\"}",
            "codearena:rl:submission:u:someone-else"
    })
    void refusesAnythingThatIsNotAnAddressAndFallsBackToTheRealPeer(String hostile) {
        MockHttpServletRequest request = requestFrom("203.0.113.9", hostile);

        assertThat(resolver(true).resolveClient(request))
                .as("a header that is not an address buys nothing: the socket peer decides")
                .isEqualTo(CallerIdentity.ofClient("203.0.113.9"));
    }

    /**
     * And when there is no usable peer either, every such caller shares one bucket rather
     * than each minting their own — the safe direction to fail in, because the alternative
     * is unbounded key creation chosen by the attacker.
     */
    @Test
    void sharesOneBucketWhenNeitherTheHeaderNorThePeerIsUsable() {
        MockHttpServletRequest request = requestFrom("not-an-address", "also-not-an-address");

        assertThat(resolver(true).resolveClient(request))
                .isEqualTo(CallerIdentity.ofClient(CallerIdentityResolver.UNKNOWN));
    }

    /** A very long header value is a bucket-per-request attempt; it gets the shared bucket. */
    @Test
    void refusesAnOverlongAddress() {
        assertThat(CallerIdentityResolver.sanitiseAddress("1".repeat(500)))
                .isEqualTo(CallerIdentityResolver.UNKNOWN);
    }

    @Test
    void acceptsIpv6AndNormalisesItsCase() {
        assertThat(CallerIdentityResolver.sanitiseAddress("2001:DB8::A1"))
                .isEqualTo("2001:db8::a1");
    }

    @Test
    void treatsAMissingPeerAsOneSharedBucket() {
        assertThat(CallerIdentityResolver.sanitiseAddress(null))
                .isEqualTo(CallerIdentityResolver.UNKNOWN);
    }

    // ------------------------------------------------------------ authenticated callers

    /**
     * An authenticated caller is identified from the server-side session, which is the same
     * source authorisation reads. There is no request field that can name a different user,
     * so nobody can spend somebody else's allowance or escape their own.
     */
    @Test
    void identifiesAnAuthenticatedCallerByTheirSessionNotTheirRequest() {
        authenticateAs(ALICE);
        MockHttpServletRequest request = requestFrom("203.0.113.9", "10.0.0.1");
        request.addParameter("userId", "00000000-0000-4000-8000-00000000b0b0");

        assertThat(resolver(true).resolve(request))
                .isEqualTo(CallerIdentity.ofUser(ALICE.toString()));
    }

    /**
     * Client-scoped policies stay client-scoped even for a signed-in caller: registering
     * accounts while already logged in is still one client creating accounts.
     */
    @Test
    void keepsClientScopeForAnAuthenticatedCaller() {
        authenticateAs(ALICE);

        assertThat(resolver(false).resolveClient(requestFrom("203.0.113.9", null)))
                .isEqualTo(CallerIdentity.ofClient("203.0.113.9"));
    }

    @Test
    void fallsBackToTheClientWhenNobodyIsAuthenticated() {
        assertThat(resolver(false).resolve(requestFrom("203.0.113.9", null)))
                .isEqualTo(CallerIdentity.ofClient("203.0.113.9"));
    }

    private static MockHttpServletRequest requestFrom(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader(CallerIdentityResolver.FORWARDED_FOR, forwardedFor);
        }
        return request;
    }

    private static void authenticateAs(UUID publicId) {
        AuthenticatedUser user = new AuthenticatedUser(
                publicId, "alice", "hash", Role.USER, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }
}
