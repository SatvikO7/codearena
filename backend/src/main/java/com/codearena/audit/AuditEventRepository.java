package com.codearena.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Reading and appending audit events.
 *
 * <h2>There is no update and no delete</h2>
 * Not "none are exposed" — none are used. The entity is {@code @Immutable}, so Hibernate will
 * not issue an UPDATE; nothing calls {@code delete}; and a database trigger refuses both
 * operations regardless of what any code intends. Three independent layers, because the row
 * most worth tampering with is the one recording the tamperer.
 *
 * <h2>Why filtering is a Specification rather than one query with optional parameters</h2>
 * The obvious shape — {@code WHERE (:action IS NULL OR e.action = :action)} repeated per
 * filter — does not work against PostgreSQL. A null bound parameter in that position has no
 * type the planner can infer, and the query fails outright with <em>could not determine data
 * type of parameter</em>. This is the same trap ADR-017 records for problem search, and the
 * resolution is the same: build the condition in Java instead of asking the database to cope
 * with a null.
 *
 * <p>It is also the better query. Only the filters a caller actually sent become predicates,
 * so the planner sees a clean {@code action = ? ORDER BY occurred_at DESC} and can use the
 * matching index, rather than eight disjunctions it must evaluate per row.
 *
 * <p>Nothing a caller writes reaches the SQL as text: every predicate compares a typed column
 * against a bound parameter — an enum, a UUID, an instant — and the entity id is an equality
 * rather than a LIKE, so it cannot be turned into a scan by somebody sending {@code %}.
 */
public interface AuditEventRepository
        extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    /** Used by the system status view to report how much history exists. */
    @Override
    long count();
}
