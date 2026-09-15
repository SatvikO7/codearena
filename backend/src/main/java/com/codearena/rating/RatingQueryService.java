package com.codearena.rating;

import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.contest.Contest;
import com.codearena.contest.ContestLifecycle;
import com.codearena.contest.ContestRepository;
import com.codearena.rating.dto.RatingResponses.ContestRatingResult;
import com.codearena.rating.dto.RatingResponses.HistoryEntry;
import com.codearena.rating.dto.RatingResponses.ProgressionPoint;
import com.codearena.rating.dto.RatingResponses.RankingEntry;
import com.codearena.rating.dto.RatingResponses.RatingProfile;
import com.codearena.user.User;
import com.codearena.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads of the rating system: the leaderboard, a competitor's profile, and one contest's
 * outcome for one competitor.
 *
 * <p>Reads only. Nothing here writes, and there is deliberately no method that could — a
 * rating moves in exactly one place, inside a contest finalisation, and having a second way in
 * would make the history stop explaining the rating.
 */
@Service
public class RatingQueryService {

    /**
     * How many recent results a profile carries.
     *
     * <p>Enough to see a trend without turning a profile into a paginated list. The full
     * history has its own endpoint.
     */
    static final int RECENT_RESULTS = 10;

    /** Above this, a ranking page is refused rather than served slowly. */
    public static final int MAX_PAGE_SIZE = 100;

    private final UserRatingRepository userRatingRepository;
    private final ContestRatingChangeRepository ratingChangeRepository;
    private final UserRepository userRepository;
    private final ContestRepository contestRepository;
    private final RatingMetrics metrics;

    public RatingQueryService(UserRatingRepository userRatingRepository,
                              ContestRatingChangeRepository ratingChangeRepository,
                              UserRepository userRepository,
                              ContestRepository contestRepository,
                              RatingMetrics metrics) {
        this.userRatingRepository = userRatingRepository;
        this.ratingChangeRepository = ratingChangeRepository;
        this.userRepository = userRepository;
        this.contestRepository = contestRepository;
        this.metrics = metrics;
    }

    /**
     * A page of the global ranking.
     *
     * <p>Ordered and paged by the database. The application never sees more than one page,
     * which is what keeps this the same cost at ten users and at a hundred thousand.
     */
    @Transactional(readOnly = true)
    public PageResponse<RankingEntry> ranking(int page, int size) {
        long startedAt = System.nanoTime();

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        long offset = (long) pageIndex * limit;

        List<RankingEntry> entries = userRatingRepository.findRanking(limit, offset).stream()
                .map(row -> new RankingEntry(
                        row.getRank(), row.getUserId(), row.getUsername(),
                        row.getRating(), row.getPeakRating(), row.getContestsRated()))
                .toList();

        long total = userRatingRepository.countRated();
        metrics.rankingServed(Duration.ofNanos(System.nanoTime() - startedAt));

        return PageResponse.of(entries, pageIndex, limit, total);
    }

    /**
     * One competitor's rating profile.
     *
     * <p>Public: a leaderboard that named somebody would be strange if their profile then
     * refused to say what their rating was. What it does not carry is anything private — no
     * email, no role, no account state. An unrated competitor gets a profile with nulls rather
     * than a 404, because "this account has never competed" is a real and useful answer.
     */
    @Transactional(readOnly = true)
    public RatingProfile profile(UUID userPublicId) {
        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("No such user"));

        Optional<UserRating> rating = userRatingRepository.findByUserId(user.getId());
        if (rating.isEmpty()) {
            return new RatingProfile(user.getPublicId(), user.getUsername(),
                    null, null, null, 0, false, null, List.of(), List.of());
        }

        UserRating current = rating.get();
        Long rank = userRatingRepository.findRankOf(user.getId()).orElse(null);

        List<HistoryEntry> recent = ratingChangeRepository
                .findHistory(user.getId(), PageRequest.of(0, RECENT_RESULTS))
                .stream().map(RatingQueryService::toHistoryEntry).toList();

        List<ProgressionPoint> progression = ratingChangeRepository
                .findProgression(user.getId()).stream()
                .map(point -> new ProgressionPoint(
                        point.getAt(), point.getRating(), point.getContestTitle()))
                .toList();

        return new RatingProfile(
                user.getPublicId(), user.getUsername(),
                current.getRating(), current.getPeakRating(), rank,
                current.getContestsRated(), true, current.getLastRatedAt(),
                recent, progression);
    }

    /** A page of one competitor's rated contest history, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<HistoryEntry> history(UUID userPublicId, int page, int size) {
        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("No such user"));

        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        List<HistoryEntry> entries = ratingChangeRepository
                .findHistory(user.getId(), PageRequest.of(pageIndex, limit))
                .stream().map(RatingQueryService::toHistoryEntry).toList();

        long total = ratingChangeRepository.countByUserId(user.getId());
        return PageResponse.of(entries, pageIndex, limit, total);
    }

    /**
     * What one contest did to one competitor's rating.
     *
     * <p>The four states are distinct on purpose, and the distinction is the whole value of
     * this endpoint: UNRATED and CANCELLED mean "never"; PENDING means "not yet"; FINALIZED
     * means "here it is". Collapsing any of them into a change of zero would report a fact
     * that is not true.
     */
    @Transactional(readOnly = true)
    public ContestRatingResult contestResult(UUID contestId, UUID userPublicId) {
        Contest contest = contestRepository.findByPublicId(contestId)
                .orElseThrow(() -> new ResourceNotFoundException("No such contest"));

        if (contest.getLifecycle() == ContestLifecycle.CANCELLED) {
            return ContestRatingResult.cancelled(contestId);
        }
        if (!contest.isRated()) {
            return ContestRatingResult.unrated(contestId);
        }
        if (!contest.isRatingFinalized()) {
            return ContestRatingResult.pending(contestId);
        }

        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("No such user"));

        return ratingChangeRepository
                .findByContestIdAndUserId(contest.getId(), user.getId())
                .map(change -> new ContestRatingResult(
                        contestId, "FINALIZED",
                        change.getRank(), change.getParticipantCount(),
                        change.getScore(), change.getPenalty(),
                        change.getRatingBefore(), change.getRatingChange(),
                        change.getRatingAfter(), contest.getRatingFinalizedAt()))
                .orElseGet(() -> ContestRatingResult.notParticipated(
                        contestId, contest.getRatingFinalizedAt()));
    }

    private static HistoryEntry toHistoryEntry(ContestRatingChangeRepository.HistoryRow row) {
        return new HistoryEntry(
                row.getContestId(), row.getContestTitle(), row.getContestSlug(),
                row.getContestEndAt(), row.getRank(), row.getParticipantCount(),
                row.getScore(), row.getPenalty(),
                row.getRatingBefore(), row.getRatingChange(), row.getRatingAfter(),
                row.getCreatedAt());
    }
}
