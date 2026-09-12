package com.codearena.problem;

import com.codearena.common.ValidationException;
import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The orderings a client may ask for.
 *
 * <p>A closed set rather than a free-text property name. Spring Data would happily accept
 * {@code sort=createdBy.passwordHash} and either order by it or throw a 500; neither is
 * acceptable from a public parameter. Mapping a fixed vocabulary onto server-defined
 * {@link Sort} objects means the client chooses from a menu instead of naming columns.
 *
 * <p>Every ordering ends with {@code id} as a tiebreaker. Without one, rows sharing a
 * sort key have no defined order between them, so the same row can appear on two
 * consecutive pages while another is skipped entirely.
 */
public enum ProblemSort {

    NEWEST(Sort.by(Sort.Direction.DESC, "id")),
    OLDEST(Sort.by(Sort.Direction.ASC, "id")),
    TITLE(Sort.by(Sort.Direction.ASC, "title").and(Sort.by(Sort.Direction.ASC, "id"))),
    DIFFICULTY(Sort.by(Sort.Direction.ASC, "difficulty").and(Sort.by(Sort.Direction.ASC, "id")));

    private final Sort sort;

    ProblemSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }

    /**
     * @param value client-supplied name, case-insensitive; null or blank means {@link #NEWEST}
     * @throws ValidationException naming the permitted values, rather than a 500
     */
    public static ProblemSort parse(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("sort",
                    "Unknown sort '" + value + "'. Valid values: " + permitted());
        }
    }

    private static String permitted() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
