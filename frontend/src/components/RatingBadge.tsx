import { bandFor, changeDirection, formatChange } from '../types/rating';

interface RatingProps {
  rating: number | null;
  /** Shows the band name beside the number. Off in dense tables. */
  showBand?: boolean;
}

/**
 * A rating, with its band.
 *
 * <p>A null rating renders as "Unrated" rather than as a number. That distinction is the
 * whole reason the field is nullable: a competitor who has never entered a rated contest has
 * no rating, and showing them at 1500 would be reporting a result they have not earned.
 *
 * <p>The band name is always present as text next to the colour. Roughly one man in twelve
 * cannot reliably separate the colours a rating scale conventionally uses, and a scale that
 * exists only as colour tells them nothing — the same reasoning as the verdict badges.
 */
export function RatingBadge({ rating, showBand = true }: RatingProps) {
  const band = bandFor(rating);

  if (rating === null || band === null) {
    return (
      <span className="rating-badge rating-badge--unrated">
        <span className="rating-badge__value">Unrated</span>
      </span>
    );
  }

  return (
    <span className={`rating-badge ${band.className}`}>
      <span className="rating-badge__value">{rating}</span>
      {showBand && <span className="rating-badge__band">{band.name}</span>}
    </span>
  );
}

interface ChangeProps {
  change: number;
}

/**
 * One contest's rating movement.
 *
 * <p>Zero is shown as "+0" and styled as its own thing, neither a gain nor a loss. It is a
 * real result — the contest went exactly as the ratings expected — and rendering it as a
 * blank or a bare zero would read as missing data.
 *
 * <p>The arrow is decorative; the sign in the text carries the meaning.
 */
export function RatingChangeBadge({ change }: ChangeProps) {
  const direction = changeDirection(change);
  const glyph = direction === 'up' ? '▲' : direction === 'down' ? '▼' : '—';

  return (
    <span className={`rating-change rating-change--${direction}`}>
      <span aria-hidden="true">{glyph}</span> {formatChange(change)}
    </span>
  );
}
