package com.codearena.audit;

import com.codearena.common.PageResponse;
import com.codearena.common.ValidationException;
import com.codearena.audit.dto.AuditEventResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Searching the audit log.
 *
 * <h2>Sorting is a closed vocabulary</h2>
 * A client sends a name from {@link #SORTABLE}; anything else is a 400. It never sends a
 * column, a direction expression or a property path. That matters more here than elsewhere:
 * Spring Data will happily sort by any property name it can resolve, so an open sort
 * parameter is a way to order by fields the API never meant to expose, and to make the
 * database do arbitrary work on an unindexed column.
 *
 * <p>Every permitted sort is backed by an index that also carries {@code occurredAt DESC},
 * so the ordering the API offers is the ordering the database can actually serve.
 *
 * <h2>Everything else is typed</h2>
 * Actions, outcomes and actor types are enums, parsed before they reach the query; actor and
 * entity ids are compared for equality against bounded columns. No filter is interpolated
 * into SQL, and the entity id is not a LIKE, so a caller cannot turn a filter into a scan.
 */
@Service
public class AuditQueryService {

    /**
     * The only sortable fields.
     *
     * <p>Deliberately short. An audit search is "what happened, newest first", optionally
     * narrowed — not a reporting tool, and each additional sort is an index to maintain.
     */
    public static final Set<String> SORTABLE = Set.of("occurredAt", "action", "outcome");

    /** Bounded so a single request cannot ask the database for the whole table. */
    public static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** The parsed, validated query. Built by the controller from request parameters. */
    public record Query(UUID actorUserId,
                        AuditAction action,
                        AuditOutcome outcome,
                        ActorType actorType,
                        String entityType,
                        String entityId,
                        Instant from,
                        Instant to) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> search(Query query, int page, int size,
                                                   String sort, String direction) {
        Page<AuditEvent> results = repository.findAll(
                matching(query), pageable(page, size, sort, direction));

        return PageResponse.from(results, AuditEventResponse::from);
    }

    /**
     * Turns the query into predicates, one per filter the caller actually sent.
     *
     * <p>An absent filter contributes no predicate at all, rather than a
     * {@code :x IS NULL OR …} disjunction. That is not a style preference: PostgreSQL cannot
     * infer a type for a null bound parameter in that position and the query fails outright
     * (ADR-017 records the same trap in problem search). It also gives the planner a clean
     * predicate it can serve from an index.
     *
     * <p>Every comparison is a typed column against a bound value. No caller input is
     * interpolated, and the entity id is an equality rather than a LIKE.
     */
    private Specification<AuditEvent> matching(Query query) {
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.actorUserId() != null) {
                predicates.add(builder.equal(root.get("actorUserId"), query.actorUserId()));
            }
            if (query.action() != null) {
                predicates.add(builder.equal(root.get("action"), query.action()));
            }
            if (query.outcome() != null) {
                predicates.add(builder.equal(root.get("outcome"), query.outcome()));
            }
            if (query.actorType() != null) {
                predicates.add(builder.equal(root.get("actorType"), query.actorType()));
            }
            if (query.entityType() != null) {
                predicates.add(builder.equal(root.get("entityType"), query.entityType()));
            }
            if (query.entityId() != null) {
                predicates.add(builder.equal(root.get("entityId"), query.entityId()));
            }
            if (query.from() != null) {
                // Inclusive lower bound, exclusive upper: consecutive ranges neither overlap
                // nor leave a gap, so paging through history by time cannot miss an event.
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(builder.lessThan(root.get("occurredAt"), query.to()));
            }
            return predicates.isEmpty()
                    ? builder.conjunction()
                    : builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Builds the page request, refusing anything outside the whitelist.
     *
     * <p>The time range is validated here too: a range whose end precedes its start returns
     * nothing, which looks to an administrator like "there were no such events" rather than
     * "you asked the wrong question". Saying so is more useful than an empty page.
     */
    private Pageable pageable(int page, int size, String sort, String direction) {
        String property = sort == null || sort.isBlank() ? "occurredAt" : sort;
        if (!SORTABLE.contains(property)) {
            throw new ValidationException("sort",
                    "Sort must be one of " + SORTABLE.stream().sorted().toList());
        }

        Sort.Direction order = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        int boundedSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int boundedPage = Math.max(0, page);

        // Newest first by default: an audit log is read from the end.
        Sort ordering = Sort.by(order, property);
        if (!"occurredAt".equals(property)) {
            // A secondary key so that pages do not shuffle between requests when many rows
            // share an action or an outcome. Without it, page 2 can repeat a row from page 1.
            ordering = ordering.and(Sort.by(Sort.Direction.DESC, "occurredAt"));
        }
        return PageRequest.of(boundedPage, boundedSize, ordering);
    }

    /** Parses an enum filter, naming the acceptable values rather than throwing a 500. */
    public static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field,
                    "Must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    /** Validates a time range, so an inverted one is an error rather than an empty page. */
    public static void requireValidRange(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ValidationException("to", "The end of the range must be after its start");
        }
    }

    /** Exposed for the admin system view, which reports how much history exists. */
    @Transactional(readOnly = true)
    public Map<String, Long> totals() {
        return Map.of("auditEvents", repository.count());
    }
}
