import { isTerminal } from '../types/submission';
import type { SubmissionStatus } from '../types/submission';

/** The status payload carried by both the `snapshot` and `submission` SSE events. */
export interface SubmissionStatusEvent {
  id: string;
  status: SubmissionStatus;
  terminal: boolean;
  testsTotal?: number;
  testsPassed?: number;
  failedTestIndex?: number;
  runtimeMs?: number;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  /** When the row last changed. The basis of the convergence rule below. */
  updatedAt: string;
}

/**
 * What the client believes about a submission, and how it got there.
 *
 * `settled` means a terminal verdict has been observed, so nothing further can change it.
 */
export interface StreamState {
  current: SubmissionStatusEvent | null;
  settled: boolean;
}

export const initialStreamState: StreamState = { current: null, settled: false };

/**
 * Folds an incoming event into what the client already believes.
 *
 * <p>This is the entire answer to "at-least-once delivery". The transport can duplicate an
 * event, deliver two out of order, or drop one, and every one of those has to end with the
 * client showing what the database says. Two rules achieve it:
 *
 * <ol>
 *   <li><b>Terminal absorbs.</b> Once a verdict is seen, nothing replaces it. A late
 *       RUNNING frame arriving after ACCEPTED — the case the brief explicitly forbids —
 *       is discarded rather than rendered.</li>
 *   <li><b>`updatedAt` moves forward only.</b> An event not strictly newer than the applied
 *       one is a duplicate or a straggler, and is ignored. Equal timestamps are ignored
 *       too: re-applying the same row changes nothing but would churn React state.</li>
 * </ol>
 *
 * <p>A dropped event needs no rule, because it is repaired rather than reasoned about: the
 * snapshot on reconnect and the fallback poll both re-read the database.
 *
 * <p>Pure and total, so it is exhaustively testable without a network, a clock or a DOM.
 */
export function applyStreamEvent(state: StreamState, event: SubmissionStatusEvent): StreamState {
  if (state.settled) {
    return state;
  }

  if (state.current) {
    const incoming = Date.parse(event.updatedAt);
    const applied = Date.parse(state.current.updatedAt);
    // An unparseable timestamp must not silently win. Treating NaN as "not newer" keeps a
    // malformed frame from replacing good state.
    if (!(incoming > applied)) {
      return state;
    }
  }

  return {
    current: event,
    settled: isTerminal(event.status),
  };
}

/**
 * Whether the client should still be watching.
 *
 * Nothing further can happen once a verdict is in, so both the stream and the fallback poll
 * stop here — this is what prevents the permanent polling loop.
 */
export function shouldKeepWatching(state: StreamState): boolean {
  return !state.settled;
}
