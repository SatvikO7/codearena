import { useCallback, useEffect, useState } from 'react';
import { getSystemStatus } from '../../services/auditService';
import { describeApiError } from '../../services/apiClient';
import type { DependencyStatus, SystemStatus } from '../../types/audit';

/** How often the page refreshes itself. Slow enough to be a status page, not a monitor. */
const REFRESH_MS = 10_000;

function DependencyRow({ name, status }: { name: string; status: DependencyStatus }) {
  return (
    <div>
      <dt>{name}</dt>
      <dd>
        <span className={`pill pill--${status.up ? 'ok' : 'error'}`}>
          {/* A glyph and a word as well as the colour: "is the database up?" is not a
              question to answer with a shade of green. */}
          <span className="pill-glyph" aria-hidden="true">{status.up ? '✓' : '✕'}</span>
          {status.up ? 'Up' : 'Down'}
        </span>
        <span className="row-sub">{status.responseMs} ms</span>
      </dd>
    </div>
  );
}

/**
 * Operational status, for an administrator.
 *
 * <p>Renders exactly what the server curated. The endpoint behind it deliberately carries no
 * credentials, connection strings, environment variables or paths, so there is nothing here
 * to filter out — the filtering happened when the endpoint was written.
 */
export function AdminSystemPage() {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const fetched = await getSystemStatus(signal);
      if (signal?.aborted) return;
      setStatus(fetched);
      setError(null);
    } catch (caught) {
      if (signal?.aborted) return;
      setError(describeApiError(caught));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    const timer = setInterval(() => void load(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  if (loading && !status) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      </div>
    );
  }

  if (error && !status) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the system status</p>
          <p className="status-detail">{error}</p>
        </div>
      </div>
    );
  }

  if (!status) {
    return null;
  }

  return (
    <div className="page">
      <section className="hero">
        <h1>System status</h1>
        <p className="lede">
          A curated operational view. It carries no credentials or configuration; liveness and
          readiness probes remain separate and unauthenticated.
        </p>
      </section>

      {error && (
        <div className="panel" role="alert">
          <p className="status status--error">The last refresh failed</p>
          <p className="status-detail">{error}</p>
        </div>
      )}

      <section className="panel">
        <h2>Dependencies</h2>
        <dl className="detail-grid">
          <DependencyRow name="PostgreSQL" status={status.database} />
          <DependencyRow name="Redis" status={status.redis} />
        </dl>
      </section>

      <section className="panel">
        <h2>Judging queue</h2>
        <dl className="detail-grid">
          <div>
            <dt>Waiting</dt>
            {/* Null means Redis could not be reached, which is not the same as "none
                waiting" — saying "unknown" is the honest rendering of that. */}
            <dd>{status.queue.pending ?? 'unknown'}</dd>
          </div>
          <div>
            <dt>Being judged</dt>
            <dd>{status.queue.processing ?? 'unknown'}</dd>
          </div>
        </dl>
        <p className="field-hint">
          A waiting count that climbs while the judging count stays flat is what a stopped
          worker looks like from here. Worker heartbeats are not reported: the API server has
          no route to the worker&rsquo;s network.
        </p>
      </section>

      <section className="panel">
        <h2>Build</h2>
        <dl className="detail-grid">
          <div>
            <dt>Version</dt>
            <dd>{status.version}</dd>
          </div>
          <div>
            <dt>Server time</dt>
            <dd>{new Date(status.serverTime).toLocaleString()}</dd>
          </div>
          <div>
            <dt>Audit events</dt>
            <dd>{status.auditEventCount.toLocaleString()}</dd>
          </div>
        </dl>
      </section>
    </div>
  );
}
