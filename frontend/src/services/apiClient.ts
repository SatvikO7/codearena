import axios, { AxiosError } from 'axios';
import type { ApiError } from '../types/system';
import type { FieldViolation } from '../types/auth';

/**
 * The single axios instance every service module shares.
 *
 * Components never call axios directly: keeping HTTP in the service layer means the base
 * URL, credential handling and error normalisation exist in exactly one place.
 *
 * `withCredentials` sends the session cookie; `withXSRFToken` echoes the CSRF cookie back
 * as a header. Both are required because the session cookie is HttpOnly and cross-origin
 * in development — axios will not attach either by default.
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
});

/** The server's error envelope, when the failure carried one. */
export function apiErrorBody(error: unknown): ApiError | undefined {
  return axios.isAxiosError(error) ? (error as AxiosError<ApiError>).response?.data : undefined;
}

/** The stable machine-readable code, for branching on a specific failure. */
export function apiErrorCode(error: unknown): string | undefined {
  return apiErrorBody(error)?.error;
}

/** Field-level validation failures, ready to attach to form inputs. */
export function apiFieldErrors(error: unknown): FieldViolation[] {
  return apiErrorBody(error)?.fieldErrors ?? [];
}

/**
 * Turns any axios failure into a readable message.
 *
 * The server's error envelope is preferred when present; network and timeout failures,
 * which carry no response, fall back to a message that says so rather than surfacing an
 * opaque axios string to the user.
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
