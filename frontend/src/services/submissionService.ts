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

export async function listSubmissions(
  query: { page?: number; size?: number; problemId?: string; status?: SubmissionStatus },
  signal?: AbortSignal,
): Promise<PageResponse<SubmissionSummary>> {
  const params: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value as string | number;
    }
  }
  const response = await apiClient.get<PageResponse<SubmissionSummary>>('/api/submissions', {
    params,
    signal,
  });
  return response.data;
}
