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

/**
 * Whether the server refused this request for going too fast.
 *
 * Worth distinguishing from every other failure: a 429 is not a bug, not a lost session and
 * not something the user did wrong. It is temporary, and the only correct response is to
 * wait — which is a different thing to tell somebody than "request failed with status 429".
 */
export function isRateLimited(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 429;
}

/**
 * How long the server asked the client to wait, in seconds.
 *
 * Read from `Retry-After` rather than guessed. The server computes it from the state of the
 * bucket, so it is the one number that is actually right; inventing a delay locally would
 * either retry too early — and be refused again — or make the user wait longer than they
 * need to. Returns undefined when the header is missing or not a number, in which case the
 * UI says "in a moment" rather than making something up.
 */
export function retryAfterSeconds(error: unknown): number | undefined {
  if (!axios.isAxiosError(error)) {
    return undefined;
  }
  const header = error.response?.headers?.['retry-after'] as string | number | undefined;
  if (header === undefined || header === null || header === '') {
    return undefined;
  }
  const seconds = Number(header);
  return Number.isFinite(seconds) && seconds >= 0 ? Math.ceil(seconds) : undefined;
}

/** "17 seconds", "1 minute" — a wait a person can act on. */
export function describeWait(seconds: number): string {
  if (seconds < 60) {
    return `${seconds} second${seconds === 1 ? '' : 's'}`;
  }
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes === 1 ? '' : 's'}`;
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
    // Answered before the generic body message, because the server's sentence is
    // deliberately identical for every policy -- it must not reveal which control was
    // tripped -- and the concrete wait is the part that is actually useful here.
    if (isRateLimited(error)) {
      const wait = retryAfterSeconds(error);
      return wait === undefined
        ? 'Too many requests. Please wait a moment and try again.'
        : `Too many requests. Please try again in ${describeWait(wait)}.`;
    }
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
