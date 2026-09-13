import { useCallback, useEffect, useRef, useState } from 'react';
import { clockOffset, correctedNow, countdown, hasProbablyChanged } from '../services/contestClock';
import type { Countdown } from '../services/contestClock';
import type { ContestDetail } from '../types/contest';

/**
 * A ticking countdown, corrected against the server's clock.
 *
 * <h2>What it does about a sleeping tab</h2>
 * Browsers throttle timers in background tabs and stop them entirely while a machine is
 * asleep. A page left open overnight therefore wakes with a countdown that is hours wrong and
 * a contest state that may have changed twice.
 *
 * <p>Two things handle that, and neither is a faster timer:
 * <ul>
 *   <li>Every tick recomputes from timestamps rather than decrementing a counter, so a
 *       missed tick costs nothing — the next one is correct regardless.</li>
 *   <li>When the derived state stops matching what the server last said, or the tab becomes
 *       visible again, {@code onStale} fires so the page can re-fetch. The hook never decides
 *       locally that a contest has started or ended; it decides that it should go and ask.</li>
 * </ul>
 *
 * @param contest the last contest state the server sent, or null while loading
 * @param onStale called when the page's idea of the contest looks out of date
 */
export function useContestClock(
  contest: ContestDetail | null,
  onStale: () => void,
): Countdown | null {
  const [tick, setTick] = useState(() => Date.now());

  /**
   * How far this browser's clock is from the server's.
   *
   * <p>Adjusted during render when a new server response arrives, rather than in an effect:
   * an effect would render one frame against an uncorrected clock before fixing it, which is
   * a visible jump in the countdown.
   *
   * <p>It is measured only when the contest object changes. Re-measuring every tick would
   * make the offset track the browser's clock, which is the very thing being corrected for.
   *
   * <p>Measured against {@code tick} — the browser time this hook last recorded — rather
   * than by calling the clock during render. That keeps the render pure, at the cost of the
   * offset being based on a reading up to a second old. A sub-second error in a countdown
   * displayed to the second is not worth an impure render to avoid.
   */
  const [measured, setMeasured] = useState<ContestDetail | null>(null);
  const [offset, setOffset] = useState(0);
  if (contest !== measured) {
    setMeasured(contest);
    setOffset(contest ? clockOffset(contest, tick) : 0);
  }

  // Held in a ref so the listeners below reach the latest callback without being torn down
  // and rebuilt whenever the page passes a new closure.
  const onStaleRef = useRef(onStale);
  useEffect(() => {
    onStaleRef.current = onStale;
  }, [onStale]);

  const notifyStale = useCallback(() => onStaleRef.current(), []);

  useEffect(() => {
    if (!contest) {
      return;
    }
    const timer = setInterval(() => setTick(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [contest]);

  // A tab that has just become visible may have missed hours. Ask, rather than guess.
  useEffect(() => {
    function onVisibilityChange() {
      if (document.visibilityState === 'visible') {
        setTick(Date.now());
        notifyStale();
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [notifyStale]);

  const current = contest ? countdown(contest, correctedNow(offset, tick)) : null;
  const derived = current?.status ?? null;

  /**
   * Fires once per divergence, not once per tick.
   *
   * <p>A ref rather than state because nothing renders from it: it only remembers which
   * divergence has already been reported, so that a second of waiting for the server to
   * agree does not become a request every second.
   */
  const reportedRef = useRef<string | null>(null);
  useEffect(() => {
    if (!contest || !derived) {
      return;
    }
    if (!hasProbablyChanged(contest, derived)) {
      reportedRef.current = null;
      return;
    }
    if (reportedRef.current !== derived) {
      reportedRef.current = derived;
      notifyStale();
    }
  }, [contest, derived, notifyStale]);

  return current;
}
