import type { Difficulty } from '../types/problem';

/** Difficulty as a coloured chip. Colour is reinforced by the label, never carried by it alone. */
export function DifficultyBadge({ difficulty }: { difficulty: Difficulty }) {
  return <span className={`badge badge--${difficulty.toLowerCase()}`}>{difficulty}</span>;
}
