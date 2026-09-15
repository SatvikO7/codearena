import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getContest, registerForContest } from '../services/contestService';
import { formatDuration, formatLocal } from '../services/contestClock';
import { useContestClock } from '../hooks/useContestClock';
import { apiErrorCode, describeApiError } from '../services/apiClient';
import { ContestStatusPill } from '../components/ContestStatusPill';
import { ContestStandings } from '../components/ContestStandings';
import { ContestRatingPanel } from '../components/ContestRatingPanel';
import type { ContestDetail } from '../types/contest';

export function ContestDetailPage() {
  const { contestId } = useParams<{ contestId: string }>();
  if (!contestId) {
    return null;
  }
  return <ContestView key={contestId} contestId={contestId} />;
}

/**
 * One contest: schedule, countdown, registration, problems and standings.
 *
 * <h2>The server decides; this page asks</h2>
 * The countdown runs locally so the page does not poll every second, but it never changes
 * what the page believes. When the timer crosses a boundary — or the tab wakes from sleep —
 * the page re-fetches and renders whatever the server says. That is why a contest can start
 * or end while somebody is looking at it without the page inventing a state.
 */
function ContestView({ contestId }: { contestId: string }) {
  const [contest, setContest] = useState<ContestDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [registerError, setRegisterError] = useState<string | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const fetched = await getContest(contestId, signal);
        if (signal?.aborted) return;
        setContest(fetched);
        setError(null);
      } catch (caught) {
        if (signal?.aborted) return;
        if (apiErrorCode(caught) === 'CONTEST_NOT_FOUND') {
          setNotFound(true);
        } else {
          setError(describeApiError(caught));
        }
      }
    },
    [contestId],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Synchronising with the server; the state updates happen after the request resolves.
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const refresh = useCallback(() => void load(), [load]);
  const clock = useContestClock(contest, refresh);

  async function register() {
    if (registering) {
      // Guards the obvious double-click. The server is idempotent regardless, which is what
      // actually prevents a duplicate — this only avoids a pointless second request.
      return;
    }
    setRegistering(true);
    setRegisterError(null);
    try {
      await registerForContest(contestId);
      await load();
    } catch (caught) {
      setRegisterError(describeApiError(caught));
    } finally {
      setRegistering(false);
    }
  }

  if (notFound) {
    return (
      <div className="page">
        <section className="panel empty-state">
          <h1>Contest not found</h1>
          <p>It may not exist, or it may not have been published yet.</p>
          <Link className="button" to="/contests">
            Back to contests
          </Link>
        </section>
      </div>
    );
  }

  if (error && !contest) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">Could not load the contest</p>
          <p className="status-detail">{error}</p>
        </div>
      </div>
    );
  }

  if (!contest) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading contest&hellip;
        </p>
      </div>
    );
  }

  return (
    <div className="page">
      <section className="hero">
        <div className="title-row">
          <h1>{contest.title}</h1>
          <ContestStatusPill status={contest.status} />
        </div>
        <p className="meta-row">
          <span>Starts {formatLocal(contest.startAt)}</span>
          <span>Ends {formatLocal(contest.endAt)}</span>
          <span>{contest.participantCount} registered</span>
          {/* Said plainly and up front. Whether a contest counts is the thing somebody
              decides on before entering, and it cannot change once it starts. */}
          <span className={contest.rated ? 'badge badge--rated' : 'badge badge--unrated'}>
            {contest.rated ? 'Rated' : 'Unrated'}
          </span>
        </p>
      </section>

      {clock && clock.target && (
        <section className="panel" aria-live="polite">
          <h2>{clock.target === 'start' ? 'Starts in' : 'Time remaining'}</h2>
          <p className="countdown">{formatDuration(clock.remainingMs)}</p>
          <p className="field-hint">
            Counted against the server&rsquo;s clock. The deadline is enforced by the server on
            every submission, whatever this timer shows.
          </p>
        </section>
      )}

      {contest.description && (
        <section className="panel">
          <h2>About</h2>
          <p className="statement">{contest.description}</p>
        </section>
      )}

      <section className="panel">
        <h2>Your entry</h2>
        {contest.status === 'UPCOMING' && !contest.registered && (
          <>
            <button type="button" className="button" onClick={register} disabled={registering}>
              {registering ? 'Registering…' : 'Register'}
            </button>
            <p className="field-hint">Registration closes when the contest starts.</p>
          </>
        )}
        {contest.status === 'UPCOMING' && contest.registered && (
          <p className="status status--ok">
            Registered. The problems appear when the contest starts.
          </p>
        )}
        {contest.status === 'LIVE' && contest.registered && (
          <p className="status status--ok">You are in. Pick a problem below.</p>
        )}
        {contest.status === 'LIVE' && !contest.registered && (
          <p className="status status--error">
            This contest is running and registration has closed.
          </p>
        )}
        {contest.status === 'ENDED' && (
          <p className="status">This contest has ended. Final standings are below.</p>
        )}
        {contest.status === 'CANCELLED' && (
          <p className="status status--error">
            This contest was cancelled. Submissions made before it stopped are kept.
          </p>
        )}
        {registerError && (
          <p className="form-error" role="alert">
            {registerError}
          </p>
        )}
      </section>

      {contest.problems.length > 0 && (
        <section className="panel table-panel">
          <h2>Problems</h2>
          <table className="data-table">
            <caption className="visually-hidden">Contest problems</caption>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Problem</th>
                <th scope="col">Points</th>
                <th scope="col">Your status</th>
              </tr>
            </thead>
            <tbody>
              {contest.problems.map((problem) => (
                <tr key={problem.problemId}>
                  <td>{problem.label}</td>
                  <td>
                    {contest.canSubmit ? (
                      // Into the contest solve page, so the submission goes to the contest
                      // endpoint rather than becoming a practice submission.
                      <Link to={`/contests/${contest.id}/problems/${problem.problemId}`}>
                        {problem.title}
                      </Link>
                    ) : (
                      <Link to={`/problems/${problem.problemSlug}`}>{problem.title}</Link>
                    )}
                  </td>
                  <td>{problem.points}</td>
                  <td>
                    {problem.solved ? (
                      <span className="status status--ok">Solved</span>
                    ) : problem.attempts ? (
                      <span className="status">
                        {problem.attempts} {problem.attempts === 1 ? 'attempt' : 'attempts'}
                      </span>
                    ) : (
                      <span className="status">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {contest.status !== 'UPCOMING' && contest.status !== 'DRAFT' && (
        <ContestStandings contestId={contest.id} live={contest.status === 'LIVE'} />
      )}

      {/* Only once the contest is over. During a live contest there is deliberately no
          predicted change to show: a provisional rating is still a number people would
          quote, and it would be wrong as often as the standings moved. */}
      {(contest.status === 'ENDED' || contest.status === 'CANCELLED') && (
        <ContestRatingPanel contestId={contest.id} rated={contest.rated} />
      )}
    </div>
  );
}
