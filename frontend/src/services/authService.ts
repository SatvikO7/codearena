import { apiClient } from './apiClient';
import type { LoginPayload, RegistrationPayload, UserProfile } from '../types/auth';

export async function register(payload: RegistrationPayload): Promise<UserProfile> {
  const response = await apiClient.post<UserProfile>('/api/auth/register', payload);
  return response.data;
}

export async function login(payload: LoginPayload): Promise<UserProfile> {
  const response = await apiClient.post<UserProfile>('/api/auth/login', payload);
  return response.data;
}

export async function logout(): Promise<void> {
  await apiClient.post('/api/auth/logout');
}

/**
 * Resolves the session the browser already holds, if any.
 *
 * A 401 here is the normal "not signed in" answer rather than an error, so callers get
 * `null` instead of an exception. This is also the request that primes the CSRF cookie
 * when the app loads, which is why it runs before any form can be submitted.
 */
export async function fetchCurrentUser(signal?: AbortSignal): Promise<UserProfile | null> {
  try {
    const response = await apiClient.get<UserProfile>('/api/auth/me', { signal });
    return response.data;
  } catch (error) {
    if (isUnauthenticated(error)) {
      return null;
    }
    throw error;
  }
}

function isUnauthenticated(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'response' in error &&
    (error as { response?: { status?: number } }).response?.status === 401
  );
}
