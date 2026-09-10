import axios, { AxiosError } from 'axios';
import type { ApiError } from '../types/system';

/**
 * The single axios instance every service module shares.
 *
 * Components never call axios directly: keeping HTTP in the service layer means the
 * base URL, auth headers and error normalisation exist in exactly one place.
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
});

/**
 * Turns any axios failure into a readable message.
 *
 * The server's error envelope is preferred when present; network and timeout failures,
 * which carry no response, fall back to a message that says so rather than surfacing
 * an opaque axios string to the user.
 */
export function describeApiError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<ApiError>;
    if (axiosError.response?.data?.message) {
      return axiosError.response.data.message;
    }
    if (axiosError.code === 'ECONNABORTED') {
      return 'The request timed out.';
    }
    if (!axiosError.response) {
      return 'Cannot reach the API server. Is the backend running?';
    }
    return `Request failed with status ${axiosError.response.status}.`;
  }
  return 'An unexpected error occurred.';
}
