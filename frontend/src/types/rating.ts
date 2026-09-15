/**
 * The rating vocabulary, mirroring the server's.
 *
 * <p>Read-only by construction. There is no payload type here, because there is no endpoint
 * that accepts a rating — the browser can display one and can ask for one to be computed, and
 * that is the whole of its authority. A type for "the rating to set" would be describing a
 * capability that does not exist.
 */

export interface RankingEntry {
  /** Competition rank: ties share a rank and the next rank skips. */
  rank: number;
  userId: string;
  username: string;
  rating: number;
  peakRating: number;
  contestsRated: number;
}

export interface HistoryEntry {
  contestId: string;
  contestTitle: string;
  contestSlug: string;
  contestEndAt: string;
  rank: number;
  participantCount: number;
  score: number;
  penalty: number;
  ratingBefore: number;
  ratingChange: number;
  ratingAfter: number;
  ratedAt: string;
}

export interface ProgressionPoint {
  at: string;
  rating: number;
  contestTitle: string;
}

export interface RatingProfile {
  userId: string;
  username: string;
  /**
   * Null when this competitor has never been rated.
   *
   * <p>Not zero, and the difference matters on screen: "unrated" and "rated 0" are different
   * facts, and a page that showed the second when the first was true would be inventing a
   * result. Everything that renders a rating has to handle the null.
   */
  rating: number | null;
  peakRating: number | null;
  rank: number | null;
  contestsRated: number;
  rated: boolean;
  lastRatedAt: string | null;
  recent: HistoryEntry[];
  progression: ProgressionPoint[];
}

/**
 * What a contest did to the caller's rating.
 *
 * - `UNRATED` — this contest does not move ratings, and never will.
 * - `CANCELLED` — it was called off. There will never be a rating.
 * - `PENDING` — rated, ended, not yet computed. A rating is coming.
 * - `FINALIZED` — here is the result, or nulls if the caller did not compete.
 */
export type ContestRatingStatus = 'UNRATED' | 'PENDING' | 'FINALIZED' | 'CANCELLED';

export interface ContestRatingResult {
  contestId: string;
  status: ContestRatingStatus;
  rank: number | null;
  participantCount: number | null;
  score: number | null;
  penalty: number | null;
  ratingBefore: number | null;
  ratingChange: number | null;
  ratingAfter: number | null;
  finalizedAt: string | null;
}

export interface FinalizationResult {
  contestId: string;
  /** True when the call found the work already done. Not an error. */
  alreadyFinalized: boolean;
  rated: boolean;
  ratedParticipants: number;
  finalizedAt: string | null;
}

/**
 * Bands, purely for display.
 *
 * <p>These name a rating; they do not change one. The server knows nothing about them, and
 * moving a boundary here moves a label and nothing else — which is the point of keeping them
 * on this side of the wire.
 */
export interface RatingBand {
  name: string;
  min: number;
  className: string;
}

export const RATING_BANDS: RatingBand[] = [
  { name: 'Grandmaster', min: 2400, className: 'rating-band--grandmaster' },
  { name: 'Master', min: 2100, className: 'rating-band--master' },
  { name: 'Expert', min: 1900, className: 'rating-band--expert' },
  { name: 'Specialist', min: 1600, className: 'rating-band--specialist' },
  { name: 'Apprentice', min: 1400, className: 'rating-band--apprentice' },
  { name: 'Novice', min: 0, className: 'rating-band--novice' },
];

/** The band a rating falls in, or null when there is no rating to band. */
export function bandFor(rating: number | null): RatingBand | null {
  if (rating === null) {
    return null;
  }
  return RATING_BANDS.find((band) => rating >= band.min) ?? RATING_BANDS[RATING_BANDS.length - 1];
}

/**
 * A rating change as text, with an explicit sign.
 *
 * <p>`+0` rather than `0`, deliberately. Zero is a real rating change — it means the result
 * was exactly what the ratings predicted — and writing it the same way as every other change
 * says so. A bare "0" reads like a missing value.
 */
export function formatChange(change: number): string {
  return change >= 0 ? `+${change}` : `${change}`;
}

/** Which way a change went, for styling and for the glyph beside it. */
export function changeDirection(change: number): 'up' | 'down' | 'level' {
  if (change > 0) return 'up';
  if (change < 0) return 'down';
  return 'level';
}
