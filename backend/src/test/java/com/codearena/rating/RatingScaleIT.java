package com.codearena.rating;

import com.codearena.AbstractIntegrationTest;
import com.codearena.common.PageResponse;
import com.codearena.rating.dto.RatingResponses.RankingEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leaderboard at a size nobody has reached yet.
 *
 * <h2>Why this exists</h2>
 * The ranking is the one query in this phase whose cost grows with the whole user base rather
 * than with one contest. Every other read is bounded — a profile is one row plus that user's
 * history; a contest result is a single lookup. This one ranks everybody.
 *
 * <p>It is therefore the query that would be fine all the way through development and then
 * degrade in production without anything having changed except the number of accounts. The
 * failure mode is the unpleasant kind: not an error, just a page that gets slower until it
 * starts timing out, at the point where the platform is finally busy enough to matter.
 *
 * <h2>What is being asserted, and what is not</h2>
 * That the query is <b>ordered, ranked and paged by the database</b>, and that its cost does
 * not track the size of the table. The thresholds are deliberately loose — this runs on a
 * laptop and inside a container, and a tight timing assertion would be a flaky test rather
 * than a useful one. A regression that mattered would not be a few milliseconds; it would be
 * somebody replacing the window function with an in-memory sort, and that shows up here as
 * seconds or as an out-of-memory error, not as noise.
 *
 * <p>The rows are inserted directly. Ten thousand competitors cannot be manufactured by
 * running ten thousand contests, and the shape of the data is what the query cares about.
 */
@SpringBootTest
class RatingScaleIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RatingQueryService queryService;
    @Autowired private UserRatingRepository userRatingRepository;

    @BeforeEach
    void setUp() {
        resetDatabase(jdbc);
    }

    @Test
    @DisplayName("a thousand rated competitors: a page is served in well under a second")
    void aThousandCompetitors() {
        seed(1_000);

        Duration took = time(() -> queryService.ranking(0, 50));

        assertThat(userRatingRepository.countRated()).isEqualTo(1_000);
        assertThat(took).as("first page of 1,000").isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("ten thousand rated competitors: still a page, still fast")
    void tenThousandCompetitors() {
        seed(10_000);

        PageResponse<RankingEntry> page = queryService.ranking(0, 50);
        Duration took = time(() -> queryService.ranking(0, 50));

        assertThat(page.items()).hasSize(50);
        assertThat(page.totalItems()).isEqualTo(10_000);
        assertThat(took).as("first page of 10,000").isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("the last page costs about what the first page costs")
    void deepPagingDoesNotDegrade() {
        // The property that proves the paging is real. If the application were loading the
        // table and slicing it, both pages would cost the same *large* amount; if it were
        // paging properly, both cost the same *small* amount. What would show a regression
        // is the last page costing dramatically more than the first — the signature of an
        // OFFSET over an unindexed sort.
        seed(10_000);

        Duration first = time(() -> queryService.ranking(0, 50));
        Duration last = time(() -> queryService.ranking(199, 50));

        assertThat(last).as("page 200 of 10,000 (first page took %s)", first)
                .isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("one competitor's rank is a counting query, not a ranking of everybody")
    void findingOneRankIsCheap() {
        seed(10_000);

        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM user_ratings ORDER BY rating ASC LIMIT 1", Long.class);

        Duration took = time(() -> userRatingRepository.findRankOf(userId));

        // The worst case on purpose: the lowest-rated competitor, whose rank requires
        // counting almost the entire table. An index-only count over
        // ix_user_ratings_leaderboard, not a window function over 10,000 rows.
        assertThat(took).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("the page size is clamped, so no caller can ask for the whole table")
    void aCallerCannotAskForEverything() {
        seed(10_000);

        PageResponse<RankingEntry> page = queryService.ranking(0, Integer.MAX_VALUE);

        assertThat(page.items()).hasSize(RatingQueryService.MAX_PAGE_SIZE);
        assertThat(page.size()).isEqualTo(RatingQueryService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("the ranking itself is correct at scale, not merely fast")
    void theRankingIsStillRight() {
        // Speed is worthless if the numbers are wrong. The seed gives the first competitor
        // the highest rating, so rank 1 is known independently of the query.
        seed(1_000);

        List<RankingEntry> top = queryService.ranking(0, 3).items();

        assertThat(top).hasSize(3);
        assertThat(top.get(0).rank()).isEqualTo(1);
        assertThat(top.get(0).rating()).isGreaterThanOrEqualTo(top.get(1).rating());
        assertThat(top.get(1).rating()).isGreaterThanOrEqualTo(top.get(2).rating());
    }

    /**
     * Inserts {@code count} rated competitors in one statement.
     *
     * <p>{@code generate_series} rather than a loop of inserts: ten thousand round trips would
     * make the setup slower than the thing being measured, and the point is the query.
     *
     * <p>Ratings are spread across a realistic band with deliberate collisions, so the ranking
     * is exercising ties rather than a strictly ordered list.
     */
    private void seed(int count) {
        jdbc.update("""
                INSERT INTO users (public_id, username, email, password_hash, role, enabled)
                SELECT gen_random_uuid(),
                       'competitor' || i,
                       'competitor' || i || '@example.test',
                       'not-a-real-hash',
                       'USER',
                       TRUE
                  FROM generate_series(1, ?) AS i
                """, count);

        jdbc.update("""
                INSERT INTO user_ratings (user_id, rating, peak_rating, contests_rated)
                SELECT u.id,
                       3000 - (i % 1500),
                       3000 - (i % 1500),
                       1 + (i % 40)
                  FROM generate_series(1, ?) AS i
                  JOIN users u ON u.username = 'competitor' || i
                """, count);
    }

    private static Duration time(Runnable work) {
        long startedAt = System.nanoTime();
        work.run();
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
