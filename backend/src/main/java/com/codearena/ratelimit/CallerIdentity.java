package com.codearena.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What is being limited: one bounded, non-reversible token standing for a caller, an
 * account or a user.
 *
 * <h2>Why the value is hashed</h2>
 * Two of the three kinds derive from caller-supplied text — a network address that may
 * have come from a header, and a username typed into a login form. Writing either
 * verbatim into a Redis key would be two mistakes at once:
 *
 * <ul>
 *   <li><b>Unbounded key length.</b> An attacker choosing the key chooses how much memory
 *       each attempt costs. Hashing fixes every key at sixteen characters.</li>
 *   <li><b>A readable list of what was attempted.</b> {@code SCAN} over the keyspace would
 *       otherwise enumerate every username anyone had tried and every address they came
 *       from. The limiter needs to tell identities apart; it never needs to read them
 *       back, so it should not be able to.</li>
 * </ul>
 *
 * <p>Sixty-four bits of SHA-256. A collision merges two callers into one bucket, which
 * costs a little accuracy at astronomically low probability; it grants nobody anything.
 *
 * <p>The user kind is not hashed: a public id is already a bounded opaque UUID that the
 * API hands to the client anyway, and leaving it readable makes an operator's job easier.
 * Internal database ids never appear here, in keeping with the rest of the system.
 */
public record CallerIdentity(Kind kind, String value) {

    /**
     * The categories an identity can belong to.
     *
     * <p>Deliberately few and fixed: the kind appears in metric tags, so it must not be
     * able to multiply. It also becomes part of the Redis key, which stops a user id and
     * an address hash from ever colliding into a shared bucket.
     */
    public enum Kind {
        /** An authenticated user, by public id. */
        USER("u"),
        /** An unauthenticated caller, by hashed network identity. */
        CLIENT("c"),
        /** The account a login attempt names, by hashed identifier. */
        ACCOUNT("a");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** One character, because it is repeated in every key. */
        public String prefix() {
            return prefix;
        }
    }

    /** Length of the hex digest kept. Sixty-four bits: bounded, ample, unreadable. */
    static final int DIGEST_CHARS = 16;

    /**
     * Longest accepted input before hashing. An email address is capped at 320 characters
     * by RFC 3696 and an IPv6 address at 45; anything longer is not a real identifier and
     * is truncated rather than hashed in full, so a caller cannot make the limiter do
     * arbitrary work by sending a megabyte of username.
     */
    static final int MAX_INPUT_LENGTH = 320;

    public CallerIdentity {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a rate-limit identity must have a value");
        }
    }

    /** An authenticated user, by the public id the API already exposes. */
    public static CallerIdentity ofUser(String publicId) {
        return new CallerIdentity(Kind.USER, publicId);
    }

    /** An unauthenticated caller, by network identity. */
    public static CallerIdentity ofClient(String networkIdentity) {
        return new CallerIdentity(Kind.CLIENT, digest(networkIdentity));
    }

    /**
     * The account a login attempt is about.
     *
     * <p>Normalised before hashing so that {@code Alice}, {@code alice} and
     * {@code " alice "} share one bucket. Without that, a limit per account would be a
     * limit per spelling, and an attacker would get a fresh allowance for every variation
     * of capitalisation — which is to say, no limit at all.
     */
    public static CallerIdentity ofAccount(String identifier) {
        return new CallerIdentity(Kind.ACCOUNT, digest(normalise(identifier)));
    }

    /** Lower-cased and trimmed, in the same root locale the user lookup uses. */
    static String normalise(String identifier) {
        if (identifier == null) {
            return "";
        }
        return identifier.strip().toLowerCase(java.util.Locale.ROOT);
    }

    static String digest(String input) {
        String bounded = input == null ? "" : input;
        if (bounded.length() > MAX_INPUT_LENGTH) {
            bounded = bounded.substring(0, MAX_INPUT_LENGTH);
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(bounded.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; if it is genuinely absent the
            // process is not one that should be serving requests.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The identity as it appears inside a Redis key. */
    public String keySegment() {
        return kind.prefix() + ":" + value;
    }
}
