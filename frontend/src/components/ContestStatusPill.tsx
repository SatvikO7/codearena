import { CONTEST_STATUS_GLYPHS, CONTEST_STATUS_LABELS } from '../types/contest';
import type { ContestStatus } from '../types/contest';

/** Groups contest states for styling. */
function toneOf(status: ContestStatus): 'ok' | 'error' | 'pending' | 'neutral' {
  if (status === 'LIVE') return 'ok';
  if (status === 'UPCOMING') return 'pending';
  if (status === 'CANCELLED') return 'error';
  return 'neutral';
}

/**
 * A contest's state, as a badge.
 *
 * <p>Carries a glyph and a word as well as a colour, for the same reason the submission
 * verdict pills do: colour alone is not legible to everyone, and "is this contest running?"
 * is not a question to answer with a shade of green.
 */
export function ContestStatusPill({ status }: { status: ContestStatus }) {
  return (
    <span className={`pill pill--${toneOf(status)}`}>
      <span className="pill-glyph" aria-hidden="true">
        {CONTEST_STATUS_GLYPHS[status]}
      </span>
      {CONTEST_STATUS_LABELS[status]}
      {status === 'LIVE' && <span className="pill-spinner" aria-hidden="true" />}
    </span>
  );
}
