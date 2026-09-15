package com.codearena.rating;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Current ratings, and the global leaderboard.
 *
 * <h2>The ranking is computed by the database</h2>
 * {@code RANK()} over the whole table, paged with LIMIT/OFFSET. Loading every user into the
 * application to number them would work at a thousand users and fall over at a hundred
 * thousand, and the failure would arrive as a memory problem rather than a slow query — the
 * worse of the two to diagnose.
 *
 * <p>{@code RANK()} gives <b>competition ranking</b>: genuine ties share a rank and the next
 * rank skips (1, 2, 2, 4), matching how contest standings already rank contestants. Ties are
 * broken for <em>ordering</em> by contests rated and then user id, so a page boundary is
 * stable between requests — but that ordering does not affect the rank itself. Two users on
 * the same rating are shown the same rank however they are ordered on the page, which is the
 * distinction between a result and a row number.
 */
public interface UserRatingRepository extends JpaRepository<UserRating, Long> {

    Optional<UserRating> findByUserId(Long userId);

    @Query("SELECT r FROM UserRating r WHERE r.user.publicId = :publicId")
    Optional<UserRating> findByUserPublicId(@Param("publicId") UUID publicId);

    /**
     * Loads the ratings of a set of users for a contest finalisation.
     *
     * <p>One query for the whole field rather than one per participant: a contest with three
     * hundred contestants would otherwise open a finalisation with three hundred round trips.
     */
    @Query("SELECT r FROM UserRating r WHERE r.user.id IN :userIds")
    List<UserRating> findAllByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /** One row of the global leaderboard. */
    interface RankedUser {
        long getRank();

        UUID getUserId();

        String getUsername();

        int getRating();

        int getPeakRating();

        int getContestsRated();
    }

    /**
     * A page of the global ranking.
     *
     * <p>Note what is selected: a public id, a username and three numbers. No email, no role,
     * no timestamps, no internal id. The leaderboard is the most public surface in the system
     * and it reads a table that does not contain anything private in the first place.
     */
    @Query(value = """
            SELECT RANK() OVER (ORDER BY ur.rating DESC)      AS rank,
                   u.public_id                                AS userId,
                   u.username                                 AS username,
                   ur.rating                                  AS rating,
                   ur.peak_rating                             AS peakRating,
                   ur.contests_rated                          AS contestsRated
            FROM user_ratings ur
            JOIN users u ON u.id = ur.user_id
            ORDER BY ur.rating DESC, ur.contests_rated DESC, ur.user_id ASC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<RankedUser> findRanking(@Param("limit") int limit, @Param("offset") long offset);

    /**
     * One user's global rank.
     *
     * <p>Counted rather than paged: a user's rank is one more than the number of users rated
     * strictly above them, which is the definition of competition ranking and needs no window
     * function. It is also an index-only count over
     * {@code ix_user_ratings_leaderboard} rather than a scan that ranks everybody to find one
     * row.
     */
    @Query(value = """
            SELECT 1 + COUNT(*)
            FROM user_ratings
            WHERE rating > (SELECT rating FROM user_ratings WHERE user_id = :userId)
            """, nativeQuery = true)
    Optional<Long> findRankOf(@Param("userId") long userId);

    @Query(value = "SELECT count(*) FROM user_ratings", nativeQuery = true)
    long countRated();
}
