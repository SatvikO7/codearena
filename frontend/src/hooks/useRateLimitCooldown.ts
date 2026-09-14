import { useCallback, useEffect, useRef, useState } from 'react';
import { isRateLimited, retryAfterSeconds } from '../services/apiClient';

/** Used when the server refused but did not say how long to wait. */
const DEFAULT_COOLDOWN_SECONDS = 30;

/**
 * Holds an action closed for as long as the server asked, and counts down while it waits.
 *
 * <h2>Why the UI needs this at all</h2>
 * A 429 that only produces an error message leaves the button live, and the natural thing
 * for somebody to do with a button that has just failed is press it again. Every one of
 * those presses is refused, and each refusal extends the wait — the user makes their own
 * situation worse, and the server absorbs a burst of pointless requests from a client that
 * has already been told to stop.
 *
 * <p>So the wait is enforced on this side too, from the server's own `Retry-After`. Not
 * guessed: the server computes the number from the state of the bucket, and a locally
 * invented delay would either retry too early and be refused again, or make somebody wait
 * longer than they had to.
 *
 * <h2>What this deliberately does not do</h2>
 * It does not retry. Nothing here schedules the failed request to go again when the
 * countdown reaches zero — it simply lets the person try. An automatic retry is how a rate
 * limit becomes a retry storm: every throttled client waking at once and resending, which
 * is the shape of the load the limit existed to prevent. The user decides.
 *
 * <p>It is also not a security control, and nothing depends on it. A client that ignores
 * this is refused by the server exactly as before; this only stops an honest client from
 * hurting itself.
 */
export function useRateLimitCooldown() {
  const [remainingSeconds, setRemainingSeconds] = useState(0);
  const deadlineRef = useRef<number>(0);

  useEffect(() => {
    if (remainingSeconds <= 0) {
      return;
    }
    const timer = setInterval(() => {
      const left = Math.ceil((deadlineRef.current - Date.now()) / 1000);
      setRemainingSeconds(left > 0 ? left : 0);
    }, 1000);
    return () => clearInterval(timer);
  }, [remainingSeconds]);

  /**
   * Starts a cooldown if this failure was a rate limit.
   *
   * @returns true when the error was a 429, so a caller can branch without testing twice
   */
  const startIfRateLimited = useCallback((error: unknown): boolean => {
    if (!isRateLimited(error)) {
      return false;
    }
    const seconds = retryAfterSeconds(error) ?? DEFAULT_COOLDOWN_SECONDS;
    deadlineRef.current = Date.now() + seconds * 1000;
    setRemainingSeconds(seconds);
    return true;
  }, []);

  return { remainingSeconds, cooling: remainingSeconds > 0, startIfRateLimited };
}
