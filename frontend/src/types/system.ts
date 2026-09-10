/** Metadata returned by `GET /api/system/info`. */
export interface SystemInfo {
  service: string;
  version: string;
  profiles: string[];
  serverTime: string;
}

/** The error envelope every failing API call returns. See `ApiErrorResponse` on the server. */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors?: { field: string; message: string }[];
}
