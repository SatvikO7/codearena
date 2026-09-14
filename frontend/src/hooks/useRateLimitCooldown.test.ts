import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { useRateLimitCooldown } from './useRateLimitCooldown';

function refusal(status: number, retryAfter?: string) {
  const error = new AxiosError('Request failed');
  error.response = {
    status,
    statusText: '',
    headers: new AxiosHeaders(retryAfter === undefined ? {} : { 'retry-after': retryAfter }),
    config: { headers: new AxiosHeaders() },
    data: undefined,
  } as never;
  return error;
}

/**
 * The cooldown that stops a refused client from making its own situation worse.
 *
 * <p>The property that matters most is the one asserted last: when the countdown reaches
 * zero, nothing is resent. A hook that retried automatically would turn every throttled
 * client into a synchronised wave of requests at the same instant — the load the limit
 * exists to shed, rebuilt on the client.
 */
describe('useRateLimitCooldown', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does nothing for a failure that is not a rate limit', () => {
    const { result } = renderHook(() => useRateLimitCooldown());

    act(() => {
      expect(result.current.startIfRateLimited(refusal(401))).toBe(false);
    });

    expect(result.current.cooling).toBe(false);
    expect(result.current.remainingSeconds).toBe(0);
  });

  it('holds the action closed for exactly as long as the server asked', () => {
    const { result } = renderHook(() => useRateLimitCooldown());

    act(() => {
      expect(result.current.startIfRateLimited(refusal(429, '5'))).toBe(true);
    });

    expect(result.current.cooling).toBe(true);
    expect(result.current.remainingSeconds).toBe(5);
  });

  it('counts down as time passes', () => {
    const { result } = renderHook(() => useRateLimitCooldown());

    act(() => {
      result.current.startIfRateLimited(refusal(429, '5'));
    });
    act(() => {
      vi.advanceTimersByTime(3000);
    });

    expect(result.current.remainingSeconds).toBe(2);
    expect(result.current.cooling).toBe(true);
  });

  it('opens up again once the wait has elapsed', () => {
    const { result } = renderHook(() => useRateLimitCooldown());

    act(() => {
      result.current.startIfRateLimited(refusal(429, '3'));
    });
    act(() => {
      vi.advanceTimersByTime(3500);
    });

    expect(result.current.remainingSeconds).toBe(0);
    expect(result.current.cooling).toBe(false);
  });

  /** A 429 with no Retry-After must still produce a wait, not an open button. */
  it('applies a conservative wait when the server gave no number', () => {
    const { result } = renderHook(() => useRateLimitCooldown());

    act(() => {
      result.current.startIfRateLimited(refusal(429));
    });

    expect(result.current.remainingSeconds).toBeGreaterThan(0);
  });

  /**
   * The important one. The hook reports that the wait is over; it does not act on it.
   * Anything that resends by itself here would be a retry storm waiting for a bad day.
   */
  it('never retries anything by itself', () => {
    const { result } = renderHook(() => useRateLimitCooldown());

    act(() => {
      result.current.startIfRateLimited(refusal(429, '2'));
    });
    act(() => {
      vi.advanceTimersByTime(10_000);
    });

    expect(result.current.cooling).toBe(false);
    expect(Object.keys(result.current)).toEqual(
      expect.arrayContaining(['remainingSeconds', 'cooling', 'startIfRateLimited']),
    );
    expect(Object.keys(result.current)).not.toContain('retry');
  });
});
