import { apiClient } from './apiClient';
import type { AuditEvent, AuditQuery, SystemStatus } from '../types/audit';
import type { PageResponse } from '../types/problem';

/**
 * Drops empty filters so the request carries only what the administrator actually chose.
 *
 * <p>Sending `outcome=` would be rejected by the server as an invalid enum value, and
 * sending `action=` would narrow nothing while looking as though it narrowed something.
 */
function toParams(query: AuditQuery): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value as string | number;
    }
  }
  return params;
}

export async function searchAuditEvents(
  query: AuditQuery,
  signal?: AbortSignal,
): Promise<PageResponse<AuditEvent>> {
  const response = await apiClient.get<PageResponse<AuditEvent>>('/api/admin/audit-events', {
    params: toParams(query),
    signal,
  });
  return response.data;
}

/**
 * The action vocabulary, fetched rather than hard-coded.
 *
 * <p>A list baked into the frontend falls out of date the first time a phase adds an action,
 * and the symptom is a filter that cannot find the events somebody is looking for.
 */
export async function listAuditActions(signal?: AbortSignal): Promise<string[]> {
  const response = await apiClient.get<string[]>('/api/admin/audit-events/actions', { signal });
  return response.data;
}

export async function getSystemStatus(signal?: AbortSignal): Promise<SystemStatus> {
  const response = await apiClient.get<SystemStatus>('/api/admin/system/status', { signal });
  return response.data;
}
