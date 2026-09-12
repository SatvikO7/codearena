import { useEffect, useRef, useState } from 'react';
import { getSubmission } from '../services/submissionService';
import { describeApiError } from '../services/apiClient';
import { isTerminal } from '../types/submission';
import type { SubmissionDetail } from '../types/submission';

/**
 * How long between polls.
 *
 * <p>A judgement takes anywhere from a few hundred milliseconds (Python, one test) to tens
 * of seconds (C++ compile plus a full suite). One second is a compromise: responsive enough
 * that a fast verdict feels immediate, slow enough that a long judgement is not a hundred
 * requests.
 */
const POLL_INTERVAL_MS = 1000;

/**
 * Stops polling after this long even if the status never settles.
 *
 * <p>The server's own recovery gives up well before this, so reaching it means something is
 * wrong that more polling cannot fix. Without a ceiling a forgotten tab would poll for ever.
 */
const POLL_TIMEOUT_MS = 3 * 60 * 1000;

/** What has been observed, and which submission it belongs to. */
interface Tracked {
  id: string;
  submission: SubmissionDetail | null;
  error: string | null;
  timedOut: boolean;
}

export interface PollingState {
  submission: SubmissionDetail | null;
  error: string | null;
  /** True while the status is still QUEUED or RUNNING. */
  pending: boolean;
  timedOut: boolean;
}

/**
 * Follows a submission until it reaches a verdict.
 *
 * <p>Polling rather than websockets: the server has no push channel yet, and a one-second
 * poll against a single row is cheap and honest about what it is. Real-time updates are a
 * later phase, and this hook is the seam they will replace.
 *
 * <p>The observed state is tagged with the id it came from and the result is
 * <em>derived</em> during render rather than reset in an effect. That matters: clearing the
 * previous submission with a setState would render one frame of the old verdict against the
 * new id, and would make this hook's effect fire a cascading render on every change.
 * Passing {@code null} stops polling, and the derivation makes that read as "nothing".
 */
export function useSubmissionPolling(submissionId: string | null): PollingState {
  const [tracked, setTracked] = useState<Tracked | null>(null);
  const startedAt = useRef<number>(0);

  useEffect(() => {
    if (!submissionId) {
      return;
    }

    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    startedAt.current = Date.now();

    const poll = async () => {
      try {
        const next = await getSubmission(submissionId, controller.signal);
        if (controller.signal.aborted) return;

        setTracked({ id: submissionId, submission: next, error: null, timedOut: false });

        if (isTerminal(next.status)) {
          return;   // settled: no further request is scheduled
        }
        if (Date.now() - startedAt.current > POLL_TIMEOUT_MS) {
          setTracked({ id: submissionId, submission: next, error: null, timedOut: true });
          return;
        }
        timer = setTimeout(() => void poll(), POLL_INTERVAL_MS);
      } catch (caught) {
        if (controller.signal.aborted) return;
        // A transient failure should not abandon a submission that is still being judged,
        // so the message is recorded and polling continues at a slower pace; a persistent
        // failure eventually reaches the timeout above.
        const message = describeApiError(caught);
        setTracked((current) => ({
          id: submissionId,
          submission: current?.id === submissionId ? current.submission : null,
          error: message,
          timedOut: false,
        }));
        timer = setTimeout(() => void poll(), POLL_INTERVAL_MS * 2);
      }
    };

    void poll();

    return () => {
      controller.abort();
      if (timer) {
        clearTimeout(timer);
      }
    };
  }, [submissionId]);

  // Derived, not stored: state belonging to a previous id is simply not shown.
  const current = submissionId && tracked?.id === submissionId ? tracked : null;

  return {
    submission: current?.submission ?? null,
    error: current?.error ?? null,
    pending: current?.submission != null && !isTerminal(current.submission.status),
    timedOut: current?.timedOut ?? false,
  };
}
