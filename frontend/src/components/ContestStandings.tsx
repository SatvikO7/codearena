import { useCallback, useEffect, useState } from 'react';
import { getStandings } from '../services/contestService';
import { describeApiError } from '../services/apiClient';
import type { Standings } from '../types/contest';

/**
 * How often the standings refresh while a contest is running.
 *
 * <p>Deliberately unhurried. A scoreboard that moves every second is harder to read than one
 * that moves every fifteen, and the cost of each refresh is a real aggregation over a
 * contest's submissions. It stops entirely once the contest is over, because a final
 * scoreboard does not change.
 *
 * <p>Polling rather than SSE, and that is a considered choice rather than a shortcut. The
 * existing stream infrastructure is keyed by submission id and fans out to the one person
 * watching that submission; standings are per contest and would need a different fan-out,
 * new authorisation, and a new connection cap. Fifteen-second polling of an endpoint that is
 * already fast is the smaller, more predictable thing. See ADR-034.
 */
const REFRESH_MS = 15_000;

/**
 * The scoreboard.
 *
 * <p>Always rendered from the server's response. There is no local score arithmetic here at
 * all — no accumulating, no optimistic update when a submission is accepted — because a
 * scoreboard that computes anything locally is a scoreboard that can disagree with the
 * database, and the database is the only thing that can settle a contest.
 */
export function ContestStandings({ contestId, live }: { contestId: string; live: boolean }) {
  const [standings, setStandings] = useState<Standings | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const fetched = await getStandings(contestId, signal);
        if (signal?.aborted) return;
        setStandings(fetched);
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
    if (!live) {
      // A finished contest's standings are final; refreshing them forever would be waste.
      return;
    }
    const timer = setInterval(() => void load(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [live, load]);

  if (error && !standings) {
    return (
      <section className="panel" role="alert">
        <h2>Standings</h2>
        <p className="status status--error">Could not load the standings</p>
        <p className="status-detail">{error}</p>
      </section>
    );
  }

  if (!standings) {
    return (
      <section className="panel">
        <h2>Standings</h2>
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      </section>
    );
  }

  if (standings.rows.length === 0) {
    return (
      <section className="panel empty-state">
        <h2>Standings</h2>
        <p>Nobody has scored yet.</p>
      </section>
    );
  }

  return (
    <section className="panel table-panel">
      <div className="title-row">
        <h2>Standings</h2>
        {live && <span className="field-hint">Refreshes every {REFRESH_MS / 1000} seconds</span>}
      </div>
      <table className="data-table standings">
        <caption className="visually-hidden">Contest standings</caption>
        <thead>
          <tr>
            <th scope="col">#</th>
            <th scope="col">User</th>
            <th scope="col">Solved</th>
            <th scope="col">Score</th>
            <th scope="col">Penalty</th>
            {standings.problems.map((problem) => (
              <th key={problem.problemId} scope="col" title={`${problem.title} (${problem.points})`}>
                {problem.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {standings.rows.map((row) => (
            <tr key={row.userId}>
              <td>{row.rank}</td>
              <td>{row.username}</td>
              <td>{row.solved}</td>
              <td>{row.score}</td>
              <td>{row.penalty}</td>
              {row.cells.map((cell) => (
                <td
                  key={cell.problemId}
                  className={cell.solved ? 'cell--solved' : cell.attempts > 0 ? 'cell--tried' : ''}
                >
                  {cell.solved ? (
                    <>
                      {/* A glyph as well as the colour, so the grid is readable without it. */}
                      <span aria-hidden="true">✓</span>{' '}
                      <span className="cell-minute">{cell.solvedAtMinute}</span>
                      {cell.attempts > 0 && <span className="cell-attempts">+{cell.attempts}</span>}
                      <span className="visually-hidden">
                        solved at minute {cell.solvedAtMinute}
                        {cell.attempts > 0 ? ` after ${cell.attempts} rejected attempts` : ''}
                      </span>
                    </>
                  ) : cell.attempts > 0 ? (
                    <>
                      <span aria-hidden="true">✗</span> {cell.attempts}
                      <span className="visually-hidden">
                        {cell.attempts} attempts, unsolved
                      </span>
                    </>
                  ) : (
                    <span className="visually-hidden">no attempts</span>
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="field-hint">
        Penalty is minutes from the start to each solve, plus 20 per rejected attempt before
        it. Unsolved problems cost nothing, and a judge failure is never counted.
      </p>
    </section>
  );
}
