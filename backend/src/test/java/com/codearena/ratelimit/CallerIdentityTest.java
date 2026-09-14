package com.codearena.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What ends up in a Redis key, and what must never.
 *
 * <p>These are security tests dressed as string tests. The key is chosen by the caller in
 * two of the three cases, so the properties asserted here — bounded length, no readable
 * input, one bucket per account however it is spelled — are what stop an attacker from
 * choosing how much memory they consume and from getting a fresh allowance for free.
 */
class CallerIdentityTest {

    @Test
    void keepsAUserPublicIdReadableBecauseItIsAlreadyPublic() {
        CallerIdentity identity = CallerIdentity.ofUser("8b1f2c3d-0000-4000-8000-000000000001");

        assertThat(identity.kind()).isEqualTo(CallerIdentity.Kind.USER);
        assertThat(identity.value()).isEqualTo("8b1f2c3d-0000-4000-8000-000000000001");
    }

    /** The raw address is the input to the hash and must not survive into the key. */
    @Test
    void neverWritesANetworkAddressIntoTheKey() {
        CallerIdentity identity = CallerIdentity.ofClient("203.0.113.17");

        assertThat(identity.value()).doesNotContain("203.0.113.17");
        assertThat(identity.value()).hasSize(CallerIdentity.DIGEST_CHARS);
        assertThat(identity.keySegment()).isEqualTo("c:" + identity.value());
    }

    /**
     * A username typed into a login form must not be readable back out of Redis. Otherwise
     * anybody who could list the keyspace could list every account anyone had tried.
     */
    @Test
    void neverWritesAnAttemptedUsernameIntoTheKey() {
        CallerIdentity identity = CallerIdentity.ofAccount("alice@example.com");

        assertThat(identity.value()).doesNotContain("alice");
        assertThat(identity.value()).doesNotContain("example");
        assertThat(identity.value()).hasSize(CallerIdentity.DIGEST_CHARS);
    }

    /**
     * The important one. If casing or whitespace produced different buckets, a limit "per
     * account" would be a limit per spelling, and an attacker would get ten fresh guesses
     * for every variation of capitalisation they could think of.
     */
    @ParameterizedTest
    @ValueSource(strings = {"alice", "Alice", "ALICE", "  alice  ", "\talice\n"})
    void collapsesEverySpellingOfAnAccountIntoOneBucket(String spelling) {
        assertThat(CallerIdentity.ofAccount(spelling))
                .isEqualTo(CallerIdentity.ofAccount("alice"));
    }

    @Test
    void tellsDifferentAccountsApart() {
        assertThat(CallerIdentity.ofAccount("alice"))
                .isNotEqualTo(CallerIdentity.ofAccount("bob"));
    }

    /**
     * A caller who sends a megabyte of username should not make the limiter hash a
     * megabyte, and must not make the key any longer than anybody else's.
     */
    @Test
    void boundsTheWorkAndTheKeyWhateverTheInputSize() {
        String enormous = "x".repeat(1_000_000);

        CallerIdentity identity = CallerIdentity.ofAccount(enormous);

        assertThat(identity.value()).hasSize(CallerIdentity.DIGEST_CHARS);
    }

    /**
     * Truncation happens before hashing, so two inputs that agree for the first
     * {@link CallerIdentity#MAX_INPUT_LENGTH} characters share a bucket. Asserted rather
     * than merely tolerated: it is the deliberate cost of bounding the work, and it can
     * only ever merge allowances, never create extra ones.
     */
    @Test
    void treatsInputsBeyondTheBoundAsTheSameIdentity() {
        String base = "a".repeat(CallerIdentity.MAX_INPUT_LENGTH);

        assertThat(CallerIdentity.ofAccount(base + "one"))
                .isEqualTo(CallerIdentity.ofAccount(base + "two"));
    }

    /**
     * The kind is part of the key. Without it a user's public id and an address hash could
     * in principle name the same bucket, and one caller's allowance would silently be
     * another's.
     */
    @Test
    void separatesKindsWithinTheKey() {
        assertThat(CallerIdentity.ofUser("abc").keySegment()).startsWith("u:");
        assertThat(CallerIdentity.ofClient("abc").keySegment()).startsWith("c:");
        assertThat(CallerIdentity.ofAccount("abc").keySegment()).startsWith("a:");
    }

    /** An identity with no value would silently merge every caller into one bucket. */
    @Test
    void refusesAnEmptyIdentity() {
        assertThatThrownBy(() -> new CallerIdentity(CallerIdentity.Kind.USER, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A null identifier is hashed as empty rather than throwing inside a login attempt. */
    @Test
    void handlesAMissingAccountIdentifier() {
        assertThat(CallerIdentity.ofAccount(null).value()).hasSize(CallerIdentity.DIGEST_CHARS);
    }
}
