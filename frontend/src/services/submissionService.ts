import { apiClient } from './apiClient';
import type { PageResponse } from '../types/problem';
import type {
  Language,
  SubmissionAccepted,
  SubmissionDetail,
  SubmissionStatus,
  SubmissionSummary,
} from '../types/submission';

export async function submitSolution(
  problemId: string,
  language: Language,
  sourceCode: string,
): Promise<SubmissionAccepted> {
  const response = await apiClient.post<SubmissionAccepted>(
    `/api/problems/${problemId}/submissions`,
    { language, sourceCode },
  );
  return response.data;
}

export async function getSubmission(id: string, signal?: AbortSignal): Promise<SubmissionDetail> {
  const response = await apiClient.get<SubmissionDetail>(`/api/submissions/${id}`, { signal });
  return response.data;
}

/**
 * Drops empty and absent filters so a request carries only the parameters that mean
 * something. Sending `status=` would be rejected by the server as an invalid enum value.
 *
 * Typed loosely on purpose: callers pass narrowed query objects, and comparing a narrowed
 * union against the empty string is a type error even though the runtime value can be one.
 */
function toParams(query: object): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value as string | number;
    }
  }
  return params;
}

export interface SubmissionQuery {
  page?: number;
  size?: number;
  problemId?: string;
  status?: SubmissionStatus;
  language?: Language;
}

export async function listSubmissions(
  query: SubmissionQuery,
  signal?: AbortSignal,
): Promise<PageResponse<SubmissionSummary>> {
  const response = await apiClient.get<PageResponse<SubmissionSummary>>('/api/submissions', {
    params: toParams(query),
    signal,
  });
  return response.data;
}

/**
 * Your own submissions for one problem.
 *
 * Addressed from the problem, but scoped to the caller by the server — this is not a feed
 * of everybody's attempts, and there is no endpoint that would be.
 */
export async function listProblemSubmissions(
  problemId: string,
  query: Omit<SubmissionQuery, 'problemId'>,
  signal?: AbortSignal,
): Promise<PageResponse<SubmissionSummary>> {
  const response = await apiClient.get<PageResponse<SubmissionSummary>>(
    `/api/problems/${problemId}/submissions`,
    { params: toParams(query), signal },
  );
  return response.data;
}
