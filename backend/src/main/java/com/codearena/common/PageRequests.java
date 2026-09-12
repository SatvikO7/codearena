package com.codearena.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pagination parameters, validated and clamped, for every listing endpoint.
 *
 * <p>Semantics, which are the documented contract:
 * <ul>
 *   <li>{@code page} is zero-based and must not be negative.</li>
 *   <li>{@code size} defaults to {@value #DEFAULT_SIZE} and is <em>clamped</em> to
 *       {@value #MAX_SIZE} rather than rejected. A client asking for 5000 rows is far more
 *       often naive than hostile, and silently serving a sane page is friendlier than a
 *       400 — while still denying the request that would have read the whole table. A size
 *       below 1 is a mistake with no sensible interpretation, so that is rejected.</li>
 *   <li>The caller supplies the {@link Sort}; it is never derived from a raw client string,
 *       so no request can order by an arbitrary column.</li>
 * </ul>
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            throw new ValidationException("page", "Page index must not be negative");
        }

        int requestedSize = size == null ? DEFAULT_SIZE : size;
        if (requestedSize < 1) {
            throw new ValidationException("size", "Page size must be at least 1");
        }
        return PageRequest.of(resolvedPage, Math.min(requestedSize, MAX_SIZE), sort);
    }
}
