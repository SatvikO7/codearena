package com.codearena.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The paged envelope every listing endpoint returns.
 *
 * <p>Spring's own {@code Page} is deliberately not serialised directly. Its JSON shape is
 * an implementation detail that has changed between Spring versions — Boot even warns
 * about relying on it — so pinning an explicit contract here means a framework upgrade
 * cannot silently reshape the API every client depends on.
 *
 * @param page       zero-based index of the returned page
 * @param size       maximum items per page actually applied, after clamping
 * @param totalItems total matching the filter, across all pages
 * @param totalPages number of pages at this size
 */
@Schema(description = "A page of results. `page` is zero-based.")
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext(),
                source.hasPrevious());
    }

    /**
     * A page assembled by hand, for a native query that paged itself.
     *
     * <p>Spring Data's {@link Page} comes from a repository method that knows how to count
     * and slice. The global ranking does neither through Spring Data: it is a window
     * function with its own LIMIT and OFFSET, because ranking every user in the application
     * to serve twenty of them is the thing that stops working at scale. This factory exists
     * so such a query still returns the same envelope as everything else, and the client
     * cannot tell which kind of query answered it.
     *
     * @param page  zero-based index of the page returned
     * @param size  the page size actually applied, after any clamping
     * @param total how many items exist in total, across all pages
     */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(
                items,
                page,
                size,
                total,
                totalPages,
                (long) (page + 1) * size < total,
                page > 0);
    }
}
