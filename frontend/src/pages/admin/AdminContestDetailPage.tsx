import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  addContestProblem,
  getContestForAdmin,
  listParticipants,
  publishContest,
  removeContestProblem,
  updateContestProblem,
} from '../../services/contestService';
import { listProblems } from '../../services/problemService';
import { formatLocal } from '../../services/contestClock';
import { describeApiError } from '../../services/apiClient';
import { ContestStatusPill } from '../../components/ContestStatusPill';
import type { ContestDetail, ContestParticipant } from '../../types/contest';
import type { ProblemSummary } from '../../types/problem';

export function AdminContestDetailPage() {
  const { contestId } = useParams<{ contestId: string }>();
  if (!contestId) {
    return null;
  }
  return <AdminContestView key={contestId} contestId={contestId} />;
}

/**
 * Managing one contest: its problems, its points, and who has entered.
 *
 * <p>The page reflects the freeze rather than enforcing it. Every editing control disappears
 * once the contest has started, but that is a courtesy to the administrator — the server
 * refuses the change regardless, which is what actually makes a running contest immutable.
 */
function AdminContestView({ contestId }: { contestId: string }) {
  const [contest, setContest] = useState<ContestDetail | null>(null);
  const [participants, setParticipants] = useState<ContestParticipant[]>([]);
  const [available, setAvailable] = useState<ProblemSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [chosenProblem, setChosenProblem] = useState('');
  const [points, setPoints] = useState(100);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const fetched = await getContestForAdmin(contestId, signal);
        if (signal?.aborted) return;
        setContest(fetched);
        setError(null);
      } catch (caught) {
        if (signal?.aborted) return;
        setError(describeApiError(caught));
      }
    },
    [contestId],
  );

  useEffect(() => {
    const controller = new AbortController();
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    const controller = new AbortController();
    listParticipants(contestId, controller.signal)
      // oxlint-disable-next-line set-state-in-effect
      .then(setParticipants)
      .catch(() => setParticipants([]));
    return () => controller.abort();
  }, [contestId]);

  useEffect(() => {
    const controller = new AbortController();
    // Only published problems may be added, so only those are offered.
    listProblems({ status: 'PUBLISHED', size: 100 }, controller.signal)
      // oxlint-disable-next-line set-state-in-effect
      .then((result) => setAvailable(result.items))
      .catch(() => setAvailable([]));
    return () => controller.abort();
  }, []);

  async function act(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
    } catch (caught) {
      setError(describeApiError(caught));
    } finally {
      setBusy(false);
    }
  }

  if (error && !contest) {
    return (
      <div className="page">
        <div className="panel" role="alert">
          <p className="status status--error">{error}</p>
        </div>
      </div>
    );
  }

  if (!contest) {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      </div>
    );
  }

  const editable = contest.status === 'DRAFT' || contest.status === 'UPCOMING';
  const alreadyAdded = new Set(contest.problems.map((entry) => entry.problemId));

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
        </p>
        <p className="form-aside">
          <Link to="/admin/contests">Back to contests</Link>
          {' · '}
          <Link to={`/contests/${contest.id}`}>View as a contestant</Link>
        </p>
      </section>

      {error && (
        <div className="panel" role="alert">
          <p className="status status--error">{error}</p>
        </div>
      )}

      {contest.status === 'DRAFT' && (
        <section className="panel">
          <h2>Publish</h2>
          <p className="field-hint">
            Publishing makes the contest visible and opens registration. It needs at least one
            problem. The schedule and problems stay editable until it starts.
          </p>
          <button
            type="button"
            className="button"
            disabled={busy || contest.problems.length === 0}
            onClick={() => void act(() => publishContest(contest.id))}
          >
            Publish
          </button>
        </section>
      )}

      {!editable && (
        <section className="panel">
          <p className="status">
            This contest has started. Its problems, order and points are fixed.
          </p>
        </section>
      )}

      <section className="panel table-panel">
        <h2>Problems</h2>
        {contest.problems.length === 0 && <p className="field-hint">No problems yet.</p>}
        {contest.problems.length > 0 && (
          <table className="data-table">
            <caption className="visually-hidden">Contest problems</caption>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Problem</th>
                <th scope="col">Points</th>
                {editable && <th scope="col">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {contest.problems.map((entry, index) => (
                <tr key={entry.problemId}>
                  <td>{entry.label}</td>
                  <td>{entry.title}</td>
                  <td>
                    {editable ? (
                      <input
                        type="number"
                        min={1}
                        max={10000}
                        defaultValue={entry.points}
                        aria-label={`Points for ${entry.title}`}
                        onBlur={(event) => {
                          const next = Number(event.target.value);
                          if (next !== entry.points) {
                            void act(() =>
                              updateContestProblem(contest.id, entry.problemId, { points: next }),
                            );
                          }
                        }}
                      />
                    ) : (
                      entry.points
                    )}
                  </td>
                  {editable && (
                    <td className="row-actions">
                      <button
                        type="button"
                        className="button button--quiet"
                        disabled={busy || index === 0}
                        onClick={() =>
                          void act(() =>
                            updateContestProblem(contest.id, entry.problemId, {
                              displayOrder: index - 1,
                            }),
                          )
                        }
                      >
                        Up
                      </button>
                      <button
                        type="button"
                        className="button button--quiet"
                        disabled={busy || index === contest.problems.length - 1}
                        onClick={() =>
                          void act(() =>
                            updateContestProblem(contest.id, entry.problemId, {
                              displayOrder: index + 1,
                            }),
                          )
                        }
                      >
                        Down
                      </button>
                      <button
                        type="button"
                        className="button button--quiet"
                        disabled={busy}
                        onClick={() =>
                          void act(() => removeContestProblem(contest.id, entry.problemId))
                        }
                      >
                        Remove
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {editable && (
          <div className="filter-row">
            <div className="filter-field filter-field--grow">
              <label htmlFor="add-problem">Add a published problem</label>
              <select
                id="add-problem"
                value={chosenProblem}
                onChange={(event) => setChosenProblem(event.target.value)}
              >
                <option value="">Choose…</option>
                {available
                  .filter((problem) => !alreadyAdded.has(problem.id))
                  .map((problem) => (
                    <option key={problem.id} value={problem.id}>
                      {problem.title}
                    </option>
                  ))}
              </select>
            </div>
            <div className="filter-field">
              <label htmlFor="add-points">Points</label>
              <input
                id="add-points"
                type="number"
                min={1}
                max={10000}
                value={points}
                onChange={(event) => setPoints(Number(event.target.value))}
              />
            </div>
            <button
              type="button"
              className="button"
              disabled={busy || !chosenProblem}
              onClick={() =>
                void act(async () => {
                  await addContestProblem(contest.id, chosenProblem, points);
                  setChosenProblem('');
                })
              }
            >
              Add
            </button>
          </div>
        )}
      </section>

      <section className="panel table-panel">
        <h2>Participants ({participants.length})</h2>
        {participants.length === 0 && <p className="field-hint">Nobody has registered yet.</p>}
        {participants.length > 0 && (
          <table className="data-table">
            <caption className="visually-hidden">Participants</caption>
            <thead>
              <tr>
                <th scope="col">User</th>
                <th scope="col">Registered</th>
              </tr>
            </thead>
            <tbody>
              {participants.map((participant) => (
                <tr key={participant.userId}>
                  <td>{participant.username}</td>
                  <td>{formatLocal(participant.registeredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
