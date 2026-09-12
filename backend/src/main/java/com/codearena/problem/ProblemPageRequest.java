package com.codearena.problem;

import com.codearena.common.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Pagination parameters, validated and clamped.
 *
 * <p>Semantics, which are the documented contract:
 * <ul>
 *   <li>{@code page} is zero-based and must not be negative.</li>
 *   <li>{@code size} defaults to {@value #DEFAULT_SIZE} and is <em>clamped</em> to
 *       {@value #MAX_SIZE} rather than rejected. A client asking for 5000 rows is far more
 *       often naive than hostile, and silently serving a sane page is friendlier than a
 *       400 — while still denying the request that would have read the whole table. A
 *       size below 1 is a mistake with no sensible interpretation, so that is rejected.</li>
 *   <li>Ordering comes from {@link ProblemSort} and always ends with a unique tiebreaker,
 *       so paging is stable.</li>
 * </ul>
 */
public final class ProblemPageRequest {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private ProblemPageRequest() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            throw new ValidationException("page", "Page index must not be negative");
        }

        int requestedSize = size == null ? DEFAULT_SIZE : size;
        if (requestedSize < 1) {
            throw new ValidationException("size", "Page size must be at least 1");
        }
        int resolvedSize = Math.min(requestedSize, MAX_SIZE);

        return PageRequest.of(resolvedPage, resolvedSize, ProblemSort.parse(sort).toSort());
    }
}
