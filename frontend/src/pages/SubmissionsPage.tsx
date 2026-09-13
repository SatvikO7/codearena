import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listSubmissions } from '../services/submissionService';
import { listProblems } from '../services/problemService';
import { describeApiError } from '../services/apiClient';
import { StatusPill } from '../components/SubmissionVerdict';
import { Pagination } from '../components/Pagination';
import { LANGUAGES, STATUS_LABELS } from '../types/submission';
import type { Language, SubmissionStatus, SubmissionSummary } from '../types/submission';
import type { PageResponse, ProblemSummary } from '../types/problem';

/** Offered as filters in the order a user is most likely to want them. */
const FILTERABLE_STATUSES: SubmissionStatus[] = [
  'ACCEPTED',
  'WRONG_ANSWER',
  'COMPILATION_ERROR',
  'RUNTIME_ERROR',
  'TIME_LIMIT_EXCEEDED',
  'MEMORY_LIMIT_EXCEEDED',
  'SYSTEM_ERROR',
  'QUEUED',
  'RUNNING',
];

/**
 * Your own submission history.
 *
 * <p>Every filter is a server-side query parameter, and the page never holds more than one
 * page of rows. Filtering in the browser would mean fetching a whole history to render
 * twenty lines of it, which stops working at exactly the point it starts to matter.
 *
 * <p>The server scopes this to the authenticated caller; nothing here filters by user,
 * because nothing here could be trusted to.
 */
export function SubmissionsPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<SubmissionStatus | ''>('');
  const [language, setLanguage] = useState<Language | ''>('');
  const [problemId, setProblemId] = useState('');

  const [data, setData] = useState<PageResponse<SubmissionSummary> | null>(null);
  const [problems, setProblems] = useState<ProblemSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setData(
          await listSubmissions(
            {
              page,
              size: 20,
              status: status || undefined,
              language: language || undefined,
              problemId: problemId || undefined,
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
    [page, status, language, problemId],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  // The problem filter needs names to show. Failing to load them degrades the filter to
  // "All" rather than breaking the page, so the error is deliberately not surfaced.
  useEffect(() => {
    const controller = new AbortController();
    listProblems({ size: 100 }, controller.signal)
      // oxlint-disable-next-line set-state-in-effect
      .then((result) => setProblems(result.items))
      .catch(() => setProblems([]));
    return () => controller.abort();
  }, []);

  /** Any filter change invalidates the current page number. */
  function applyFilter(change: () => void) {
    change();
    setPage(0);
  }

  const hasFilters = status !== '' || language !== '' || problemId !== '';

  return (
    <div className="page">
      <section className="hero">
        <h1>My submissions</h1>
        <p className="lede">Everything you have submitted, newest first.</p>
      </section>

      <section className="panel filters" aria-label="Filters">
        <div className="filter-row">
          <div className="filter-field filter-field--grow">
            <label htmlFor="problem">Problem</label>
            <select
              id="problem"
              value={problemId}
              onChange={(event) => applyFilter(() => setProblemId(event.target.value))}
            >
              <option value="">All problems</option>
              {problems.map((problem) => (
                <option key={problem.id} value={problem.id}>
                  {problem.title}
                </option>
              ))}
            </select>
          </div>

          <div className="filter-field">
            <label htmlFor="status">Verdict</label>
            <select
              id="status"
              value={status}
              onChange={(event) =>
                applyFilter(() => setStatus(event.target.value as SubmissionStatus | ''))
              }
            >
              <option value="">All</option>
              {FILTERABLE_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {STATUS_LABELS[value]}
                </option>
              ))}
            </select>
          </div>

          <div className="filter-field">
            <label htmlFor="language">Language</label>
            <select
              id="language"
              value={language}
              onChange={(event) => applyFilter(() => setLanguage(event.target.value as Language | ''))}
            >
              <option value="">All</option>
              {LANGUAGES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>

          {hasFilters && (
            <button
              type="button"
              className="button button--quiet"
              onClick={() =>
                applyFilter(() => {
                  setStatus('');
                  setLanguage('');
                  setProblemId('');
                })
              }
            >
              Clear filters
            </button>
          )}
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
          <h2>{hasFilters ? 'No submissions match' : 'Nothing here yet'}</h2>
          <p>
            {hasFilters
              ? 'Try widening your filters.'
              : 'Solve a problem and your submissions will appear here.'}
          </p>
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
                  <th scope="col">Verdict</th>
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
                      <span className="row-sub">{submission.id.slice(0, 8)}</span>
                    </td>
                    <td>{submission.language}</td>
                    <td>
                      <StatusPill status={submission.status} />
                    </td>
                    <td>
                      {submission.testsTotal != null
                        ? `${submission.testsPassed ?? 0}/${submission.testsTotal}`
                        : '—'}
                    </td>
                    <td>
                      {submission.runtimeMs != null ? `${submission.runtimeMs} ms` : '—'}
                    </td>
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
