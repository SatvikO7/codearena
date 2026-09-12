import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listProblems } from '../services/problemService';
import { describeApiError } from '../services/apiClient';
import { DifficultyBadge } from '../components/DifficultyBadge';
import { Pagination } from '../components/Pagination';
import { DIFFICULTIES, PROBLEM_TAGS } from '../types/problem';
import type { Difficulty, PageResponse, ProblemSummary, ProblemTag } from '../types/problem';

/**
 * The problem catalogue.
 *
 * <p>Only published problems ever appear here, and that is the server's doing: this page
 * sends no status filter and could not widen the result if it tried. The filtering it does
 * send is a convenience, not a security control.
 */
export function ProblemsPage() {
  const [page, setPage] = useState(0);
  const [difficulty, setDifficulty] = useState<Difficulty | ''>('');
  const [tag, setTag] = useState<ProblemTag | ''>('');
  const [search, setSearch] = useState('');
  const [submittedSearch, setSubmittedSearch] = useState('');

  const [data, setData] = useState<PageResponse<ProblemSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        const result = await listProblems(
          { page, size: 20, difficulty, tag, search: submittedSearch },
          signal,
        );
        setData(result);
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [page, difficulty, tag, submittedSearch],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Fetching from the server is synchronisation with an external system, which is what
    // effects are for. The state updates happen after the request resolves rather than
    // synchronously here, so there is no cascading render to avoid.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  /** Any filter change invalidates the current page number. */
  function changeFilter<T>(setter: (value: T) => void) {
    return (value: T) => {
      setter(value);
      setPage(0);
    };
  }

  return (
    <div className="page">
      <section className="hero">
        <h1>Problems</h1>
        <p className="lede">Browse the published catalogue.</p>
      </section>

      <section className="panel filters" aria-label="Filters">
        <form
          className="filter-row"
          onSubmit={(event) => {
            event.preventDefault();
            setSubmittedSearch(search);
            setPage(0);
          }}
        >
          <div className="filter-field filter-field--grow">
            <label htmlFor="search">Search</label>
            <input
              id="search"
              type="search"
              placeholder="Title or slug"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>

          <div className="filter-field">
            <label htmlFor="difficulty">Difficulty</label>
            <select
              id="difficulty"
              value={difficulty}
              onChange={(event) => changeFilter(setDifficulty)(event.target.value as Difficulty | '')}
            >
              <option value="">All</option>
              {DIFFICULTIES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>

          <div className="filter-field">
            <label htmlFor="tag">Topic</label>
            <select
              id="tag"
              value={tag}
              onChange={(event) => changeFilter(setTag)(event.target.value as ProblemTag | '')}
            >
              <option value="">All</option>
              {PROBLEM_TAGS.map((value) => (
                <option key={value} value={value}>
                  {value.replace(/_/g, ' ')}
                </option>
              ))}
            </select>
          </div>

          <button className="button" type="submit">
            Apply
          </button>
        </form>
      </section>

      {loading && (
        <p className="status status--pending" role="status">
          Loading problems&hellip;
        </p>
      )}

      {error && !loading && (
        <div className="panel" role="alert">
          <p className="status status--error">Could not load problems</p>
          <p className="status-detail">{error}</p>
        </div>
      )}

      {!loading && !error && data && data.items.length === 0 && (
        <section className="panel empty-state">
          <h2>No problems match</h2>
          <p>
            {submittedSearch || difficulty || tag
              ? 'Try widening your filters.'
              : 'Nothing has been published yet.'}
          </p>
        </section>
      )}

      {!loading && !error && data && data.items.length > 0 && (
        <>
          <section className="panel table-panel">
            <table className="data-table">
              <caption className="visually-hidden">Published problems</caption>
              <thead>
                <tr>
                  <th scope="col">Title</th>
                  <th scope="col">Difficulty</th>
                  <th scope="col">Topics</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((problem) => (
                  <tr key={problem.id}>
                    <td>
                      <Link to={`/problems/${problem.slug}`}>{problem.title}</Link>
                    </td>
                    <td>
                      <DifficultyBadge difficulty={problem.difficulty} />
                    </td>
                    <td className="tag-cell">
                      {problem.tags.length === 0 ? (
                        <span className="muted">&mdash;</span>
                      ) : (
                        problem.tags.map((value) => (
                          <span key={value} className="tag">
                            {value.replace(/_/g, ' ')}
                          </span>
                        ))
                      )}
                    </td>
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
