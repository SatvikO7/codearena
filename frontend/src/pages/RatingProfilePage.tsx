import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getRatingHistory, getRatingProfile } from '../services/ratingService';
import { describeApiError } from '../services/apiClient';
import { Pagination } from '../components/Pagination';
import { RatingBadge, RatingChangeBadge } from '../components/RatingBadge';
import { RatingGraph } from '../components/RatingGraph';
import { formatLocal } from '../services/contestClock';
import type { HistoryEntry, RatingProfile } from '../types/rating';
import type { PageResponse } from '../types/problem';

/**
 * One competitor's rating, and every rated contest behind it.
 *
 * <p>Public among users, like the leaderboard: a ranking that named somebody would be odd if
 * their page then refused to say what their rating was. What it does not show is anything
 * private — the server does not send an email, a role or an account state, so there is none
 * here to leak.
 *
 * <p>Two requests rather than one. The profile carries the graph and the ten most recent
 * results; the full history is paged separately, because a competitor with two hundred
 * contests should not be sending two hundred rows to draw a page.
 */
export function RatingProfilePage() {
  const { userId = '' } = useParams();

  const [profile, setProfile] = useState<RatingProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [historyPage, setHistoryPage] = useState(0);
  const [history, setHistory] = useState<PageResponse<HistoryEntry> | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const loadProfile = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setProfile(await getRatingProfile(userId, signal));
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [userId],
  );

  const loadHistory = useCallback(
    async (signal: AbortSignal) => {
      setHistoryError(null);
      try {
        setHistory(await getRatingHistory(userId, { page: historyPage, size: 20 }, signal));
      } catch (caught) {
        if (signal.aborted) return;
        // Reported separately from the profile. A failure to page the history should not
        // blank out a rating the page has already loaded successfully.
        setHistoryError(describeApiError(caught));
      }
    },
    [userId, historyPage],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void loadProfile(controller.signal);
    return () => controller.abort();
  }, [loadProfile]);

  useEffect(() => {
    const controller = new AbortController();
    // oxlint-disable-next-line set-state-in-effect
    void loadHistory(controller.signal);
    return () => controller.abort();
  }, [loadHistory]);

  if (loading) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      </div>
    );
  }

  if (error || !profile) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load this competitor</p>
          <p className="status-detail">{error ?? 'No such competitor.'}</p>
          <Link to="/rankings" className="button button--quiet">
            Back to the rankings
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <section className="hero">
        <h1>{profile.username}</h1>
        <p className="lede">
          {profile.rated
            ? `${profile.contestsRated} rated contest${profile.contestsRated === 1 ? '' : 's'}.`
            : 'Has not competed in a rated contest yet.'}
        </p>
      </section>

      <section className="panel">
        <h2>Standing</h2>
        <dl className="detail-grid">
          <div>
            <dt>Rating</dt>
            <dd>
              <RatingBadge rating={profile.rating} />
            </dd>
          </div>
          <div>
            <dt>Peak</dt>
            {/* Never falls, even when the rating does. */}
            <dd>{profile.peakRating ?? <span className="muted">&mdash;</span>}</dd>
          </div>
          <div>
            <dt>Global rank</dt>
            <dd>{profile.rank ?? <span className="muted">&mdash;</span>}</dd>
          </div>
          <div>
            <dt>Rated contests</dt>
            <dd>{profile.contestsRated}</dd>
          </div>
          <div>
            <dt>Last rated</dt>
            <dd>
              {profile.lastRatedAt ? (
                formatLocal(profile.lastRatedAt)
              ) : (
                <span className="muted">&mdash;</span>
              )}
            </dd>
          </div>
        </dl>

        {!profile.rated && (
          <p className="status status--muted">
            This account has no rating. That is not the same as a rating of zero &mdash; it
            means no rated contest has been completed, so there is nothing to measure yet.
          </p>
        )}
      </section>

      {profile.rated && (
        <section className="panel">
          <h2>Progression</h2>
          <RatingGraph points={profile.progression} />
        </section>
      )}

      <section className="panel table-panel">
        <h2>Rated contests</h2>

        {historyError && (
          <div role="alert">
            <p className="status status--error">Could not load the history</p>
            <p className="status-detail">{historyError}</p>
          </div>
        )}

        {!historyError && history && history.items.length === 0 && (
          <p className="status status--muted">
            No rated contests yet. Unrated contests are not listed here, because they produce
            no rating and a row of zeroes would report a result that does not exist.
          </p>
        )}

        {!historyError && history && history.items.length > 0 && (
          <>
            <table className="data-table">
              <caption className="visually-hidden">Rated contest history</caption>
              <thead>
                <tr>
                  <th scope="col">Contest</th>
                  <th scope="col">Finished</th>
                  <th scope="col">Rank</th>
                  <th scope="col">Score</th>
                  <th scope="col">Penalty</th>
                  <th scope="col">Change</th>
                  <th scope="col">Rating</th>
                </tr>
              </thead>
              <tbody>
                {history.items.map((entry) => (
                  <tr key={entry.contestId}>
                    <td>
                      <Link to={`/contests/${entry.contestId}`}>{entry.contestTitle}</Link>
                    </td>
                    <td>{formatLocal(entry.contestEndAt)}</td>
                    <td className="numeric">
                      {entry.rank} <span className="muted">/ {entry.participantCount}</span>
                    </td>
                    <td className="numeric">{entry.score}</td>
                    <td className="numeric">{entry.penalty}</td>
                    <td>
                      <RatingChangeBadge change={entry.ratingChange} />
                    </td>
                    <td className="numeric">
                      <span className="muted">{entry.ratingBefore} &rarr;</span>{' '}
                      {entry.ratingAfter}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <Pagination
              page={history.page}
              totalPages={history.totalPages}
              totalItems={history.totalItems}
              hasNext={history.hasNext}
              hasPrevious={history.hasPrevious}
              onChange={setHistoryPage}
              noun="contest"
            />
          </>
        )}
      </section>
    </div>
  );
}
