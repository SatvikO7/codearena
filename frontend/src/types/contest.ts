/**
 * The contest vocabulary, mirroring the server's.
 *
 * <p>Every timestamp is an ISO-8601 string with an offset, as the API sends it. They are
 * parsed for display and for the countdown, and are never used to decide whether something
 * is allowed — that is the server's job, on every request.
 */

export type ContestStatus = 'DRAFT' | 'UPCOMING' | 'LIVE' | 'ENDED' | 'CANCELLED';

/** Short words for each state, so the UI never invents its own wording. */
export const CONTEST_STATUS_LABELS: Record<ContestStatus, string> = {
  DRAFT: 'Draft',
  UPCOMING: 'Upcoming',
  LIVE: 'Live',
  ENDED: 'Ended',
  CANCELLED: 'Cancelled',
};

/**
 * A glyph per state, so status is never carried by colour alone.
 *
 * <p>The same reasoning as the submission verdicts: roughly one man in twelve cannot
 * reliably tell the green badge from the red one.
 */
export const CONTEST_STATUS_GLYPHS: Record<ContestStatus, string> = {
  DRAFT: '✎',
  UPCOMING: '◷',
  LIVE: '●',
  ENDED: '✓',
  CANCELLED: '✕',
};

export interface ContestSummary {
  id: string;
  title: string;
  slug: string;
  status: ContestStatus;
  startAt: string;
  endAt: string;
  participantCount: number;
  problemCount: number;
  registered: boolean;
  /** Whether this contest moves ratings. In the list because it decides who enters. */
  rated: boolean;
}

export interface ContestProblemEntry {
  problemId: string;
  problemSlug: string;
  title: string;
  /** A, B, C — the contest's own label, not the problem's title ordering. */
  label: string;
  displayOrder: number;
  points: number;
  /** Null when the caller has no relationship to the contest. */
  solved: boolean | null;
  attempts: number | null;
}

export interface ContestDetail {
  id: string;
  title: string;
  slug: string;
  description: string | null;
  status: ContestStatus;
  startAt: string;
  /** Exclusive: the contest is over at exactly this instant. */
  endAt: string;
  /**
   * The server's clock when this was served.
   *
   * <p>The countdown is drawn against this rather than against `Date.now()`. A laptop
   * resumed from sleep, or a machine whose clock has drifted, would otherwise show a timer
   * that is minutes wrong — and a contestant who trusts it submits too late.
   */
  serverTime: string;
  participantCount: number;
  registered: boolean;
  /** Registered, and the contest is LIVE. Re-checked by the server on every submission. */
  canSubmit: boolean;
  /**
   * Whether this contest moves ratings.
   *
   * <p>Fixed once the contest starts. The server refuses to change it after that, so the
   * value a contestant saw when they entered is the value that applies to them.
   */
  rated: boolean;
  /**
   * When rating finalisation ran, or null if it has not.
   *
   * <p>Set for unrated contests too: it answers "has the question been settled", while
   * `rated` answers "did settling it change anything".
   */
  ratingFinalizedAt: string | null;
  /** Empty until the contest starts: the problem set is not disclosed in advance. */
  problems: ContestProblemEntry[];
}

export interface ContestRegistration {
  contestId: string;
  status: ContestStatus;
  registeredAt: string;
  alreadyRegistered: boolean;
}

export interface StandingsColumn {
  problemId: string;
  label: string;
  title: string;
  points: number;
}

export interface StandingsCell {
  problemId: string;
  solved: boolean;
  attempts: number;
  penaltyMinutes: number | null;
  solvedAtMinute: number | null;
}

export interface StandingsRow {
  /** Competition rank: ties share a rank and the next one skips. */
  rank: number;
  userId: string;
  username: string;
  solved: number;
  score: number;
  penalty: number;
  cells: StandingsCell[];
}

export interface Standings {
  contestId: string;
  status: ContestStatus;
  computedAt: string;
  problems: StandingsColumn[];
  rows: StandingsRow[];
}

export interface ContestParticipant {
  userId: string;
  username: string;
  registeredAt: string;
}

/** What an administrator sends when creating or editing a contest. */
export interface ContestPayload {
  title: string;
  slug: string;
  description: string;
  /** ISO-8601 with an offset. */
  startAt: string;
  endAt: string;
  /**
   * Whether the contest should move ratings.
   *
   * <p>Defaults to false on the server when omitted, and is refused outright once the
   * contest has started.
   */
  rated: boolean;
}

/** True while a contest is accepting submissions, by the status the server reported. */
export function isLive(status: ContestStatus): boolean {
  return status === 'LIVE';
}

export function isOver(status: ContestStatus): boolean {
  return status === 'ENDED' || status === 'CANCELLED';
}
