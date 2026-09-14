import { describe, expect, it } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import {
  describeApiError,
  describeWait,
  isRateLimited,
  retryAfterSeconds,
} from './apiClient';

/**
 * How the client reads a 429.
 *
 * <p>The behaviour under test is small but load-bearing: the server's wait is honoured
 * rather than guessed, and a rate limit is told apart from every other failure so the UI can
 * stop rather than let somebody keep pressing a button that is certain to be refused.
 */
function refusal(status: number, headers: Record<string, string> = {}, body?: unknown) {
  const error = new AxiosError('Request failed');
  error.response = {
    status,
    statusText: '',
    headers: new AxiosHeaders(headers),
    config: { headers: new AxiosHeaders() },
    data: body,
  } as never;
  return error;
}

describe('rate limit handling', () => {
  it('recognises a rate-limited response and nothing else', () => {
    expect(isRateLimited(refusal(429))).toBe(true);
    expect(isRateLimited(refusal(401))).toBe(false);
    expect(isRateLimited(refusal(409))).toBe(false);
    expect(isRateLimited(new Error('network'))).toBe(false);
  });

  /** The server computes the wait from the bucket; the client must not invent its own. */
  it('takes the wait from the server', () => {
    expect(retryAfterSeconds(refusal(429, { 'retry-after': '17' }))).toBe(17);
  });

  it('reports no wait when the server did not give one', () => {
    expect(retryAfterSeconds(refusal(429))).toBeUndefined();
    expect(retryAfterSeconds(refusal(429, { 'retry-after': '' }))).toBeUndefined();
  });

  /**
   * A header that is not a number must not become NaN seconds, which would render as
   * "NaN seconds" and, worse, arithmetic that never elapses.
   */
  it('ignores a Retry-After that is not a number', () => {
    expect(retryAfterSeconds(refusal(429, { 'retry-after': 'Wed, 21 Oct 2026 07:28:00 GMT' })))
      .toBeUndefined();
    expect(retryAfterSeconds(refusal(429, { 'retry-after': '-5' }))).toBeUndefined();
  });

  it('rounds a fractional wait up, so a client never retries a moment too early', () => {
    expect(retryAfterSeconds(refusal(429, { 'retry-after': '2.4' }))).toBe(3);
  });

  it('describes a wait in units a person can act on', () => {
    expect(describeWait(1)).toBe('1 second');
    expect(describeWait(17)).toBe('17 seconds');
    expect(describeWait(60)).toBe('1 minute');
    expect(describeWait(90)).toBe('2 minutes');
  });

  /**
   * The server's own sentence is deliberately identical for every policy, so that a 429
   * cannot be read as evidence that an account exists. The concrete wait is the part worth
   * adding.
   */
  it('tells the user how long to wait rather than quoting a status code', () => {
    const message = describeApiError(
      refusal(429, { 'retry-after': '30' }, { message: 'Too many requests.' }),
    );

    expect(message).toContain('30 seconds');
    expect(message).not.toContain('429');
  });

  it('falls back to a plain message when the server gave no wait', () => {
    const message = describeApiError(refusal(429));

    expect(message).toContain('Too many requests');
    expect(message).not.toContain('undefined');
    expect(message).not.toContain('NaN');
  });

  /** Nothing about the mechanism may reach the user. */
  it('never mentions the infrastructure behind the limit', () => {
    const message = describeApiError(refusal(429, { 'retry-after': '5' }));

    expect(message.toLowerCase()).not.toContain('redis');
    expect(message.toLowerCase()).not.toContain('bucket');
    expect(message.toLowerCase()).not.toContain('token');
  });

  /** Other failures keep the behaviour they had before this phase. */
  it('leaves every other failure to the existing handling', () => {
    expect(describeApiError(refusal(409, {}, { message: 'Username already in use' })))
      .toBe('Username already in use');
  });
});
