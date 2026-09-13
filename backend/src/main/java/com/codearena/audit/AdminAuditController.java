package com.codearena.audit;

import com.codearena.audit.dto.AuditEventResponse;
import com.codearena.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reading the audit log. ADMIN only.
 *
 * <h2>Read-only, by construction</h2>
 * There is one method and it is a GET. No endpoint here creates, edits or removes an audit
 * event, and none could usefully be added: the entity is immutable, the repository has no
 * mutating method, and the database refuses UPDATE and DELETE outright.
 *
 * <h2>Authorisation</h2>
 * The path lives under {@code /api/admin/**}, which the Phase 2 security configuration
 * restricts to ADMIN. Anonymous callers get 401, authenticated non-administrators get 403 —
 * and that 403 is itself audited, because somebody probing the admin surface is exactly what
 * this log is for.
 *
 * <h2>What a caller can influence</h2>
 * Filter values and a sort name from a closed set. Nothing a caller sends reaches SQL as
 * text: enums are parsed before the query, ids are compared for equality against typed
 * columns, and the sort is checked against a whitelist rather than passed through.
 */
@RestController
@RequestMapping("/api/admin/audit-events")
@Tag(name = "Admin: audit", description = "The immutable record of security-sensitive events")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Not an administrator", content = @Content)
})
public class AdminAuditController {

    private final AuditQueryService queryService;

    public AdminAuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "Search the audit log",
               description = """
                       Every filter is optional and they combine with AND. Newest first
                       unless a different order is asked for.

                       **Time range** is half-open: `from` is inclusive and `to` is
                       exclusive, so consecutive ranges neither overlap nor leave a gap. A
                       range that ends before it starts is a 400 rather than an empty page —
                       an empty page reads as "nothing happened", which would be a wrong
                       answer to a mistyped question.

                       **Sorting** accepts only `occurredAt`, `action` or `outcome`. Anything
                       else is a 400: an open sort parameter would let a caller order by
                       fields the API never meant to expose and make the database sort an
                       unindexed column. When sorting by action or outcome, `occurredAt DESC`
                       is applied as a secondary key so pages do not shuffle between requests.

                       **Page size** is capped at 200.

                       **Actions** are the values of the `AuditAction` enum, documented on
                       the schema. `entityType` is one of USER, PROBLEM, CONTEST,
                       CONTEST_PROBLEM or SUBMISSION.
                       """)
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of audit events"))
    public PageResponse<AuditEventResponse> search(
            @Parameter(description = "The acting user's public id.")
            @RequestParam(required = false) UUID actorUserId,

            @Parameter(description = "An AuditAction constant, e.g. CONTEST_CANCEL.")
            @RequestParam(required = false) String action,

            @Parameter(description = "SUCCESS, FAILURE or DENIED.")
            @RequestParam(required = false) String outcome,

            @Parameter(description = "USER, ADMIN, SYSTEM or ANONYMOUS.")
            @RequestParam(required = false) String actorType,

            @Parameter(description = "What was acted on, e.g. CONTEST.")
            @RequestParam(required = false) String entityType,

            @Parameter(description = "The target's public identifier, matched exactly.")
            @RequestParam(required = false) String entityId,

            @Parameter(description = "Inclusive lower bound, ISO-8601 with an offset.")
            @RequestParam(required = false) Instant from,

            @Parameter(description = "Exclusive upper bound, ISO-8601 with an offset.")
            @RequestParam(required = false) Instant to,

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "occurredAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {

        AuditQueryService.requireValidRange(from, to);

        AuditQueryService.Query query = new AuditQueryService.Query(
                actorUserId,
                AuditQueryService.parse(AuditAction.class, action, "action"),
                AuditQueryService.parse(AuditOutcome.class, outcome, "outcome"),
                AuditQueryService.parse(ActorType.class, actorType, "actorType"),
                blankToNull(entityType),
                blankToNull(entityId),
                from, to);

        return queryService.search(query, page, size, sort, direction);
    }

    @GetMapping("/actions")
    @Operation(summary = "The audit action vocabulary",
               description = "Every action the system records, so an administrative UI can "
                           + "offer a filter without hard-coding a list that drifts out of date.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The supported actions"))
    public List<String> actions() {
        return Arrays.stream(AuditAction.values()).map(Enum::name).toList();
    }

    /** An omitted filter and an empty one mean the same thing: do not filter. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
