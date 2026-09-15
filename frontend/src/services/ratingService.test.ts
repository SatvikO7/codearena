import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import {
  finalizeContest,
  getContestRating,
  getRankings,
  getRatingHistory,
  getRatingProfile,
} from './ratingService';
import { apiClient } from './apiClient';

/**
 * What the rating client actually sends.
 *
 * <p>Almost all of the value here is in what is <em>absent</em>. A rating is computed by the
 * server from standings the server derived, and the browser's whole authority is to ask for a
 * page of them or to ask that a finished contest be finalised. These tests assert that the
 * requests carry nothing more than that — because the cheapest way to introduce a
 * client-controlled rating is for somebody to add a "helpful" field to a request body and for
 * nothing to notice.
 */
describe('ratingService', () => {
  beforeEach(() => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { items: [] } } as never);
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: {} } as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('asks for a page of the ranking, with defaults', async () => {
    await getRankings({});

    const [url, config] = vi.mocked(apiClient.get).mock.calls[0];
    expect(url).toBe('/api/rankings');
    expect((config as { params: Record<string, unknown> }).params).toEqual({ page: 0, size: 50 });
  });

  it('passes through an explicit page and size', async () => {
    await getRankings({ page: 3, size: 25 });

    const [, config] = vi.mocked(apiClient.get).mock.calls[0];
    expect((config as { params: Record<string, unknown> }).params).toEqual({ page: 3, size: 25 });
  });

  /**
   * There is no sort, order or direction parameter, and there must never be one: an ordering
   * taken from a query string is how a ranking endpoint becomes an injection point. The
   * server's ordering is fixed, so the client has nothing to send.
   */
  it('sends no ordering parameter of any kind', async () => {
    await getRankings({ page: 1 });

    const [, config] = vi.mocked(apiClient.get).mock.calls[0];
    const params = (config as { params: Record<string, unknown> }).params;
    expect(Object.keys(params).sort()).toEqual(['page', 'size']);
  });

  /**
   * An identifier goes into a path segment, so it is encoded. A raw identifier would let a
   * crafted value change which endpoint is called rather than which resource is asked for.
   */
  it('encodes identifiers into the path', async () => {
    await getRatingProfile('a b/../admin');

    const [url] = vi.mocked(apiClient.get).mock.calls[0];
    expect(url).toBe('/api/users/a%20b%2F..%2Fadmin/rating');
  });

  it('encodes the identifier on the history path too', async () => {
    await getRatingHistory('../../etc', { page: 0, size: 20 });

    const [url] = vi.mocked(apiClient.get).mock.calls[0];
    expect(url).toBe('/api/users/..%2F..%2Fetc/rating/history');
  });

  /**
   * The contest rating result is the caller's own, and there is no parameter for whose. The
   * server takes the competitor from the session; a client that could name one would be a
   * client that could read somebody else's result.
   */
  it('asks for a contest result without naming a user', async () => {
    await getContestRating('c0ffee');

    const [url, config] = vi.mocked(apiClient.get).mock.calls[0];
    expect(url).toBe('/api/contests/c0ffee/rating');
    expect((config as { params?: unknown }).params).toBeUndefined();
  });

  /**
   * The most important assertion in this file. Finalisation sends **no body**: every input
   * comes from the database, and a request with nothing in it cannot be made to carry a
   * rating, a rank or a set of standings.
   */
  it('finalises a contest with no request body at all', async () => {
    await finalizeContest('contest-1');

    const call = vi.mocked(apiClient.post).mock.calls[0];
    expect(call[0]).toBe('/api/admin/contests/contest-1/finalize');
    expect(call[1]).toBeUndefined();
  });

  it('encodes the contest identifier when finalising', async () => {
    await finalizeContest('a/b');

    expect(vi.mocked(apiClient.post).mock.calls[0][0]).toBe('/api/admin/contests/a%2Fb/finalize');
  });
});
