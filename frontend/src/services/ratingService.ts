import { apiClient } from './apiClient';
import type {
  ContestRatingResult,
  FinalizationResult,
  HistoryEntry,
  RankingEntry,
  RatingProfile,
} from '../types/rating';
import type { PageResponse } from '../types/problem';

/**
 * Reads of the rating system, and one administrative write.
 *
 * <p>Note what is missing: there is no `setRating`, no `adjustRating`, no `recalculate`. Not
 * because they are omitted for later, but because the API has no such endpoint — a rating
 * moves in exactly one place, inside a contest finalisation, computed from standings the
 * server derived. The only rating-related thing a browser can cause is
 * {@link finalizeContest}, which asks the server to compute; it does not supply a number and
 * has no body to supply one in.
 */

export async function getRankings(
  query: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResponse<RankingEntry>> {
  const response = await apiClient.get<PageResponse<RankingEntry>>('/api/rankings', {
    params: { page: query.page ?? 0, size: query.size ?? 50 },
    signal,
  });
  return response.data;
}

export async function getRatingProfile(
  userId: string,
  signal?: AbortSignal,
): Promise<RatingProfile> {
  const response = await apiClient.get<RatingProfile>(
    `/api/users/${encodeURIComponent(userId)}/rating`,
    { signal },
  );
  return response.data;
}

export async function getRatingHistory(
  userId: string,
  query: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResponse<HistoryEntry>> {
  const response = await apiClient.get<PageResponse<HistoryEntry>>(
    `/api/users/${encodeURIComponent(userId)}/rating/history`,
    { params: { page: query.page ?? 0, size: query.size ?? 20 }, signal },
  );
  return response.data;
}

/**
 * The caller's own rating outcome for one contest.
 *
 * <p>There is no parameter for whose result to fetch, and adding one would change nothing:
 * the server takes the competitor from the session.
 */
export async function getContestRating(
  contestId: string,
  signal?: AbortSignal,
): Promise<ContestRatingResult> {
  const response = await apiClient.get<ContestRatingResult>(
    `/api/contests/${encodeURIComponent(contestId)}/rating`,
    { signal },
  );
  return response.data;
}

/**
 * Asks the server to finalise a contest now. ADMIN only.
 *
 * <p>No body, because there is nothing to send — every input comes from the database. The
 * call is idempotent: if it has already happened, the response says so and nothing changes.
 */
export async function finalizeContest(contestId: string): Promise<FinalizationResult> {
  const response = await apiClient.post<FinalizationResult>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/finalize`,
  );
  return response.data;
}
