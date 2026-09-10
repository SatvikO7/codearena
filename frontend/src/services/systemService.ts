import { apiClient } from './apiClient';
import type { SystemInfo } from '../types/system';

export async function fetchSystemInfo(signal?: AbortSignal): Promise<SystemInfo> {
  const response = await apiClient.get<SystemInfo>('/api/system/info', { signal });
  return response.data;
}
