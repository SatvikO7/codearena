package com.codearena.rating;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The mathematics, checked against a calculator.
 *
 * <h2>Every expected value in this file was worked out by hand</h2>
 * That is the whole point of the class being pure. A test that asserts whatever the
 * implementation currently returns proves only that the implementation has not changed — it
 * would pass just as happily against a formula that was wrong from the first commit. Each
 * case below therefore shows its arithmetic in a comment, and the number in the assertion is
 * the number that arithmetic produces.
 *
 * <p>Ratings are the one part of this system where a subtle error is invisible: nobody can
 * look at "−17" and tell whether it should have been −18. The only defence is arithmetic
 * somebody can repeat.
 */
class RatingCalculatorTest {

    /**
     * Fixed ids in known order.
     *
     * <p>The calculator sorts by public id, so fixing them makes the output order predictable
     * and lets a test reason about which change belongs to whom. A..E sort ascending.
     */
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID E = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    /** An established competitor: past the provisional window, below the elite band. K = 20. */
    private static RatingCalculator.Participant established(UUID id, int rating, int rank) {
        return new RatingCalculator.Participant(id, rating, 25, rank, 0, 0);
    }

    /** A competitor in their first contest. K = 40. */
    private static RatingCalculator.Participant newcomer(UUID id, int rating, int rank) {
        return new RatingCalculator.Participant(id, rating, 0, rank, 0, 0);
    }

    private static Map<UUID, RatingCalculator.Change> byUser(List<RatingCalculator.Change> changes) {
        return changes.stream().collect(Collectors.toMap(
                RatingCalculator.Change::userPublicId, Function.identity()));
    }

    // ================================================================= expected score

    @Nested
    @DisplayName("The expected score")
    class ExpectedScore {

        @Test
        @DisplayName("equal ratings expect exactly half")
        void equalRatingsAreEven() {
            // E = 1 / (1 + 10^(0/400)) = 1 / (1 + 1) = 0.5, exactly.
            assertThat(RatingCalculator.expectedScore(1500, 1500)).isEqualTo(0.5);
            assertThat(RatingCalculator.expectedScore(800, 800)).isEqualTo(0.5);
            assertThat(RatingCalculator.expectedScore(2900, 2900)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("400 points is the 10:1 expectation Elo is defined around")
        void fourHundredPointsIsTenToOne() {
            // E = 1 / (1 + 10^(-400/400)) = 1 / (1 + 0.1) = 1/1.1 = 0.909090...
            assertThat(RatingCalculator.expectedScore(1900, 1500))
                    .isCloseTo(10.0 / 11.0, within(1e-12));
            // And the other way: 1 / (1 + 10^(400/400)) = 1/11 = 0.090909...
            assertThat(RatingCalculator.expectedScore(1500, 1900))
                    .isCloseTo(1.0 / 11.0, within(1e-12));
        }

        @Test
        @DisplayName("800 points is 100:1")
        void eightHundredPointsIsAHundredToOne() {
            // E = 1 / (1 + 10^(-800/400)) = 1 / (1 + 0.01) = 100/101.
            assertThat(RatingCalculator.expectedScore(2300, 1500))
                    .isCloseTo(100.0 / 101.0, within(1e-12));
        }

        @Test
        @DisplayName("a pair's expectations sum to one")
        void expectationsAreComplementary() {
            assertThat(RatingCalculator.expectedScore(1731, 1204)
                     + RatingCalculator.expectedScore(1204, 1731))
                    .isCloseTo(1.0, within(1e-12));
        }
    }

    // ================================================================= two competitors

    @Nested
    @DisplayName("Two competitors")
    class HeadToHead {

        @Test
        @DisplayName("evenly matched: the winner takes ten, the loser gives ten")
        void evenlyMatchedEstablished() {
            // n = 2, so one opponent each.
            //   E = 0.5 for both (equal ratings).
            //   Winner:  A = 1/1 = 1     Δ = round(20 × (1   − 0.5)) = round(+10) = +10
            //   Loser:   A = 0/1 = 0     Δ = round(20 × (0   − 0.5)) = round(−10) = −10
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1500, 1),
                    established(B, 1500, 2))));

            assertThat(changes.get(A).ratingChange()).isEqualTo(10);
            assertThat(changes.get(A).ratingAfter()).isEqualTo(1510);
            assertThat(changes.get(B).ratingChange()).isEqualTo(-10);
            assertThat(changes.get(B).ratingAfter()).isEqualTo(1490);

            assertThat(changes.get(A).expectedScore()).isEqualTo(0.5);
            assertThat(changes.get(A).actualScore()).isEqualTo(1.0);
            assertThat(changes.get(A).kFactor()).isEqualTo(20);
        }

        @Test
        @DisplayName("newcomers move twice as far, because their rating is mostly a guess")
        void newcomersMoveFaster() {
            //   Same expectation, K = 40 instead of 20.
            //   Winner:  Δ = round(40 × (1 − 0.5)) = +20
            //   Loser:   Δ = round(40 × (0 − 0.5)) = −20
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    newcomer(A, 1500, 1),
                    newcomer(B, 1500, 2))));

            assertThat(changes.get(A).ratingChange()).isEqualTo(20);
            assertThat(changes.get(B).ratingChange()).isEqualTo(-20);
            assertThat(changes.get(A).kFactor()).isEqualTo(40);
        }

        @Test
        @DisplayName("a tie between equals moves nobody")
        void tieBetweenEqualsIsNeutral() {
            //   A = 0.5 (one tie, worth a half), E = 0.5. Δ = round(20 × 0) = 0.
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1500, 1),
                    established(B, 1500, 1))));

            assertThat(changes.get(A).ratingChange()).isZero();
            assertThat(changes.get(B).ratingChange()).isZero();
            assertThat(changes.get(A).ratingAfter()).isEqualTo(1500);
        }

        @Test
        @DisplayName("the expected result barely moves either rating")
        void favouriteWinsAndGainsLittle() {
            //   1900 vs 1500. E(high) = 10/11 = 0.909090..., E(low) = 1/11 = 0.090909...
            //   High wins: Δ = round(20 × (1 − 0.909090...)) = round(+1.8181...) = +2
            //   Low loses: Δ = round(20 × (0 − 0.090909...)) = round(−1.8181...) = −2
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1900, 1),
                    established(B, 1500, 2))));

            assertThat(changes.get(A).ratingChange()).isEqualTo(2);
            assertThat(changes.get(A).ratingAfter()).isEqualTo(1902);
            assertThat(changes.get(B).ratingChange()).isEqualTo(-2);
            assertThat(changes.get(B).ratingAfter()).isEqualTo(1498);
        }

        @Test
        @DisplayName("the upset moves both a long way")
        void upsetMovesBothFar() {
            //   Same pair, opposite result.
            //   Low wins:   Δ = round(20 × (1 − 0.090909...)) = round(+18.1818...) = +18
            //   High loses: Δ = round(20 × (0 − 0.909090...)) = round(−18.1818...) = −18
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1900, 2),
                    established(B, 1500, 1))));

            assertThat(changes.get(B).ratingChange()).isEqualTo(18);
            assertThat(changes.get(B).ratingAfter()).isEqualTo(1518);
            assertThat(changes.get(A).ratingChange()).isEqualTo(-18);
            assertThat(changes.get(A).ratingAfter()).isEqualTo(1882);
        }
    }

    // ================================================================= larger fields

    @Nested
    @DisplayName("A field of several")
    class Fields {

        @Test
        @DisplayName("three equals finishing 1-2-3 gain +10, 0, −10")
        void threeEqualsSpreadEvenly() {
            // n = 3, two opponents each, everybody 1500 so E = (0.5 + 0.5)/2 = 0.5.
            //   1st: beats both      A = 2/2 = 1.0   Δ = round(20 × (1.0 − 0.5)) = +10
            //   2nd: one of each     A = 1/2 = 0.5   Δ = round(20 × (0.5 − 0.5)) =   0
            //   3rd: beats nobody    A = 0/2 = 0.0   Δ = round(20 × (0.0 − 0.5)) = −10
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1500, 1),
                    established(B, 1500, 2),
                    established(C, 1500, 3))));

            assertThat(changes.get(A).ratingChange()).isEqualTo(10);
            assertThat(changes.get(B).ratingChange()).isZero();
            assertThat(changes.get(C).ratingChange()).isEqualTo(-10);
        }

        @Test
        @DisplayName("a tie for first splits the pair's gain, and the loser still pays ten")
        void tieForFirst() {
            // Ranks 1, 1, 3 — competition ranking, so second place does not exist.
            //   A and B: one tie (0.5) plus one win (1.0)  →  A = 1.5/2 = 0.75
            //            Δ = round(20 × (0.75 − 0.5)) = round(+5) = +5
            //   C:       A = 0/2 = 0  →  Δ = round(20 × (0 − 0.5)) = −10
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1500, 1),
                    established(B, 1500, 1),
                    established(C, 1500, 3))));

            assertThat(changes.get(A).ratingChange()).isEqualTo(5);
            assertThat(changes.get(B).ratingChange()).isEqualTo(5);
            assertThat(changes.get(C).ratingChange()).isEqualTo(-10);
            assertThat(changes.get(A).actualScore()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("a tie for last splits the loss")
        void tieForLast() {
            // Ranks 1, 2, 2.
            //   A: two wins                  A = 2/2 = 1.0   Δ = round(20 × 0.5)   = +10
            //   B: one loss, one tie         A = 0.5/2 = 0.25 Δ = round(20 × −0.25) = −5
            //   C: same as B                                  Δ = −5
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1500, 1),
                    established(B, 1500, 2),
                    established(C, 1500, 2))));

            assertThat(changes.get(A).ratingChange()).isEqualTo(10);
            assertThat(changes.get(B).ratingChange()).isEqualTo(-5);
            assertThat(changes.get(C).ratingChange()).isEqualTo(-5);
        }

        @Test
        @DisplayName("everybody tying moves nobody at all")
        void anEntirelyTiedFieldIsNeutral() {
            // Every pairing is a tie: A = 1.0 (four halves over four opponents) = 0.5 = E.
            List<RatingCalculator.Change> changes = RatingCalculator.compute(List.of(
                    established(A, 1500, 1), established(B, 1500, 1),
                    established(C, 1500, 1), established(D, 1500, 1),
                    established(E, 1500, 1)));

            assertThat(changes).allSatisfy(change -> {
                assertThat(change.ratingChange()).isZero();
                assertThat(change.actualScore()).isEqualTo(0.5);
            });
        }

        @Test
        @DisplayName("a newcomer beating a field of veterans gains far more than they lose")
        void newcomerBeatingVeterans() {
            // A is unrated-but-seeded at 1500 with K = 40; B, C, D are 1700 veterans, K = 20.
            //   E_A = ( 3 × E(1500, 1700) ) / 3 = E(1500, 1700)
            //       = 1 / (1 + 10^(200/400)) = 1 / (1 + 10^0.5) = 1 / 4.16227766... = 0.240253...
            //   A wins all three: A_A = 1.
            //   Δ_A = round(40 × (1 − 0.240253...)) = round(40 × 0.759746...) = round(30.389...) = +30
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    newcomer(A, 1500, 1),
                    established(B, 1700, 2),
                    established(C, 1700, 3),
                    established(D, 1700, 4))));

            assertThat(changes.get(A).expectedScore()).isCloseTo(0.2402530733, within(1e-9));
            assertThat(changes.get(A).ratingChange()).isEqualTo(30);
            assertThat(changes.get(A).ratingAfter()).isEqualTo(1530);
            // The veterans, moving at half the speed, do not collectively pay for it. Rating
            // is not conserved and this is the documented reason why.
            assertThat(changes.get(B).ratingChange() + changes.get(C).ratingChange()
                     + changes.get(D).ratingChange()).isNotEqualTo(-30);
        }
    }

    // ================================================================= K-factor

    @Nested
    @DisplayName("The K-factor")
    class KFactor {

        @Test
        @DisplayName("provisional for the first five contests, established from the sixth")
        void provisionalWindow() {
            for (int completed = 0; completed < RatingCalculator.PROVISIONAL_CONTESTS; completed++) {
                assertThat(RatingCalculator.kFactorFor(
                        new RatingCalculator.Participant(A, 1500, completed, 1, 0, 0)))
                        .as("after %d rated contests", completed)
                        .isEqualTo(RatingCalculator.K_PROVISIONAL);
            }
            assertThat(RatingCalculator.kFactorFor(
                    new RatingCalculator.Participant(A, 1500, RatingCalculator.PROVISIONAL_CONTESTS, 1, 0, 0)))
                    .isEqualTo(RatingCalculator.K_ESTABLISHED);
        }

        @Test
        @DisplayName("the elite band starts exactly at 2400")
        void eliteBoundary() {
            assertThat(RatingCalculator.kFactorFor(
                    new RatingCalculator.Participant(A, 2399, 50, 1, 0, 0)))
                    .isEqualTo(RatingCalculator.K_ESTABLISHED);
            assertThat(RatingCalculator.kFactorFor(
                    new RatingCalculator.Participant(A, 2400, 50, 1, 0, 0)))
                    .isEqualTo(RatingCalculator.K_ELITE);
        }

        @Test
        @DisplayName("provisional beats elite: a newcomer rated highly still moves fast")
        void provisionalTakesPrecedenceOverElite() {
            // Otherwise a competitor seeded high on their first contest would be pinned
            // there by K = 10, and the seeding would become self-confirming.
            assertThat(RatingCalculator.kFactorFor(
                    new RatingCalculator.Participant(A, 2800, 1, 1, 0, 0)))
                    .isEqualTo(RatingCalculator.K_PROVISIONAL);
        }
    }

    // ================================================================= rounding

    @Nested
    @DisplayName("Rounding")
    class Rounding {

        @Test
        @DisplayName("half rounds away from zero, symmetrically")
        void halfRoundsAwayFromZero() {
            // The reason this is BigDecimal HALF_UP rather than Math.round: Math.round
            // computes floor(x + 0.5), so it would round −2.5 to −2 and quietly favour
            // whoever is losing points.
            assertThat(RatingCalculator.round(2.5)).isEqualTo(3);
            assertThat(RatingCalculator.round(-2.5)).isEqualTo(-3);
            assertThat(RatingCalculator.round(0.5)).isEqualTo(1);
            assertThat(RatingCalculator.round(-0.5)).isEqualTo(-1);
        }

        @Test
        @DisplayName("below a half rounds to nothing, in both directions")
        void smallChangesRoundToZero() {
            assertThat(RatingCalculator.round(0.49)).isZero();
            assertThat(RatingCalculator.round(-0.49)).isZero();
        }

        @Test
        @DisplayName("an overwhelming favourite can gain literally nothing")
        void anOverwhelmingFavouriteGainsNothing() {
            //   2400 vs 1000 — a 1400-point gap. E(high) = 1/(1 + 10^(-3.5)) = 0.999684...
            //   K is 10 in the elite band, so Δ = round(10 × 0.000316...) = round(0.00316) = 0.
            //   That is correct and not a bug: beating somebody 1400 points below you is
            //   worth no information about your rating.
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 2400, 1),
                    established(B, 1000, 2))));

            assertThat(changes.get(A).ratingChange()).isZero();
            assertThat(changes.get(A).ratingAfter()).isEqualTo(2400);
            assertThat(changes.get(A).kFactor()).isEqualTo(RatingCalculator.K_ELITE);
        }
    }

    // ================================================================= invariants

    @Nested
    @DisplayName("Invariants")
    class Invariants {

        @Test
        @DisplayName("before rounding, the system is exactly zero-sum")
        void expectationsAndResultsBalance() {
            // Σ A_i = Σ E_i, because both count each unordered pair exactly once. This is
            // the property the whole formula rests on; if it ever failed, rating would be
            // created or destroyed by the act of holding a contest.
            List<RatingCalculator.Participant> field = List.of(
                    established(A, 1832, 1),
                    newcomer(B, 1500, 2),
                    established(C, 2410, 3),
                    established(D, 1204, 4),
                    newcomer(E, 1500, 4));

            List<RatingCalculator.Change> changes = RatingCalculator.compute(field);

            double expected = changes.stream()
                    .mapToDouble(RatingCalculator.Change::expectedScore).sum();
            double actual = changes.stream()
                    .mapToDouble(RatingCalculator.Change::actualScore).sum();

            assertThat(actual).isCloseTo(expected, within(1e-9));
        }

        @Test
        @DisplayName("ratingAfter is always ratingBefore plus ratingChange")
        void arithmeticIsSelfConsistent() {
            // The database asserts this too, as a CHECK constraint. Both, because a history
            // row whose three numbers disagree is a row nobody can trust.
            List<RatingCalculator.Change> changes = RatingCalculator.compute(randomField(60));

            assertThat(changes).allSatisfy(change ->
                    assertThat(change.ratingAfter())
                            .isEqualTo(change.ratingBefore() + change.ratingChange()));
        }

        @Test
        @DisplayName("no change can exceed the participant's own K")
        void changesAreBoundedByK() {
            // |A − E| ≤ 1, so |Δ| ≤ K. This is what makes the system stable: no single
            // contest can move anybody by an arbitrary amount, however extreme the field.
            List<RatingCalculator.Change> changes = RatingCalculator.compute(randomField(80));

            assertThat(changes).allSatisfy(change ->
                    assertThat(Math.abs(change.ratingChange()))
                            .isLessThanOrEqualTo(change.kFactor()));
        }

        @Test
        @DisplayName("scores stay within [0, 1]")
        void scoresAreFractions() {
            List<RatingCalculator.Change> changes = RatingCalculator.compute(randomField(40));

            assertThat(changes).allSatisfy(change -> {
                assertThat(change.expectedScore()).isBetween(0.0, 1.0);
                assertThat(change.actualScore()).isBetween(0.0, 1.0);
            });
        }

        @Test
        @DisplayName("finishing ahead of somebody never earns less than finishing behind them")
        void betterRankNeverEarnsLess() {
            // Two identically-rated competitors: the one who placed higher must not gain
            // less. Stated as a test because it is the one property a competitor will
            // check, and any formula that violates it is indefensible whatever its merits.
            Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                    established(A, 1600, 3),
                    established(B, 1600, 1),
                    established(C, 1900, 2),
                    established(D, 1200, 4))));

            assertThat(changes.get(B).ratingChange())
                    .isGreaterThan(changes.get(A).ratingChange());
        }

        @Test
        @DisplayName("the score and penalty are carried through untouched")
        void scoreAndPenaltyArePassedThrough() {
            // They play no part in the arithmetic — rank alone decides the rating — but they
            // are written to the history row, which is what lets somebody see why they
            // placed where they did.
            List<RatingCalculator.Change> changes = RatingCalculator.compute(List.of(
                    new RatingCalculator.Participant(A, 1500, 10, 1, 300, 47),
                    new RatingCalculator.Participant(B, 1500, 10, 2, 100, 190)));

            Map<UUID, RatingCalculator.Change> byUser = byUser(changes);
            assertThat(byUser.get(A).score()).isEqualTo(300);
            assertThat(byUser.get(A).penalty()).isEqualTo(47);
            assertThat(byUser.get(B).score()).isEqualTo(100);
            assertThat(byUser.get(B).penalty()).isEqualTo(190);
        }
    }

    // ================================================================= edges

    @Nested
    @DisplayName("Edges")
    class Edges {

        @Test
        @DisplayName("one participant produces nothing")
        void aFieldOfOneIsNotAContest() {
            // Not an error. A contest one person entered is a real contest; it simply says
            // nothing about how well they did, because there was nobody to be measured
            // against. The expected score divides by n − 1, which would be a division by zero.
            assertThat(RatingCalculator.compute(List.of(established(A, 1500, 1)))).isEmpty();
        }

        @Test
        @DisplayName("an empty or null field produces nothing")
        void emptyFieldsProduceNothing() {
            assertThat(RatingCalculator.compute(List.of())).isEmpty();
            assertThat(RatingCalculator.compute(null)).isEmpty();
        }

        @Test
        @DisplayName("the result is byte-identical however the input was ordered")
        void resultIsIndependentOfInputOrder() {
            // Floating-point addition is not associative, so summing the field in a
            // different order can change the last bits of E — and with the right field,
            // change a rounded rating by one. The calculator sorts by public id to close
            // that, and this asserts the sort actually does its job.
            List<RatingCalculator.Participant> field = randomField(50);

            List<RatingCalculator.Change> reference = RatingCalculator.compute(field);

            Random shuffler = new Random(987654321L);
            for (int attempt = 0; attempt < 20; attempt++) {
                List<RatingCalculator.Participant> shuffled = new ArrayList<>(field);
                Collections.shuffle(shuffled, shuffler);

                assertThat(RatingCalculator.compute(shuffled))
                        .as("shuffle %d", attempt)
                        .containsExactlyElementsOf(reference);
            }
        }

        @Test
        @DisplayName("there is no floor and no ceiling, and a rating may go negative")
        void ratingsAreUnbounded() {
            // A deliberate choice, recorded in ADR-043. A floor would mean the system
            // quietly declining to record a result it had computed, and a competitor sitting
            // on the floor could then lose indefinitely at no cost — which is precisely the
            // situation where their rating most needs to keep moving.
            //
            // Note what it takes to get there: the opponent has to be an even match. Losing
            // to somebody hundreds of points above you rounds to nothing (see
            // anOverwhelmingFavouriteGainsNothing), so a rating only falls this far by
            // losing to people it is genuinely level with. Reaching zero from 1500 means
            // 150 consecutive defeats by an equal.
            int rating = 20;
            for (int contest = 0; contest < 3; contest++) {
                int current = rating;
                Map<UUID, RatingCalculator.Change> changes = byUser(RatingCalculator.compute(List.of(
                        new RatingCalculator.Participant(A, current, 25, 2, 0, 0),
                        new RatingCalculator.Participant(B, current, 25, 1, 0, 0))));
                rating = changes.get(A).ratingAfter();
            }

            // 20 → 10 → 0 → −10. No clamp anywhere on the way.
            assertThat(rating).isEqualTo(-10);
        }

        @Test
        @DisplayName("one change per participant, and no participant twice")
        void everyParticipantGetsExactlyOneChange() {
            List<RatingCalculator.Participant> field = randomField(75);

            List<RatingCalculator.Change> changes = RatingCalculator.compute(field);

            assertThat(changes).hasSize(75);
            assertThat(changes.stream().map(RatingCalculator.Change::userPublicId).distinct())
                    .hasSize(75);
        }

        @Test
        @DisplayName("a thousand competitors is still one pass, and finishes quickly")
        void aLargeFieldIsTractable() {
            // The pairwise form is O(n²): a thousand competitors is a million expectations.
            // That is fine at this size and is the documented limit of the approach — the
            // point of the assertion is to notice if it ever stops being fine.
            List<RatingCalculator.Participant> field = randomField(1000);

            long startedAt = System.nanoTime();
            List<RatingCalculator.Change> changes = RatingCalculator.compute(field);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(changes).hasSize(1000);
            assertThat(millis)
                    .as("computing a 1000-competitor field took %dms", millis)
                    .isLessThan(5_000);
        }
    }

    /** A reproducible pseudo-random field, with ties, newcomers and a wide rating spread. */
    private static List<RatingCalculator.Participant> randomField(int size) {
        Random random = new Random(42L);
        List<RatingCalculator.Participant> field = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            field.add(new RatingCalculator.Participant(
                    new UUID(0L, index),
                    800 + random.nextInt(1800),
                    random.nextInt(30),
                    // Deliberately collides, so ties are exercised throughout.
                    1 + random.nextInt(Math.max(1, size / 2)),
                    random.nextInt(500),
                    random.nextInt(600)));
        }
        return field;
    }
}
