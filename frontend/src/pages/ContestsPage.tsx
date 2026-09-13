import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listContests } from '../services/contestService';
import { formatLocal } from '../services/contestClock';
import { describeApiError } from '../services/apiClient';
import { ContestStatusPill } from '../components/ContestStatusPill';
import { Pagination } from '../components/Pagination';
import { CONTEST_STATUS_LABELS } from '../types/contest';
import type { ContestStatus, ContestSummary } from '../types/contest';
import type { PageResponse } from '../types/problem';

/** The states a user can usefully filter by. DRAFT is absent: they never see one. */
const FILTERS: ContestStatus[] = ['LIVE', 'UPCOMING', 'ENDED', 'CANCELLED'];

export function ContestsPage() {
  const [status, setStatus] = useState<ContestStatus | ''>('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<ContestSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setData(await listContests({ status: status || undefined, page, size: 20 }, signal));
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [status, page],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return (
    <div className="page">
      <section className="hero">
        <h1>Contests</h1>
        <p className="lede">Compete against the clock and everybody else.</p>
      </section>

      <section className="panel filters" aria-label="Filters">
        <div className="filter-row">
          <div className="filter-field">
            <label htmlFor="contest-status">Status</label>
            <select
              id="contest-status"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as ContestStatus | '');
                setPage(0);
              }}
            >
              <option value="">All</option>
              {FILTERS.map((value) => (
                <option key={value} value={value}>
                  {CONTEST_STATUS_LABELS[value]}
                </option>
              ))}
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
          <p className="status status--error">Could not load contests</p>
          <p className="status-detail">{error}</p>
        </div>
      )}

      {!loading && !error && data && data.items.length === 0 && (
        <section className="panel empty-state">
          <h2>No contests</h2>
          <p>Nothing scheduled matches this filter.</p>
        </section>
      )}

      {!loading && !error && data && data.items.length > 0 && (
        <>
          <section className="panel table-panel">
            <table className="data-table">
              <caption className="visually-hidden">Contests</caption>
              <thead>
                <tr>
                  <th scope="col">Contest</th>
                  <th scope="col">Status</th>
                  <th scope="col">Starts</th>
                  <th scope="col">Ends</th>
                  <th scope="col">Problems</th>
                  <th scope="col">Entrants</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((contest) => (
                  <tr key={contest.id}>
                    <td>
                      <Link to={`/contests/${contest.id}`}>{contest.title}</Link>
                      {contest.registered && <span className="row-sub">Registered</span>}
                    </td>
                    <td>
                      <ContestStatusPill status={contest.status} />
                    </td>
                    {/* Local time, with the zone named: a contestant who misreads a start
                        time by five and a half hours misses the contest. */}
                    <td>{formatLocal(contest.startAt)}</td>
                    <td>{formatLocal(contest.endAt)}</td>
                    <td>{contest.problemCount}</td>
                    <td>{contest.participantCount}</td>
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
