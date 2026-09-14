import { useCallback, useEffect, useState } from 'react';
import { getSystemStatus } from '../../services/auditService';
import { describeApiError, isRateLimited, retryAfterSeconds } from '../../services/apiClient';
import type { DependencyStatus, SystemStatus, WorkerStatus } from '../../types/audit';

/** How often the page refreshes itself. Slow enough to be a status page, not a monitor. */
const REFRESH_MS = 10_000;

/** Used if the server ever asks this page to slow down without saying for how long. */
const BACKOFF_MS = 60_000;

/**
 * Above this, a waiting submission has stopped waiting for a slow problem and started
 * waiting for a worker that is not coming. Matches the server's own threshold, which is what
 * decides DEGRADED — duplicated here only to colour a number, never to make the judgement.
 */
const STUCK_QUEUE_SECONDS = 300;

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

/** "9 minutes", "41 seconds" — a duration somebody can act on rather than a raw count. */
function describeSeconds(seconds: number): string {
  if (seconds < 60) {
    return `${seconds}s`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  }
  const hours = Math.floor(seconds / 3600);
  return `${hours}h ${Math.floor((seconds % 3600) / 60)}m`;
}

function StateBanner({ state }: { state: SystemStatus['state'] }) {
  const copy = {
    READY: {
      tone: 'ok',
      glyph: '✓',
      title: 'Ready',
      detail: 'Dependencies answer and the judging queue is moving.',
    },
    DEGRADED: {
      tone: 'pending',
      glyph: '!',
      title: 'Degraded',
      detail:
        'The API is serving normally and work is not being judged. Check the workers below: ' +
        'every request can succeed while nothing gets judged at all.',
    },
    UNAVAILABLE: {
      tone: 'error',
      glyph: '✕',
      title: 'Unavailable',
      detail: 'A dependency is not answering. This instance cannot serve traffic.',
    },
  }[state];

  return (
    <section className={`panel panel--${copy.tone}`} role={state === 'READY' ? undefined : 'alert'}>
      <h2>
        <span className="pill-glyph" aria-hidden="true">
          {copy.glyph}
        </span>{' '}
        {copy.title}
      </h2>
      <p className="status-detail">{copy.detail}</p>
    </section>
  );
}

/**
 * @param serverTime when the server produced this response.
 *
 * The age is measured against the server's clock rather than the browser's. Both timestamps
 * in the comparison then come from the same machine, so a viewer whose laptop clock is a
 * minute out does not see every worker as a minute staler than it is — and the render stays
 * pure, which reading `Date.now()` here would not.
 */
function WorkerRow({ worker, serverTime }: { worker: WorkerStatus; serverTime: string }) {
  const lastSeen = worker.lastSeenAt ? new Date(worker.lastSeenAt).getTime() : null;
  const secondsAgo =
    lastSeen === null
      ? null
      : Math.max(0, Math.round((new Date(serverTime).getTime() - lastSeen) / 1000));

  // Draining is checked first: a worker that is shutting down deliberately is not a fault,
  // and colouring a planned rollout red is how a status page teaches people to ignore it.
  const tone = worker.draining ? 'pending' : worker.healthy ? 'ok' : 'error';
  const label = worker.draining ? 'Draining' : worker.healthy ? 'Healthy' : 'Stale';

  return (
    <tr>
      <td>
        <code>{worker.id}</code>
      </td>
      <td>
        <span className={`pill pill--${tone}`}>{label}</span>
      </td>
      <td>{secondsAgo === null ? 'never' : `${describeSeconds(secondsAgo)} ago`}</td>
      <td>
        {worker.activeJobs} / {worker.concurrency}
      </td>
      <td>{worker.judged.toLocaleString()}</td>
      <td>{worker.infrastructureFailures.toLocaleString()}</td>
      <td>
        <code>{worker.version}</code>
      </td>
    </tr>
  );
}

/**
 * Operational status, for an administrator.
 *
 * Renders exactly what the server curated. The endpoint behind it deliberately carries no
 * credentials, connection strings, environment variables or paths, so there is nothing here
 * to filter out — the filtering happened when the endpoint was written.
 *
 * The ordering of the page is the order somebody triaging would want it: what is wrong
 * overall, then whether the judge is working, then the dependencies, then the build. The
 * state banner is first because it is the only thing on the page that has already done the
 * thinking.
 */
export function AdminSystemPage() {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // A status page that keeps polling through a 429 is the shape of load the limit exists to
  // shed. Held in a ref-like state that the poller reads rather than in its dependencies, so
  // a refusal does not tear down and rebuild the timer.
  const [pausedUntil, setPausedUntil] = useState(0);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      if (Date.now() < pausedUntil) {
        return;
      }
      try {
        const fetched = await getSystemStatus(signal);
        if (signal?.aborted) return;
        setStatus(fetched);
        setError(null);
      } catch (caught) {
        if (signal?.aborted) return;
        if (isRateLimited(caught)) {
          const seconds = retryAfterSeconds(caught);
          setPausedUntil(Date.now() + (seconds === undefined ? BACKOFF_MS : seconds * 1000));
        }
        setError(describeApiError(caught));
      } finally {
        if (!signal?.aborted) setLoading(false);
      }
    },
    [pausedUntil],
  );

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

  const { queue, workers } = status;
  const healthyWorkers = workers.filter((worker) => worker.healthy).length;
  const queueStuck =
    queue.oldestPendingAgeSeconds !== null && queue.oldestPendingAgeSeconds > STUCK_QUEUE_SECONDS;

  return (
    <div className="page">
      <section className="hero">
        <h1>System status</h1>
        <p className="lede">
          A curated operational view. It carries no credentials or configuration; liveness and
          readiness probes remain separate and unauthenticated.
        </p>
      </section>

      <StateBanner state={status.state} />

      {error && (
        <div className="panel" role="alert">
          <p className="status status--error">The last refresh failed</p>
          <p className="status-detail">{error}</p>
        </div>
      )}

      <section className="panel">
        <h2>Judge workers</h2>
        {workers.length === 0 ? (
          <p className="status status--error">
            No workers are registered. Nothing can be judged while this is true — a worker
            deregisters when it stops cleanly and its record expires a few minutes after it
            stops reporting at all.
          </p>
        ) : (
          <>
            <p className="field-hint">
              {healthyWorkers} of {workers.length} reporting. A stale worker is one whose
              process may well still be running and has stopped saying so, which is exactly
              what a container health check cannot tell you.
            </p>
            <div className="table-panel">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Worker</th>
                    <th>State</th>
                    <th>Last seen</th>
                    <th>Active</th>
                    <th>Judged</th>
                    <th>Infra failures</th>
                    <th>Version</th>
                  </tr>
                </thead>
                <tbody>
                  {workers.map((worker) => (
                    <WorkerRow key={worker.id} worker={worker} serverTime={status.serverTime} />
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>

      <section className="panel">
        <h2>Judging queue</h2>
        <dl className="detail-grid">
          <div>
            <dt>Waiting</dt>
            {/* Null means Redis could not be reached, which is not the same as "none
                waiting" — saying "unknown" is the honest rendering of that. */}
            <dd>{queue.pending ?? 'unknown'}</dd>
          </div>
          <div>
            <dt>Being judged</dt>
            <dd>{queue.processing ?? 'unknown'}</dd>
          </div>
          <div>
            <dt>Oldest wait</dt>
            <dd className={queueStuck ? 'status status--error' : undefined}>
              {queue.oldestPendingAgeSeconds === null
                ? 'nothing waiting'
                : describeSeconds(queue.oldestPendingAgeSeconds)}
            </dd>
          </div>
          <div>
            <dt>Being retried</dt>
            <dd>{queue.retrying}</dd>
          </div>
          <div>
            <dt>Judge failures (1h)</dt>
            <dd className={queue.systemErrorsLastHour > 0 ? 'status status--error' : undefined}>
              {queue.systemErrorsLastHour}
            </dd>
          </div>
        </dl>
        <p className="field-hint">
          Depth alone cannot tell a busy queue from a stuck one; the oldest wait can. Judge
          failures are never the submitter&rsquo;s fault — they mean the judge gave up.
        </p>
      </section>

      <section className="panel">
        <h2>Dependencies</h2>
        <dl className="detail-grid">
          <DependencyRow name="PostgreSQL" status={status.database} />
          <DependencyRow name="Redis" status={status.redis} />
        </dl>
      </section>

      <section className="panel">
        <h2>Build</h2>
        <dl className="detail-grid">
          <div>
            <dt>Version</dt>
            <dd>{status.version}</dd>
          </div>
          <div>
            <dt>Uptime</dt>
            <dd>{describeSeconds(status.uptimeSeconds)}</dd>
          </div>
          <div>
            <dt>Server time</dt>
            <dd>{new Date(status.serverTime).toLocaleString()}</dd>
          </div>
          <div>
            <dt>Audit log</dt>
            <dd>
              {status.audit.readable ? (
                <>
                  {status.auditEventCount.toLocaleString()} events
                  <span className="row-sub">{status.audit.eventsLastHour} in the last hour</span>
                </>
              ) : (
                <span className="status status--error">unreadable</span>
              )}
            </dd>
          </div>
        </dl>
      </section>
    </div>
  );
}
