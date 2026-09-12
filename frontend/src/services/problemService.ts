import { apiClient } from './apiClient';
import type {
  AdminProblemDetail,
  PageResponse,
  ProblemDetail,
  ProblemPayload,
  ProblemQuery,
  ProblemSummary,
} from '../types/problem';

/**
 * Drops empty filters so the request carries only the parameters that mean something.
 * Sending `difficulty=` would be rejected by the server as an invalid enum value.
 */
function toParams(query: ProblemQuery): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value as string | number;
    }
  }
  return params;
}

// ---------------------------------------------------------------- catalogue

export async function listProblems(
  query: ProblemQuery,
  signal?: AbortSignal,
): Promise<PageResponse<ProblemSummary>> {
  const response = await apiClient.get<PageResponse<ProblemSummary>>('/api/problems', {
    params: toParams(query),
    signal,
  });
  return response.data;
}

export async function getProblem(slug: string, signal?: AbortSignal): Promise<ProblemDetail> {
  const response = await apiClient.get<ProblemDetail>(`/api/problems/${encodeURIComponent(slug)}`, {
    signal,
  });
  return response.data;
}

// -------------------------------------------------------------------- admin

export async function listAdminProblems(
  query: ProblemQuery,
  signal?: AbortSignal,
): Promise<PageResponse<ProblemSummary>> {
  const response = await apiClient.get<PageResponse<ProblemSummary>>('/api/admin/problems', {
    params: toParams(query),
    signal,
  });
  return response.data;
}

export async function getAdminProblem(id: string, signal?: AbortSignal): Promise<AdminProblemDetail> {
  const response = await apiClient.get<AdminProblemDetail>(`/api/admin/problems/${id}`, { signal });
  return response.data;
}

export async function createProblem(payload: ProblemPayload): Promise<AdminProblemDetail> {
  const response = await apiClient.post<AdminProblemDetail>('/api/admin/problems', payload);
  return response.data;
}

export async function updateProblem(id: string, payload: ProblemPayload): Promise<AdminProblemDetail> {
  const response = await apiClient.put<AdminProblemDetail>(`/api/admin/problems/${id}`, payload);
  return response.data;
}

/** The lifecycle operations. Status is never sent as a field; each move is its own endpoint. */
export type LifecycleAction = 'publish' | 'unpublish' | 'archive' | 'restore';

export async function changeLifecycle(
  id: string,
  action: LifecycleAction,
): Promise<AdminProblemDetail> {
  const response = await apiClient.post<AdminProblemDetail>(`/api/admin/problems/${id}/${action}`);
  return response.data;
}
