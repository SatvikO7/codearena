import type { ProblemStatus } from '../types/problem';

export function StatusBadge({ status }: { status: ProblemStatus }) {
  return <span className={`badge badge--${status.toLowerCase()}`}>{status}</span>;
}
