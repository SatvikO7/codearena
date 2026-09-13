import { describe, expect, it } from 'vitest';
import {
  clockOffset,
  correctedNow,
  countdown,
  formatDuration,
  hasProbablyChanged,
} from './contestClock';
import type { ContestStatus } from '../types/contest';

/**
 * The countdown rules.
 *
 * <p>These matter because a contestant trusts the timer. A countdown that is minutes wrong
 * — which is ordinary on a laptop resumed from sleep — tells somebody they have time left
 * when the contest is over, and they find out by being refused.
 */
describe('contest clock', () => {
  const START = '2026-03-01T10:00:00Z';
  const END = '2026-03-01T13:00:00Z';
  const startMs = Date.parse(START);
  const endMs = Date.parse(END);

  const contest = { startAt: START, endAt: END, status: 'UPCOMING' as ContestStatus };
  const live = { ...contest, status: 'LIVE' as ContestStatus };

  // --------------------------------------------------------------- clock correction

  /**
   * The correction that makes the rest honest: if the browser is five minutes fast, every
   * subsequent calculation has to be pulled back by five minutes.
   */
  it('measures how far the browser clock is from the server', () => {
    const browserNow = Date.parse('2026-03-01T09:05:00Z');
    const offset = clockOffset({ serverTime: '2026-03-01T09:00:00Z' }, browserNow);

    expect(offset).toBe(-5 * 60 * 1000);
    expect(correctedNow(offset, browserNow)).toBe(Date.parse('2026-03-01T09:00:00Z'));
  });

  it('applies no correction when the server timestamp is unreadable', () => {
    expect(clockOffset({ serverTime: 'not-a-date' }, 1000)).toBe(0);
  });

  // --------------------------------------------------------------- the boundaries

  it('counts towards the start before a contest begins', () => {
    const result = countdown(contest, startMs - 90 * 1000);

    expect(result.status).toBe('UPCOMING');
    expect(result.target).toBe('start');
    expect(result.remainingMs).toBe(90 * 1000);
  });

  /** The same half-open window the server uses: startAt is inside the contest. */
  it('is live at exactly the start', () => {
    const result = countdown(contest, startMs);

    expect(result.status).toBe('LIVE');
    expect(result.target).toBe('end');
  });

  it('counts towards the end while live', () => {
    const result = countdown(live, endMs - 60 * 1000);

    expect(result.status).toBe('LIVE');
    expect(result.remainingMs).toBe(60 * 1000);
  });

  /** And endAt belongs to ENDED, matching the server exactly. */
  it('is ended at exactly the end', () => {
    const result = countdown(live, endMs);

    expect(result.status).toBe('ENDED');
    expect(result.target).toBeNull();
    expect(result.remainingMs).toBe(0);
  });

  it('never reports a negative remaining time', () => {
    expect(countdown(live, endMs + 10 * 60 * 1000).remainingMs).toBe(0);
  });

  // --------------------------------------------------------------- terminal states

  /**
   * A cancelled contest is not counting towards anything, whatever its schedule says. The
   * schedule is not consulted, because the lifecycle already settled it.
   */
  it('does not count down a cancelled contest', () => {
    const cancelled = { ...contest, status: 'CANCELLED' as ContestStatus };
    const result = countdown(cancelled, startMs - 1000);

    expect(result.status).toBe('CANCELLED');
    expect(result.target).toBeNull();
  });

  it('does not count down a draft', () => {
    const draft = { ...contest, status: 'DRAFT' as ContestStatus };

    expect(countdown(draft, startMs).status).toBe('DRAFT');
  });

  /** A malformed schedule falls back to what the server said rather than inventing a state. */
  it('keeps the server status when the schedule cannot be parsed', () => {
    const broken = { startAt: 'nonsense', endAt: END, status: 'LIVE' as ContestStatus };

    expect(countdown(broken, startMs).status).toBe('LIVE');
  });

  // --------------------------------------------------------------- staleness

  /**
   * The trigger for re-fetching. The page never decides locally that a contest has started
   * or ended — it decides that it should go and ask.
   */
  it('notices when the local derivation has overtaken the server', () => {
    expect(hasProbablyChanged({ status: 'UPCOMING' }, 'LIVE')).toBe(true);
    expect(hasProbablyChanged({ status: 'LIVE' }, 'ENDED')).toBe(true);
    expect(hasProbablyChanged({ status: 'LIVE' }, 'LIVE')).toBe(false);
  });

  // --------------------------------------------------------------- formatting

  /**
   * Always down to the second: a timer ticking in minutes leaves a contestant unable to tell
   * whether they have fifty seconds or ten.
   */
  it('formats a duration as hours, minutes and seconds', () => {
    expect(formatDuration(0)).toBe('0:00:00');
    expect(formatDuration(9 * 1000)).toBe('0:00:09');
    expect(formatDuration((2 * 3600 + 5 * 60 + 7) * 1000)).toBe('2:05:07');
  });

  it('names days when there is more than one', () => {
    expect(formatDuration(26 * 3600 * 1000)).toBe('1 day 2:00:00');
    expect(formatDuration((2 * 86400 + 3600) * 1000)).toBe('2 days 1:00:00');
  });

  it('never formats a negative duration', () => {
    expect(formatDuration(-5000)).toBe('0:00:00');
  });

  // --------------------------------------------------------------- timezones

  /**
   * The same instant written three ways is the same instant. A contest scheduled by an
   * administrator in IST and read by a contestant in UTC is one contest.
   */
  it('treats equivalent instants in different zones identically', () => {
    const utc = { startAt: '2026-03-01T10:00:00Z', endAt: END, status: 'UPCOMING' as ContestStatus };
    const ist = {
      startAt: '2026-03-01T15:30:00+05:30',
      endAt: END,
      status: 'UPCOMING' as ContestStatus,
    };

    const probe = startMs - 1000;
    expect(countdown(ist, probe)).toEqual(countdown(utc, probe));
    expect(countdown(ist, startMs).status).toBe('LIVE');
  });
});
