import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { changeLifecycle, listAdminProblems } from '../../services/problemService';
import type { LifecycleAction } from '../../services/problemService';
import { describeApiError } from '../../services/apiClient';
import { DifficultyBadge } from '../../components/DifficultyBadge';
import { StatusBadge } from '../../components/StatusBadge';
import { Pagination } from '../../components/Pagination';
import type { PageResponse, ProblemStatus, ProblemSummary } from '../../types/problem';

const STATUSES: ProblemStatus[] = ['DRAFT', 'PUBLISHED', 'ARCHIVED'];

/** The lifecycle moves offered for each status, mirroring the server's transition rules. */
function actionsFor(status: ProblemStatus): LifecycleAction[] {
  switch (status) {
    case 'DRAFT':
      return ['publish', 'archive'];
    case 'PUBLISHED':
      return ['unpublish', 'archive'];
    case 'ARCHIVED':
      return ['restore'];
  }
}

/**
 * Problem management.
 *
 * <p>Reached through a role-checked route, but that check is convenience only: every
 * endpoint this page calls is independently authorised, so a user who forces their way
 * here sees a page full of 403s rather than anybody's drafts.
 */
export function AdminProblemsPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ProblemStatus | ''>('');
  const [data, setData] = useState<PageResponse<ProblemSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setData(await listAdminProblems({ page, size: 20, status }, signal));
      } catch (caught) {
        if (signal?.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal?.aborted) setLoading(false);
      }
    },
    [page, status],
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

  async function runAction(id: string, action: LifecycleAction) {
    setBusyId(id);
    setActionError(null);
    try {
      await changeLifecycle(id, action);
      await load();
    } catch (caught) {
      // Publishing an incomplete problem fails here with the server's list of what is
      // missing. Surfacing that verbatim is more useful than a generic failure.
      setActionError(describeApiError(caught));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="page">
      <section className="hero">
        <div className="title-row">
          <h1>Problem management</h1>
          <Link className="button" to="/admin/problems/new">
            New problem
          </Link>
        </div>
        <p className="lede">Create, edit and move problems through their lifecycle.</p>
      </section>

      <section className="panel filters">
        <div className="filter-row">
          <div className="filter-field">
            <label htmlFor="status">Status</label>
            <select
              id="status"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as ProblemStatus | '');
                setPage(0);
              }}
            >
              <option value="">All</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>
        </div>
      </section>

      {actionError && (
        <p className="form-error" role="alert">
          {actionError}
        </p>
      )}

      {loading && (
        <p className="status status--pending" role="status">
          Loading&hellip;
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
          <h2>No problems yet</h2>
          <p>Create the first one to get started.</p>
          <Link className="button" to="/admin/problems/new">
            New problem
          </Link>
        </section>
      )}

      {!loading && !error && data && data.items.length > 0 && (
        <>
          <section className="panel table-panel">
            <table className="data-table">
              <caption className="visually-hidden">All problems</caption>
              <thead>
                <tr>
                  <th scope="col">Title</th>
                  <th scope="col">Status</th>
                  <th scope="col">Difficulty</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((problem) => (
                  <tr key={problem.id}>
                    <td>
                      <Link to={`/admin/problems/${problem.id}`}>{problem.title}</Link>
                      <span className="row-sub">{problem.slug}</span>
                    </td>
                    <td>{problem.status && <StatusBadge status={problem.status} />}</td>
                    <td>
                      <DifficultyBadge difficulty={problem.difficulty} />
                    </td>
                    <td className="action-cell">
                      {problem.status &&
                        actionsFor(problem.status).map((action) => (
                          <button
                            key={action}
                            type="button"
                            className="button button--quiet"
                            disabled={busyId === problem.id}
                            onClick={() => runAction(problem.id, action)}
                          >
                            {action}
                          </button>
                        ))}
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
