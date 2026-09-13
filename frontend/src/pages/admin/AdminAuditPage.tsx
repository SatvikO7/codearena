import { useCallback, useEffect, useState } from 'react';
import { listAuditActions, searchAuditEvents } from '../../services/auditService';
import { describeApiError } from '../../services/apiClient';
import { Pagination } from '../../components/Pagination';
import { ACTOR_TYPES, AUDIT_OUTCOMES, ENTITY_TYPES } from '../../types/audit';
import type { ActorType, AuditEvent, AuditOutcome } from '../../types/audit';
import type { PageResponse } from '../../types/problem';

/** Local datetime input to an instant; an empty box means no bound. */
function toInstant(localValue: string): string | undefined {
  if (!localValue) return undefined;
  const parsed = new Date(localValue);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

function formatTimestamp(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return iso;
  return parsed.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' });
}

/** Colour is never the only signal; each outcome also carries a word and a glyph. */
function outcomeTone(outcome: AuditOutcome): { tone: string; glyph: string } {
  if (outcome === 'SUCCESS') return { tone: 'ok', glyph: '✓' };
  if (outcome === 'DENIED') return { tone: 'error', glyph: '⊘' };
  return { tone: 'neutral', glyph: '!' };
}

/**
 * The audit log.
 *
 * <p>Read-only by construction: the page fetches and filters, and there is deliberately no
 * control here that edits or removes an event. The server would refuse — the API has no
 * write verb and the database refuses UPDATE and DELETE — but an administrative UI offering
 * a delete button that always fails would be worse than not offering one.
 *
 * <p>Everything displayed comes from the server. The page computes no outcome, infers no
 * actor and formats no timestamp it was not given.
 */
export function AdminAuditPage() {
  const [data, setData] = useState<PageResponse<AuditEvent> | null>(null);
  const [actions, setActions] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  const [page, setPage] = useState(0);
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState<AuditOutcome | ''>('');
  const [actorType, setActorType] = useState<ActorType | ''>('');
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setData(
          await searchAuditEvents(
            {
              page,
              size: 50,
              action: action || undefined,
              outcome: outcome || undefined,
              actorType: actorType || undefined,
              entityType: entityType || undefined,
              entityId: entityId.trim() || undefined,
              from: toInstant(from),
              to: toInstant(to),
            },
            signal,
          ),
        );
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [page, action, outcome, actorType, entityType, entityId, from, to],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    const controller = new AbortController();
    // Fetched, not hard-coded: a baked-in list goes stale the first time an action is added.
    listAuditActions(controller.signal)
      // oxlint-disable-next-line set-state-in-effect
      .then(setActions)
      .catch(() => setActions([]));
    return () => controller.abort();
  }, []);

  function applyFilter(change: () => void) {
    change();
    setPage(0);
  }

  const hasFilters =
    action !== '' || outcome !== '' || actorType !== '' || entityType !== '' ||
    entityId !== '' || from !== '' || to !== '';

  return (
    <div className="page">
      <section className="hero">
        <h1>Audit log</h1>
        <p className="lede">
          An append-only record of security-sensitive and administrative events. Entries
          cannot be edited or removed.
        </p>
      </section>

      <section className="panel filters" aria-label="Filters">
        <div className="filter-row">
          <div className="filter-field filter-field--grow">
            <label htmlFor="audit-action">Action</label>
            <select
              id="audit-action"
              value={action}
              onChange={(event) => applyFilter(() => setAction(event.target.value))}
            >
              <option value="">All actions</option>
              {actions.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>

          <div className="filter-field">
            <label htmlFor="audit-outcome">Outcome</label>
            <select
              id="audit-outcome"
              value={outcome}
              onChange={(event) =>
                applyFilter(() => setOutcome(event.target.value as AuditOutcome | ''))
              }
            >
              <option value="">All</option>
              {AUDIT_OUTCOMES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>

          <div className="filter-field">
            <label htmlFor="audit-actor-type">Actor</label>
            <select
              id="audit-actor-type"
              value={actorType}
              onChange={(event) =>
                applyFilter(() => setActorType(event.target.value as ActorType | ''))
              }
            >
              <option value="">All</option>
              {ACTOR_TYPES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="filter-row">
          <div className="filter-field">
            <label htmlFor="audit-entity-type">Entity</label>
            <select
              id="audit-entity-type"
              value={entityType}
              onChange={(event) => applyFilter(() => setEntityType(event.target.value))}
            >
              <option value="">All</option>
              {ENTITY_TYPES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>

          <div className="filter-field filter-field--grow">
            <label htmlFor="audit-entity-id">Entity id</label>
            <input
              id="audit-entity-id"
              value={entityId}
              placeholder="Exact identifier"
              onChange={(event) => setEntityId(event.target.value)}
              onBlur={() => setPage(0)}
            />
          </div>

          <div className="filter-field">
            <label htmlFor="audit-from">From</label>
            <input
              id="audit-from"
              type="datetime-local"
              value={from}
              onChange={(event) => applyFilter(() => setFrom(event.target.value))}
            />
          </div>

          <div className="filter-field">
            <label htmlFor="audit-to">To</label>
            <input
              id="audit-to"
              type="datetime-local"
              value={to}
              onChange={(event) => applyFilter(() => setTo(event.target.value))}
            />
          </div>

          {hasFilters && (
            <button
              type="button"
              className="button button--quiet"
              onClick={() =>
                applyFilter(() => {
                  setAction('');
                  setOutcome('');
                  setActorType('');
                  setEntityType('');
                  setEntityId('');
                  setFrom('');
                  setTo('');
                })
              }
            >
              Clear filters
            </button>
          )}
        </div>
        <p className="field-hint">Times are shown in your local timezone.</p>
      </section>

      {loading && (
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      )}

      {error && !loading && (
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the audit log</p>
          <p className="status-detail">{error}</p>
        </div>
      )}

      {!loading && !error && data && data.items.length === 0 && (
        <section className="panel empty-state">
          <h2>No matching events</h2>
          <p>{hasFilters ? 'Try widening your filters.' : 'Nothing has been recorded yet.'}</p>
        </section>
      )}

      {!loading && !error && data && data.items.length > 0 && (
        <>
          <section className="panel table-panel">
            <table className="data-table">
              <caption className="visually-hidden">Audit events</caption>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">Actor</th>
                  <th scope="col">Action</th>
                  <th scope="col">Target</th>
                  <th scope="col">Outcome</th>
                  <th scope="col">Detail</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((event) => {
                  const { tone, glyph } = outcomeTone(event.outcome);
                  const isOpen = expanded === event.id;
                  return (
                    <tr key={event.id}>
                      <td>{formatTimestamp(event.occurredAt)}</td>
                      <td>
                        {event.actorUsername ?? '—'}
                        <span className="row-sub">{event.actorType}</span>
                      </td>
                      <td>{event.action}</td>
                      <td>
                        {event.entityType ?? '—'}
                        {event.entityId && (
                          <span className="row-sub">{event.entityId.slice(0, 8)}</span>
                        )}
                      </td>
                      <td>
                        <span className={`pill pill--${tone}`}>
                          <span className="pill-glyph" aria-hidden="true">
                            {glyph}
                          </span>
                          {event.outcome}
                        </span>
                      </td>
                      <td>
                        {Object.keys(event.metadata).length > 0 || event.requestId ? (
                          <>
                            <button
                              type="button"
                              className="button button--quiet"
                              aria-expanded={isOpen}
                              onClick={() => setExpanded(isOpen ? null : event.id)}
                            >
                              {isOpen ? 'Hide' : 'Show'}
                            </button>
                            {isOpen && (
                              <div className="audit-detail">
                                {event.requestId && (
                                  <p className="row-sub">request {event.requestId}</p>
                                )}
                                {/* Bounded server-side, so this cannot become a wall of text.
                                    Rendered as text rather than markup: metadata is curated,
                                    but it is not a place to trust with HTML. */}
                                <pre className="code-block">
                                  {JSON.stringify(event.metadata, null, 2)}
                                </pre>
                              </div>
                            )}
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>

          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalItems={data.totalItems}
            hasNext={data.hasNext}
            hasPrevious={data.hasPrevious}
            onChange={setPage}
          />
        </>
      )}
    </div>
  );
}
