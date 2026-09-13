import { useEffect, useReducer } from 'react';
import { getSubmission } from '../services/submissionService';
import { describeApiError } from '../services/apiClient';
import { applyStreamEvent, initialStreamState } from '../services/submissionStream';
import type { StreamState, SubmissionStatusEvent } from '../services/submissionStream';
import { isTerminal } from '../types/submission';
import type { SubmissionDetail } from '../types/submission';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * How often the fallback polls.
 *
 * <p>Only used when SSE is unavailable — the browser lacks `EventSource`, a proxy strips
 * the stream, or the server is at its connection cap. Deliberately slower than the old
 * unconditional poll: this is a degraded path, not the normal one.
 */
const FALLBACK_POLL_MS = 2500;

/**
 * How long to keep watching before giving up entirely.
 *
 * <p>The server's own recovery gives up well before this, so reaching it means something is
 * wrong that more watching cannot fix. Without a ceiling, a forgotten tab would poll or
 * reconnect for ever.
 */
const WATCH_TIMEOUT_MS = 5 * 60 * 1000;

type Action =
  | { type: 'event'; event: SubmissionStatusEvent }
  | { type: 'detail'; detail: SubmissionDetail }
  | { type: 'error'; message: string }
  | { type: 'timeout' }
  | { type: 'transport'; transport: Transport }
  | { type: 'reset' };

export type Transport = 'connecting' | 'stream' | 'polling' | 'stopped';

interface InternalState extends StreamState {
  error: string | null;
  timedOut: boolean;
  transport: Transport;
}

const initial: InternalState = {
  ...initialStreamState,
  error: null,
  timedOut: false,
  transport: 'connecting',
};

function reducer(state: InternalState, action: Action): InternalState {
  switch (action.type) {
    case 'event': {
      // The same convergence rule for both transports: a poll result and a pushed event are
      // indistinguishable here, which is what lets them run concurrently without fighting.
      const next = applyStreamEvent(state, action.event);
      return next === state ? state : { ...state, ...next, error: null };
    }
    case 'detail': {
      const next = applyStreamEvent(state, toStatusEvent(action.detail));
      return next === state ? state : { ...state, ...next, error: null };
    }
    case 'error':
      return { ...state, error: action.message };
    case 'timeout':
      return { ...state, timedOut: true, transport: 'stopped' };
    case 'transport':
      return state.transport === action.transport ? state : { ...state, transport: action.transport };
    case 'reset':
      return initial;
  }
}

/** The detail endpoint returns a superset of the status payload; narrow it to converge on. */
function toStatusEvent(detail: SubmissionDetail): SubmissionStatusEvent {
  return {
    id: detail.id,
    status: detail.status,
    terminal: detail.terminal ?? false,
    testsTotal: detail.testsTotal,
    testsPassed: detail.testsPassed,
    failedTestIndex: detail.failedTestIndex,
    runtimeMs: detail.runtimeMs,
    errorMessage: detail.errorMessage,
    startedAt: detail.startedAt,
    finishedAt: detail.finishedAt,
    updatedAt: detail.updatedAt,
  };
}

export interface SubmissionWatch {
  status: SubmissionStatusEvent | null;
  /** True while the submission is still QUEUED or RUNNING. */
  pending: boolean;
  settled: boolean;
  error: string | null;
  timedOut: boolean;
  transport: Transport;
}

/**
 * Watches a submission until it reaches a verdict.
 *
 * <h2>How it stays correct</h2>
 * SSE is the primary transport and polling is the fallback, but neither is trusted on its
 * own. Both feed the same convergence reducer, so whichever arrives first wins and the
 * other is discarded as a duplicate. That matters because the two genuinely overlap during
 * a reconnect.
 *
 * <p><b>The fallback is armed, not permanent.</b> It starts only when the stream fails or
 * closes without a verdict, and it stops the moment a terminal status is observed. There is
 * no path that leaves a poll running after the submission is finished — which is the bug
 * the previous unconditional polling implementation had by construction.
 *
 * <p>Passing {@code null} stops everything; the effect's teardown closes the stream and
 * clears the timer, so cancellation is a language guarantee rather than a flag somebody has
 * to remember to set.
 */
export function useSubmissionStream(submissionId: string | null): SubmissionWatch {
  const [state, dispatch] = useReducer(reducer, initial);

  useEffect(() => {
    if (!submissionId) {
      return;
    }

    dispatch({ type: 'reset' });

    let stream: EventSource | null = null;
    let pollTimer: ReturnType<typeof setTimeout> | undefined;
    let watchdog: ReturnType<typeof setTimeout> | undefined;
    // Owned by the effect rather than mirrored from render state: a verdict has to stop
    // the timers in the same turn it is observed, and a value derived from rendering is
    // not available until React commits — which can be after the next poll has fired.
    let cancelled = false;
    const controller = new AbortController();

    const stopEverything = () => {
      cancelled = true;
      stream?.close();
      if (pollTimer) clearTimeout(pollTimer);
      if (watchdog) clearTimeout(watchdog);
      controller.abort();
    };

    /** Reads the authoritative state. Used by the fallback and after a stream failure. */
    const pollOnce = async () => {
      if (cancelled) {
        return;
      }
      try {
        const detail = await getSubmission(submissionId, controller.signal);
        if (cancelled) return;
        dispatch({ type: 'detail', detail });
        // Read from the status, not the server's `terminal` flag, so this matches exactly
        // what the reducer decided rather than trusting a second source for the same fact.
        if (isTerminal(detail.status)) {
          stopEverything();
          return;
        }
      } catch (caught) {
        if (cancelled) return;
        // A transient failure must not abandon a submission that is still being judged, so
        // the message is surfaced and polling continues until the watchdog fires.
        dispatch({ type: 'error', message: describeApiError(caught) });
      }
      if (!cancelled) {
        pollTimer = setTimeout(() => void pollOnce(), FALLBACK_POLL_MS);
      }
    };

    const startFallback = () => {
      if (cancelled || pollTimer) {
        return;
      }
      dispatch({ type: 'transport', transport: 'polling' });
      void pollOnce();
    };

    if (typeof EventSource === 'undefined') {
      // No SSE in this environment: go straight to the fallback rather than pretending.
      startFallback();
    } else {
      // withCredentials sends the session cookie, which is how the stream is authorised.
      stream = new EventSource(`${API_BASE}/api/submissions/${submissionId}/events`, {
        withCredentials: true,
      });

      const onMessage = (message: MessageEvent<string>) => {
        try {
          const event = JSON.parse(message.data) as SubmissionStatusEvent;
          dispatch({ type: 'event', event });
          if (isTerminal(event.status)) {
            stopEverything();
          }
        } catch {
          // An unparseable frame is dropped. The next event, or the fallback, repairs it.
        }
      };

      stream.addEventListener('snapshot', onMessage as EventListener);
      stream.addEventListener('submission', onMessage as EventListener);
      stream.onopen = () => dispatch({ type: 'transport', transport: 'stream' });
      stream.onerror = () => {
        // EventSource reports both a transport failure and a normal server-side close this
        // way, and cannot tell them apart. Either way the right move is the same: if the
        // verdict has not arrived, keep watching by polling.
        stream?.close();
        startFallback();
      };
    }

    // Reaching this means no verdict arrived: a settled watch has already cleared the
    // timer inside stopEverything, so the watchdog cannot fire after one.
    watchdog = setTimeout(() => {
      dispatch({ type: 'timeout' });
      stopEverything();
    }, WATCH_TIMEOUT_MS);

    return stopEverything;
  }, [submissionId]);

  return {
    status: state.current,
    pending: state.current !== null && !state.settled,
    settled: state.settled,
    error: state.error,
    timedOut: state.timedOut,
    transport: state.transport,
  };
}
