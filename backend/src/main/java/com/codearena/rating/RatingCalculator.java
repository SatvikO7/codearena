package com.codearena.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Turns a contest's final standings into rating changes.
 *
 * <p>Pure. No database, no Redis, no HTTP, no clock, no randomness — inputs in, numbers out.
 * That is what makes the mathematics independently testable, and the tests hand-calculate
 * their expected values rather than asserting whatever the code happens to produce.
 *
 * <h2>The formula</h2>
 * A contest is treated as a round robin: every participant "plays" every other participant
 * once, and the result of each pairing is decided by their competition ranks. This is the
 * standard generalisation of Elo from two players to many, and it is stated here in full
 * rather than gestured at.
 *
 * <p>For a contest with {@code n} eligible participants, and for each participant
 * <i>i</i> with rating <i>R<sub>i</sub></i>:
 *
 * <pre>
 *   Expected score against one opponent j:
 *
 *       E(i, j) = 1 / (1 + 10 ^ ((R_j - R_i) / 400))
 *
 *   Expected score over the field, as a fraction of opponents:
 *
 *       E_i = ( Σ_{j ≠ i} E(i, j) ) / (n - 1)
 *
 *   Actual score over the field:
 *
 *       A_i = ( wins_i + 0.5 × ties_i ) / (n - 1)
 *
 *     where wins_i  = |{ j ≠ i : rank_j &gt; rank_i }|   (a numerically larger rank is worse)
 *           ties_i  = |{ j ≠ i : rank_j = rank_i }|
 *
 *   Rating change:
 *
 *       Δ_i = round( K_i × (A_i − E_i) )
 *
 *       R'_i = R_i + Δ_i
 * </pre>
 *
 * <p>The 400 is Elo's scale constant: a player rated 400 points above another is expected to
 * beat them about ten times in eleven. It is inherited from chess and kept because changing
 * it would change nothing except the numbers people quote at each other.
 *
 * <h2>Why this and not a Codeforces-style seed</h2>
 * Codeforces computes a "seed" — the rank a contestant would be expected to achieve — and
 * derives a performance rating from it. That produces good numbers and is considerably
 * harder to state in one screen, harder to hand-verify, and harder to explain to somebody
 * who disagrees with their rating change. The pairwise form above is the same idea with the
 * normalisation done differently, and every step of it can be checked with a calculator.
 * ADR-043 records the choice.
 *
 * <h2>The system is zero-sum before rounding</h2>
 * Σ<sub>i</sub> A<sub>i</sub> = Σ<sub>i</sub> E<sub>i</sub> exactly: both count each unordered
 * pair once. So Σ (A_i − E_i) = 0, and if every K were equal the rating changes would sum to
 * zero. They do not quite, for two stated reasons: K varies per participant, and each change
 * is rounded independently. **Neither is a bug, and rating is not conserved.** A system that
 * forced conservation would have to take points from somebody to pay for a rounding error.
 *
 * <h2>Determinism</h2>
 * Floating-point addition is not associative, so the order of the {@code Σ} matters to the
 * last bit. Participants are therefore sorted by public id before anything is summed, which
 * fixes the order regardless of how the caller assembled the list. Given the same inputs this
 * class produces bit-identical output, and a test asserts it against a shuffled input.
 */
public final class RatingCalculator {

    /**
     * The rating a competitor starts with.
     *
     * <p>1500 is the Elo convention and is chosen for one practical reason: it leaves room to
     * fall. A scale starting at zero makes the first bad contest look like a catastrophe and
     * pushes ratings negative, which is arithmetically fine and reads as broken.
     */
    public static final int INITIAL_RATING = 1500;

    /** Elo's scale constant: 400 points is roughly a 10:1 expectation. */
    public static final double SCALE = 400.0;

    /**
     * How many rated contests a competitor is treated as provisional for.
     *
     * <p>A new competitor's rating is a guess, so it should move quickly toward the truth.
     * Five contests is enough for the rating to travel most of the way from 1500 to wherever
     * it belongs, and short enough that an established competitor is not still swinging.
     */
    public static final int PROVISIONAL_CONTESTS = 5;

    /** K while provisional: a rating that is mostly guess should move fast. */
    public static final int K_PROVISIONAL = 40;

    /** K once established. */
    public static final int K_ESTABLISHED = 20;

    /**
     * K at the top of the scale.
     *
     * <p>Above this rating the field is small and the sample is thin, so a single unlucky
     * contest should not undo a season. The same reasoning chess uses for its own top band.
     */
    public static final int ELITE_RATING = 2400;

    public static final int K_ELITE = 10;

    /**
     * Below two participants there is no contest to rate.
     *
     * <p>Not an error — a contest one person entered is a legitimate contest — but the
     * expected score is a division by {@code n - 1}, and with nobody to be measured against
     * there is no information about how anybody performed.
     */
    public static final int MIN_PARTICIPANTS = 2;

    private RatingCalculator() {
    }

    /**
     * One participant, as the calculator needs them.
     *
     * @param userPublicId the competitor; also the deterministic ordering key
     * @param rating       their rating going in. {@link #INITIAL_RATING} for a first contest
     * @param contestsRated how many rated contests they have completed, for the K-factor
     * @param rank         their <b>competition rank</b> from the final standings: tied
     *                     competitors share a rank and the next rank skips (1, 2, 2, 4).
     *                     This is a result, not a row number
     * @param score        their contest score, carried through for the history record
     * @param penalty      their penalty minutes, carried through likewise
     */
    public record Participant(
            UUID userPublicId,
            int rating,
            int contestsRated,
            int rank,
            int score,
            int penalty) {
    }

    /**
     * One computed rating movement.
     *
     * @param expectedScore E_i, the fraction of the field this rating was expected to beat
     * @param actualScore   A_i, the fraction it actually beat
     * @param kFactor       the K applied, which depends on the participant not the contest
     */
    public record Change(
            UUID userPublicId,
            int ratingBefore,
            int ratingAfter,
            int ratingChange,
            int rank,
            int score,
            int penalty,
            double expectedScore,
            double actualScore,
            int kFactor) {
    }

    /**
     * Computes every participant's rating change.
     *
     * <p>Returns an empty list when there are fewer than {@link #MIN_PARTICIPANTS}. The caller
     * still finalises the contest — it simply produced no rating movement — because
     * "finalised" and "changed somebody's rating" are different facts.
     *
     * @param participants the eligible field. Order is irrelevant: it is sorted internally
     * @return one change per participant, in the same sorted order
     */
    public static List<Change> compute(List<Participant> participants) {
        if (participants == null || participants.size() < MIN_PARTICIPANTS) {
            return List.of();
        }

        // Sorted once, and everything below iterates this list. Floating-point addition is
        // not associative, so this is what makes the result independent of how the caller
        // happened to order the field.
        List<Participant> field = participants.stream()
                .sorted(Comparator.comparing(Participant::userPublicId))
                .toList();

        int n = field.size();
        double opponents = n - 1.0;
        List<Change> changes = new ArrayList<>(n);

        for (Participant self : field) {
            double expectedSum = 0.0;
            double actualSum = 0.0;

            for (Participant other : field) {
                if (other.userPublicId().equals(self.userPublicId())) {
                    continue;
                }
                expectedSum += expectedScore(self.rating(), other.rating());

                // A numerically larger rank is a worse result, so beating somebody means
                // having the smaller rank. Equal ranks are a genuine tie and score a half
                // each -- exactly as a drawn game does in Elo.
                if (other.rank() > self.rank()) {
                    actualSum += 1.0;
                } else if (other.rank() == self.rank()) {
                    actualSum += 0.5;
                }
            }

            double expected = expectedSum / opponents;
            double actual = actualSum / opponents;
            int k = kFactorFor(self);
            int delta = round(k * (actual - expected));

            changes.add(new Change(
                    self.userPublicId(),
                    self.rating(),
                    self.rating() + delta,
                    delta,
                    self.rank(),
                    self.score(),
                    self.penalty(),
                    expected,
                    actual,
                    k));
        }
        return List.copyOf(changes);
    }

    /**
     * The classic Elo expectation: the probability that {@code rating} beats {@code opponent}.
     *
     * <p>Exposed for the tests, which check it against hand-calculated values — equal ratings
     * give exactly 0.5, and a 400-point gap gives 10/11.
     */
    public static double expectedScore(int rating, int opponent) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponent - rating) / SCALE));
    }

    /**
     * The K-factor for one participant.
     *
     * <p>A property of the competitor, not of the contest: a newcomer and a veteran in the
     * same contest move by different amounts, which is the point. Provisional is checked
     * first, so a newcomer who happens to be rated highly still moves quickly.
     */
    public static int kFactorFor(Participant participant) {
        if (participant.contestsRated() < PROVISIONAL_CONTESTS) {
            return K_PROVISIONAL;
        }
        if (participant.rating() >= ELITE_RATING) {
            return K_ELITE;
        }
        return K_ESTABLISHED;
    }

    /**
     * Rounds a rating change to a whole number.
     *
     * <p>HALF_UP in {@link BigDecimal}'s sense, which is half <em>away from zero</em>: +2.5
     * becomes +3 and −2.5 becomes −3. That symmetry is the reason for not using
     * {@link Math#round}, which computes {@code floor(x + 0.5)} and so rounds −2.5 to −2 —
     * quietly favouring whoever is losing points.
     */
    static int round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
