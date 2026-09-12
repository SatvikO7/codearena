import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listSubmissions } from '../services/submissionService';
import { describeApiError } from '../services/apiClient';
import { StatusPill } from '../components/SubmissionVerdict';
import { Pagination } from '../components/Pagination';
import type { PageResponse } from '../types/problem';
import type { SubmissionStatus, SubmissionSummary } from '../types/submission';

/** Your own submission history. The server scopes this to the caller; nothing here filters. */
export function SubmissionsPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<SubmissionStatus | ''>('');
  const [data, setData] = useState<PageResponse<SubmissionSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setData(await listSubmissions({ page, size: 20, status: status || undefined }, signal));
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [page, status],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; the state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return (
    <div className="page">
      <section className="hero">
        <h1>My submissions</h1>
        <p className="lede">Everything you have submitted, newest first.</p>
      </section>

      <section className="panel filters">
        <div className="filter-row">
          <div className="filter-field">
            <label htmlFor="status">Status</label>
            <select
              id="status"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as SubmissionStatus | '');
                setPage(0);
              }}
            >
              <option value="">All</option>
              <option value="ACCEPTED">Accepted</option>
              <option value="WRONG_ANSWER">Wrong answer</option>
              <option value="COMPILATION_ERROR">Compilation error</option>
              <option value="RUNTIME_ERROR">Runtime error</option>
              <option value="TIME_LIMIT_EXCEEDED">Time limit exceeded</option>
              <option value="MEMORY_LIMIT_EXCEEDED">Memory limit exceeded</option>
              <option value="QUEUED">Queued</option>
              <option value="RUNNING">Running</option>
            </select>
          </div>
        </div>
      </section>

      {loading && (
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      )}

      {error && !loading && (
        <div className="panel" role="alert">
          <p className="status status--error">Could not load your submissions</p>
          <p className="status-detail">{error}</p>
        </div>
      )}

      {!loading && !error && data && data.items.length === 0 && (
        <section className="panel empty-state">
          <h2>Nothing here yet</h2>
          <p>Solve a problem and your submissions will appear here.</p>
          <Link className="button" to="/problems">
            Browse problems
          </Link>
        </section>
      )}

      {!loading && !error && data && data.items.length > 0 && (
        <>
          <section className="panel table-panel">
            <table className="data-table">
              <caption className="visually-hidden">Your submissions</caption>
              <thead>
                <tr>
                  <th scope="col">Problem</th>
                  <th scope="col">Language</th>
                  <th scope="col">Status</th>
                  <th scope="col">Tests</th>
                  <th scope="col">Runtime</th>
                  <th scope="col">Submitted</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((submission) => (
                  <tr key={submission.id}>
                    <td>
                      <Link to={`/submissions/${submission.id}`}>{submission.problemTitle}</Link>
                    </td>
                    <td>{submission.language}</td>
                    <td>
                      <StatusPill status={submission.status} />
                    </td>
                    <td>
                      {submission.testsTotal
                        ? `${submission.testsPassed ?? 0}/${submission.testsTotal}`
                        : '—'}
                    </td>
                    <td>{submission.runtimeMs != null ? `${submission.runtimeMs} ms` : '—'}</td>
                    <td>{new Date(submission.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
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
