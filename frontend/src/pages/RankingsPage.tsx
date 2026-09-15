import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getRankings } from '../services/ratingService';
import { describeApiError } from '../services/apiClient';
import { Pagination } from '../components/Pagination';
import { RatingBadge } from '../components/RatingBadge';
import type { RankingEntry } from '../types/rating';
import type { PageResponse } from '../types/problem';

/**
 * The global leaderboard.
 *
 * <p>Only competitors who have completed a rated contest appear. An account that has never
 * competed is absent rather than listed at the starting rating — it has no rating, and
 * showing one would be inventing a result.
 *
 * <p>Paging is the server's: the browser asks for a page and receives a page. It never
 * receives the whole table, which is what keeps this the same weight at ten competitors and
 * at a hundred thousand.
 */
export function RankingsPage() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageResponse<RankingEntry> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setData(await getRankings({ page, size: 50 }, signal));
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [page],
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
        <h1>Rankings</h1>
        <p className="lede">Everybody who has competed in a rated contest, best first.</p>
      </section>

      {loading && (
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      )}

      {error && !loading && (
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the rankings</p>
          <p className="status-detail">{error}</p>
          <button type="button" className="button button--quiet" onClick={() => setPage(page)}>
            Try again
          </button>
        </div>
      )}

      {!loading && !error && data && data.items.length === 0 && (
        <section className="panel empty-state">
          <h2>Nobody is rated yet</h2>
          <p>
            Ratings appear once a rated contest has finished and been finalised. Until then
            there is nothing to rank &mdash; which is different from everybody being equal.
          </p>
          <Link to="/contests" className="button">
            See the contests
          </Link>
        </section>
      )}

      {!loading && !error && data && data.items.length > 0 && (
        <>
          <section className="panel table-panel">
            <table className="data-table">
              <caption className="visually-hidden">Global rankings</caption>
              <thead>
                <tr>
                  <th scope="col">Rank</th>
                  <th scope="col">Competitor</th>
                  <th scope="col">Rating</th>
                  <th scope="col">Peak</th>
                  <th scope="col">Contests</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((entry) => (
                  <tr key={entry.userId}>
                    {/* Ties genuinely share a rank, so the same number can appear twice.
                        That is the result, not a rendering mistake. */}
                    <td className="numeric">{entry.rank}</td>
                    <td>
                      <Link to={`/users/${entry.userId}/rating`}>{entry.username}</Link>
                    </td>
                    <td>
                      <RatingBadge rating={entry.rating} />
                    </td>
                    <td className="numeric">{entry.peakRating}</td>
                    <td className="numeric">{entry.contestsRated}</td>
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
            noun="competitor"
          />
        </>
      )}
    </div>
  );
}
