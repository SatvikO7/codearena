package com.codearena.problem;

import com.codearena.common.PageRequests;
import org.springframework.data.domain.Pageable;

/**
 * Problem-catalogue pagination: the shared clamping rules in {@link PageRequests},
 * combined with the catalogue's closed sort vocabulary.
 */
public final class ProblemPageRequest {

    /** Re-exported so existing callers and tests keep one place to read the limits from. */
    public static final int DEFAULT_SIZE = PageRequests.DEFAULT_SIZE;
    public static final int MAX_SIZE = PageRequests.MAX_SIZE;

    private ProblemPageRequest() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, ProblemSort.parse(sort).toSort());
    }
}
