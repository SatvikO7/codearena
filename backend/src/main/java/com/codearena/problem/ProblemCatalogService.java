package com.codearena.problem;

import com.codearena.common.PageResponse;
import com.codearena.common.ResourceNotFoundException;
import com.codearena.problem.dto.ProblemDetailResponse;
import com.codearena.problem.dto.ProblemSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The problem catalogue, as normal users see it.
 *
 * <p>This service has one job and one rule: it never returns anything that is not
 * PUBLISHED. The status is fixed here, in server code, rather than accepted as a
 * parameter — there is no argument a caller could pass that would widen the result to
 * drafts or archived problems. Nothing in this class can reach a test case: the queries
 * it uses do not load them and the DTO it returns has no field to put one in.
 */
@Service
@Transactional(readOnly = true)
public class ProblemCatalogService {

    private final ProblemRepository problemRepository;

    public ProblemCatalogService(ProblemRepository problemRepository) {
        this.problemRepository = problemRepository;
    }

    /**
     * Lists published problems.
     *
     * <p>Filtering, ordering and paging all happen in the database; no more than one page
     * of rows is ever materialised.
     *
     * @param difficulty optional filter, null for all
     * @param tag        optional filter, null for all
     * @param search     optional case-insensitive substring of the title or slug
     */
    public PageResponse<ProblemSummaryResponse> listPublished(Difficulty difficulty,
                                                              ProblemTag tag,
                                                              String search,
                                                              Pageable pageable) {
        Page<Problem> page = problemRepository.findCatalogue(
                ProblemStatus.PUBLISHED, difficulty, tag, searchPattern(search), pageable);
        return PageResponse.from(page, ProblemSummaryResponse::forCatalogue);
    }

    /**
     * Fetches one published problem by slug.
     *
     * <p>A draft or archived problem produces 404, never 403. Answering "forbidden" would
     * confirm that a problem with that slug exists, which is precisely what an unreleased
     * problem is meant to withhold; the two cases are therefore indistinguishable to a
     * caller who is not an administrator.
     */
    public ProblemDetailResponse getPublishedBySlug(String slug) {
        return problemRepository.findPublishedBySlugWithDetail(slug, ProblemStatus.PUBLISHED)
                .map(ProblemDetailResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("PROBLEM_NOT_FOUND", "Problem not found"));
    }

    /**
     * Turns a raw search term into a LIKE pattern, or null when there is nothing to match.
     *
     * <p>The wildcards and lower-casing are applied here rather than in the query so that
     * the bound parameter is a plain string. {@code %} and {@code _} in the user's term are
     * escaped: without that, a search for "100%" would match everything, and a term of
     * only wildcards would force a full scan.
     */
    private String searchPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
