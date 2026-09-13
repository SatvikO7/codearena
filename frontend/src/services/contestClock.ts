import type { ContestDetail, ContestStatus } from '../types/contest';

/**
 * The countdown, as pure functions.
 *
 * <h2>Why the browser's clock is not trusted</h2>
 * A countdown drawn against `Date.now()` is wrong by however far the machine's clock has
 * drifted. On a laptop resumed from sleep that is routinely minutes, and a contestant who
 * believes a timer showing four minutes left when the contest ended two minutes ago will
 * submit, be refused, and reasonably conclude the site is broken.
 *
 * <p>So the server sends its own instant alongside the schedule, and the offset between the
 * two clocks is computed once when the page loads. Everything after that counts down against
 * a corrected clock.
 *
 * <h2>The countdown is never the authority</h2>
 * It disables a button a moment early, which is a courtesy. Whether a submission is inside
 * the window is decided by the server on the request itself, against its own clock, every
 * time. If these two ever disagree, the server is right and the UI is stale — which is why
 * {@link hasProbablyChanged} exists to prompt a re-fetch rather than a local state change.
 */

/** How far the browser's clock is behind the server's, in milliseconds. */
export function clockOffset(detail: Pick<ContestDetail, 'serverTime'>, browserNow: number): number {
  const serverNow = Date.parse(detail.serverTime);
  if (Number.isNaN(serverNow)) {
    // An unparseable timestamp means no correction rather than a wild one.
    return 0;
  }
  return serverNow - browserNow;
}

/** The server's current instant, as best the browser can tell. */
export function correctedNow(offsetMs: number, browserNow: number): number {
  return browserNow + offsetMs;
}

export interface Countdown {
  /** Milliseconds until the next transition, floored at zero. */
  remainingMs: number;
  /** What the timer is counting towards. */
  target: 'start' | 'end' | null;
  /** The status implied by the corrected clock. */
  status: ContestStatus;
}

/**
 * What the timer should show.
 *
 * <p>Derives the status with exactly the server's rule — `[startAt, endAt)`, start inclusive
 * and end exclusive — so the UI and the API agree about which side of a boundary a moment
 * falls on. Duplicating the rule is a real risk, and the alternative (asking the server every
 * second) is worse.
 */
export function countdown(
  contest: Pick<ContestDetail, 'startAt' | 'endAt' | 'status'>,
  nowMs: number,
): Countdown {
  // A cancelled or draft contest is not counting towards anything, whatever its schedule.
  if (contest.status === 'CANCELLED' || contest.status === 'DRAFT') {
    return { remainingMs: 0, target: null, status: contest.status };
  }

  const start = Date.parse(contest.startAt);
  const end = Date.parse(contest.endAt);
  if (Number.isNaN(start) || Number.isNaN(end)) {
    // Fall back to whatever the server said rather than inventing a state.
    return { remainingMs: 0, target: null, status: contest.status };
  }

  if (nowMs < start) {
    return { remainingMs: start - nowMs, target: 'start', status: 'UPCOMING' };
  }
  if (nowMs < end) {
    return { remainingMs: end - nowMs, target: 'end', status: 'LIVE' };
  }
  return { remainingMs: 0, target: null, status: 'ENDED' };
}

/**
 * Whether the locally derived status has diverged from what the server last said.
 *
 * <p>The trigger for re-fetching rather than for changing anything locally. It fires when a
 * contest starts or ends while the page is open, and also after a laptop wakes from sleep
 * with a very stale page — in both cases the right response is to ask the server what is
 * true, not to decide locally.
 */
export function hasProbablyChanged(contest: Pick<ContestDetail, 'status'>, derived: ContestStatus): boolean {
  return contest.status !== derived;
}

/**
 * Formats a duration as `H:MM:SS`, or `D days H:MM:SS` beyond a day.
 *
 * <p>Always shows seconds: a contest timer that ticks in minutes leaves a contestant unable
 * to tell whether they have fifty seconds or ten.
 */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  const clock = `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  if (days > 0) {
    return `${days} ${days === 1 ? 'day' : 'days'} ${clock}`;
  }
  return clock;
}

/**
 * The reader's local time, labelled with their zone.
 *
 * <p>Contest times are instants; where in the world somebody reads them is a rendering
 * question. Naming the zone matters more than it looks — a contestant who misreads a start
 * time by five and a half hours misses the contest.
 */
export function formatLocal(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return iso;
  }
  return parsed.toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZoneName: 'short',
  });
}
