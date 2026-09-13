import { apiClient } from './apiClient';
import type {
  ContestDetail,
  ContestPayload,
  ContestParticipant,
  ContestRegistration,
  ContestStatus,
  ContestSummary,
  Standings,
} from '../types/contest';
import type { PageResponse } from '../types/problem';
import type { SubmissionAccepted } from '../types/submission';

// ---------------------------------------------------------------- contestant

export async function listContests(
  query: { status?: ContestStatus | ''; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResponse<ContestSummary>> {
  const params: Record<string, string | number> = {};
  // An empty status would be rejected as an invalid enum value, so it is dropped rather
  // than sent as a blank.
  if (query.status) params.status = query.status;
  if (query.page !== undefined) params.page = query.page;
  if (query.size !== undefined) params.size = query.size;

  const response = await apiClient.get<PageResponse<ContestSummary>>('/api/contests', {
    params,
    signal,
  });
  return response.data;
}

export async function getContest(contestId: string, signal?: AbortSignal): Promise<ContestDetail> {
  const response = await apiClient.get<ContestDetail>(
    `/api/contests/${encodeURIComponent(contestId)}`,
    { signal },
  );
  return response.data;
}

/**
 * Registers the signed-in user.
 *
 * <p>No body: the server takes the participant from the session. There is deliberately no
 * parameter here for a user id, because the API has no field for one.
 */
export async function registerForContest(contestId: string): Promise<ContestRegistration> {
  const response = await apiClient.post<ContestRegistration>(
    `/api/contests/${encodeURIComponent(contestId)}/register`,
  );
  return response.data;
}

/**
 * Submits inside a contest.
 *
 * <p>A different endpoint from the practice one, and that is the whole point: posting to
 * `/api/problems/{id}/submissions` from a contest page would create a practice submission
 * that never reaches the standings. The contest is in the URL, never in the body.
 */
export async function submitToContest(
  contestId: string,
  problemId: string,
  language: string,
  sourceCode: string,
): Promise<SubmissionAccepted> {
  const response = await apiClient.post<SubmissionAccepted>(
    `/api/contests/${encodeURIComponent(contestId)}/problems/${encodeURIComponent(problemId)}/submissions`,
    { language, sourceCode },
  );
  return response.data;
}

export async function getStandings(contestId: string, signal?: AbortSignal): Promise<Standings> {
  const response = await apiClient.get<Standings>(
    `/api/contests/${encodeURIComponent(contestId)}/standings`,
    { signal },
  );
  return response.data;
}

// ---------------------------------------------------------------- admin

export async function listContestsForAdmin(
  query: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResponse<ContestSummary>> {
  const response = await apiClient.get<PageResponse<ContestSummary>>('/api/admin/contests', {
    params: { page: query.page ?? 0, size: query.size ?? 20 },
    signal,
  });
  return response.data;
}

export async function getContestForAdmin(
  contestId: string,
  signal?: AbortSignal,
): Promise<ContestDetail> {
  const response = await apiClient.get<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}`,
    { signal },
  );
  return response.data;
}

export async function createContest(payload: ContestPayload): Promise<ContestDetail> {
  const response = await apiClient.post<ContestDetail>('/api/admin/contests', payload);
  return response.data;
}

export async function updateContest(
  contestId: string,
  payload: ContestPayload,
): Promise<ContestDetail> {
  const response = await apiClient.put<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}`,
    payload,
  );
  return response.data;
}

export async function publishContest(contestId: string): Promise<ContestDetail> {
  const response = await apiClient.post<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/publish`,
  );
  return response.data;
}

export async function cancelContest(contestId: string): Promise<ContestDetail> {
  const response = await apiClient.post<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/cancel`,
  );
  return response.data;
}

export async function deleteContest(contestId: string): Promise<void> {
  await apiClient.delete(`/api/admin/contests/${encodeURIComponent(contestId)}`);
}

/** Each of these returns the whole contest, so the caller gets the relabelled problem list. */
export async function addContestProblem(
  contestId: string,
  problemId: string,
  points: number,
): Promise<ContestDetail> {
  const response = await apiClient.post<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/problems`,
    { problemId, points },
  );
  return response.data;
}

export async function updateContestProblem(
  contestId: string,
  problemId: string,
  change: { points?: number; displayOrder?: number },
): Promise<ContestDetail> {
  const response = await apiClient.put<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/problems/${encodeURIComponent(problemId)}`,
    change,
  );
  return response.data;
}

export async function removeContestProblem(
  contestId: string,
  problemId: string,
): Promise<ContestDetail> {
  const response = await apiClient.delete<ContestDetail>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/problems/${encodeURIComponent(problemId)}`,
  );
  return response.data;
}

export async function listParticipants(
  contestId: string,
  signal?: AbortSignal,
): Promise<ContestParticipant[]> {
  const response = await apiClient.get<ContestParticipant[]>(
    `/api/admin/contests/${encodeURIComponent(contestId)}/participants`,
    { signal },
  );
  return response.data;
}
