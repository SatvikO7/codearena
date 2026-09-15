import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  cancelContest,
  createContest,
  deleteContest,
  listContestsForAdmin,
} from '../../services/contestService';
import { formatLocal } from '../../services/contestClock';
import { describeApiError } from '../../services/apiClient';
import { ContestStatusPill } from '../../components/ContestStatusPill';
import type { ContestSummary } from '../../types/contest';
import type { PageResponse } from '../../types/problem';

/**
 * Turns a `datetime-local` value into an instant.
 *
 * <p>The input gives a wall-clock string with no zone — `2026-03-01T09:00` — which the
 * browser interprets in the administrator's own timezone. `toISOString` then converts it to
 * UTC, which is what the API expects and what the database stores. An administrator in IST
 * typing 09:00 schedules 03:30Z, which is exactly what they meant.
 */
function toInstant(localValue: string): string {
  return new Date(localValue).toISOString();
}

/** Sensible defaults: a contest starting tomorrow and running three hours. */
function defaultSchedule() {
  const start = new Date(Date.now() + 24 * 60 * 60 * 1000);
  start.setMinutes(0, 0, 0);
  const end = new Date(start.getTime() + 3 * 60 * 60 * 1000);
  // Trimmed to `YYYY-MM-DDTHH:mm`, the format datetime-local wants, in local time.
  const asLocalInput = (date: Date) =>
    new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  return { startAt: asLocalInput(start), endAt: asLocalInput(end) };
}

export function AdminContestsPage() {
  const [data, setData] = useState<PageResponse<ContestSummary> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const schedule = defaultSchedule();
  const [title, setTitle] = useState('');
  const [slug, setSlug] = useState('');
  const [description, setDescription] = useState('');
  const [startAt, setStartAt] = useState(schedule.startAt);
  const [endAt, setEndAt] = useState(schedule.endAt);
  // Unrated by default, matching the server. A contest accidentally created unrated is
  // fixed with an edit; one accidentally created rated has already put everybody's rating
  // at stake by the time anyone notices, and cannot be changed once it starts.
  const [rated, setRated] = useState(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      const fetched = await listContestsForAdmin({ size: 50 }, signal);
      if (signal?.aborted) return;
      setData(fetched);
      setError(null);
    } catch (caught) {
      if (signal?.aborted) return;
      setError(describeApiError(caught));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    // oxlint-disable-next-line set-state-in-effect
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setFormError(null);
    try {
      await createContest({
        title,
        slug,
        description,
        startAt: toInstant(startAt),
        endAt: toInstant(endAt),
        rated,
      });
      setTitle('');
      setSlug('');
      setDescription('');
      setRated(false);
      await load();
    } catch (caught) {
      setFormError(describeApiError(caught));
    } finally {
      setBusy(false);
    }
  }

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

  return (
    <div className="page">
      <section className="hero">
        <h1>Contests</h1>
        <p className="lede">Create a contest, add problems, publish it.</p>
      </section>

      <section className="panel">
        <h2>New contest</h2>
        <form onSubmit={create}>
          <div className="filter-row">
            <div className="filter-field filter-field--grow">
              <label htmlFor="contest-title">Title</label>
              <input
                id="contest-title"
                value={title}
                required
                maxLength={200}
                onChange={(event) => setTitle(event.target.value)}
              />
            </div>
            <div className="filter-field">
              <label htmlFor="contest-slug">Slug</label>
              <input
                id="contest-slug"
                value={slug}
                required
                pattern="[a-z0-9]+(-[a-z0-9]+)*"
                title="Lowercase words separated by single hyphens"
                onChange={(event) => setSlug(event.target.value)}
              />
            </div>
          </div>

          <div className="filter-row">
            <div className="filter-field">
              <label htmlFor="contest-start">Starts</label>
              <input
                id="contest-start"
                type="datetime-local"
                value={startAt}
                required
                onChange={(event) => setStartAt(event.target.value)}
              />
            </div>
            <div className="filter-field">
              <label htmlFor="contest-end">Ends</label>
              <input
                id="contest-end"
                type="datetime-local"
                value={endAt}
                required
                onChange={(event) => setEndAt(event.target.value)}
              />
            </div>
          </div>
          {/* Said explicitly: the fields above are in the administrator's own timezone, and
              a contest scheduled in the wrong zone is a contest nobody turns up to. */}
          <p className="field-hint">
            Times are in your local timezone and are stored in UTC.
          </p>

          <div className="filter-field filter-field--grow">
            <label htmlFor="contest-description">Description</label>
            <textarea
              id="contest-description"
              value={description}
              rows={3}
              onChange={(event) => setDescription(event.target.value)}
            />
          </div>

          <div className="filter-field">
            <label htmlFor="contest-rated" className="checkbox-label">
              <input
                id="contest-rated"
                type="checkbox"
                checked={rated}
                onChange={(event) => setRated(event.target.checked)}
              />
              Rated contest
            </label>
            <p className="field-hint">
              A rated contest changes every competitor&rsquo;s rating when it finishes. This
              can still be changed while the contest is a draft or upcoming, and is{' '}
              <strong>fixed the moment it starts</strong>.
            </p>
          </div>

          <div className="form-actions">
            <button type="submit" className="button" disabled={busy}>
              Create draft
            </button>
          </div>
          {formError && (
            <p className="form-error" role="alert">
              {formError}
            </p>
          )}
        </form>
      </section>

      {error && (
        <div className="panel" role="alert">
          <p className="status status--error">{error}</p>
        </div>
      )}

      {loading && (
        <p className="status status--pending" role="status">
          Loading&hellip;
        </p>
      )}

      {!loading && data && (
        <section className="panel table-panel">
          <table className="data-table">
            <caption className="visually-hidden">All contests</caption>
            <thead>
              <tr>
                <th scope="col">Contest</th>
                <th scope="col">Status</th>
                <th scope="col">Starts</th>
                <th scope="col">Problems</th>
                <th scope="col">Entrants</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((contest) => (
                <tr key={contest.id}>
                  <td>
                    <Link to={`/admin/contests/${contest.id}`}>{contest.title}</Link>
                    <span className="row-sub">{contest.slug}</span>
                  </td>
                  <td>
                    <ContestStatusPill status={contest.status} />
                  </td>
                  <td>{formatLocal(contest.startAt)}</td>
                  <td>{contest.problemCount}</td>
                  <td>{contest.participantCount}</td>
                  <td className="row-actions">
                    {/* Cancel is offered wherever the server would allow it; an ended
                        contest cannot be cancelled, and neither can a cancelled one. */}
                    {(contest.status === 'UPCOMING' || contest.status === 'LIVE') && (
                      <button
                        type="button"
                        className="button button--quiet"
                        disabled={busy}
                        onClick={() => void act(() => cancelContest(contest.id))}
                      >
                        Cancel
                      </button>
                    )}
                    {contest.status === 'DRAFT' && contest.participantCount === 0 && (
                      <button
                        type="button"
                        className="button button--quiet"
                        disabled={busy}
                        onClick={() => void act(() => deleteContest(contest.id))}
                      >
                        Delete
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}
