import { useEffect, useState } from 'react';
import { fetchSystemInfo } from '../services/systemService';
import { describeApiError } from '../services/apiClient';
import type { SystemInfo } from '../types/system';

export type ConnectionState =
  | { status: 'loading' }
  | { status: 'connected'; info: SystemInfo }
  | { status: 'error'; message: string };

/** Reports whether the browser can reach the API server, for the status panel. */
export function useSystemInfo(): ConnectionState {
  const [state, setState] = useState<ConnectionState>({ status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();

    fetchSystemInfo(controller.signal)
      .then((info) => setState({ status: 'connected', info }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setState({ status: 'error', message: describeApiError(error) });
      });

    return () => controller.abort();
  }, []);

  return state;
}
