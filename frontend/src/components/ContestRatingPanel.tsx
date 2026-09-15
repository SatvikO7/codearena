import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getContestRating } from '../services/ratingService';
import { describeApiError } from '../services/apiClient';
import { RatingChangeBadge } from './RatingBadge';
import { formatLocal } from '../services/contestClock';
import type { ContestRatingResult } from '../types/rating';

interface Props {
  contestId: string;
  /** Whether the contest moves ratings at all, from the contest itself. */
  rated: boolean;
}

/**
 * What this contest did to the viewer's rating.
 *
 * <h2>Four states, kept apart on purpose</h2>
 * The temptation is to collapse them — render a change of zero whenever there is no number,
 * and be done. That would be a lie in three of the four cases, and the one it would tell most
 * often is the worst: a competitor whose contest has ended but not been rated would be shown
 * "no change", conclude their performance was exactly average, and be wrong.
 *
 * <p>So `PENDING` says a rating is coming, `UNRATED` and `CANCELLED` say one never will, and
 * only `FINALIZED` shows a number. A competitor who did not enter gets told that, rather than
 * being shown a blank that looks like a bug.
 *
 * <h2>No predicted change while a contest runs</h2>
 * There is nothing to fetch and nothing to show. A number labelled "unofficial" is still a
 * number people will quote at each other, and it would be wrong as often as the standings
 * moved.
 */
export function ContestRatingPanel({ contestId, rated }: Props) {
  const [result, setResult] = useState<ContestRatingResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (signal: AbortSignal) => {
      setLoading(true);
      setError(null);
      try {
        setResult(await getContestRating(contestId, signal));
      } catch (caught) {
        if (signal.aborted) return;
        setError(describeApiError(caught));
      } finally {
        if (!signal.aborted) setLoading(false);
      }
    },
    [contestId],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  if (loading) {
    return (
      <section className="panel">
        <h2>Rating</h2>
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      </section>
    );
  }

  if (error || !result) {
    return (
      <section className="panel">
        <h2>Rating</h2>
        <div role="alert">
          <p className="status status--error">Could not load your rating for this contest</p>
          <p className="status-detail">{error ?? 'No result was returned.'}</p>
          <button
            type="button"
            className="button button--quiet"
            onClick={() => void load(new AbortController().signal)}
          >
            Try again
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <h2>Rating</h2>

      {result.status === 'UNRATED' && (
        <p className="status status--muted">
          This contest is unrated. Nothing here counts towards a rating, and nothing will
          &mdash; whether a contest is rated is fixed before it starts.
        </p>
      )}

      {result.status === 'CANCELLED' && (
        <p className="status status--muted">
          This contest was cancelled, so it produced no rating and never will. The standings
          remain readable as a record of what happened.
        </p>
      )}

      {result.status === 'PENDING' && (
        <p className="status status--pending">
          This contest is rated and has ended. Rating changes have not been computed yet
          &mdash; they usually appear within a minute of the finish. This is not a change of
          zero; there is no result yet.
        </p>
      )}

      {result.status === 'FINALIZED' && result.ratingChange === null && (
        <p className="status status--muted">
          Ratings for this contest were finalised {formatLocal(result.finalizedAt ?? '')}. You
          did not compete in it, so there is no change for you.{' '}
          <Link to="/rankings">See the rankings</Link>.
        </p>
      )}

      {result.status === 'FINALIZED' && result.ratingChange !== null && (
        <>
          <dl className="detail-grid">
            <div>
              <dt>Change</dt>
              <dd>
                <RatingChangeBadge change={result.ratingChange} />
              </dd>
            </div>
            <div>
              <dt>Rating</dt>
              <dd>
                <span className="muted">{result.ratingBefore} &rarr;</span> {result.ratingAfter}
              </dd>
            </div>
            <div>
              <dt>Rank</dt>
              <dd>
                {result.rank} <span className="muted">of {result.participantCount}</span>
              </dd>
            </div>
            <div>
              <dt>Score</dt>
              <dd>
                {result.score} <span className="muted">({result.penalty} penalty)</span>
              </dd>
            </div>
          </dl>
          {result.ratingChange === 0 && (
            <p className="field-hint">
              A change of zero is a real result: you finished exactly where your rating
              predicted you would.
            </p>
          )}
        </>
      )}

      {rated && result.status !== 'FINALIZED' && (
        <p className="field-hint">
          Rating changes are computed from the final standings once, after the contest ends.
          Nothing you or anyone else sends can influence them.
        </p>
      )}
    </section>
  );
}
