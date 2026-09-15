import type { ProgressionPoint } from '../types/rating';

interface Props {
  points: ProgressionPoint[];
}

const WIDTH = 720;
const HEIGHT = 240;
const PADDING = { top: 16, right: 16, bottom: 28, left: 44 };

/**
 * A competitor's rating over time.
 *
 * <h2>Hand-drawn SVG, and no charting library</h2>
 * A line through a few dozen points does not justify a dependency. More to the point, the
 * artifact-style CSP this application is served under blocks scripts from arbitrary origins,
 * so a charting library would have to be bundled — trading a hundred lines of SVG for a few
 * hundred kilobytes of JavaScript that does the same thing less specifically.
 *
 * <h2>The axis does not start at zero, and says so</h2>
 * Ratings live in a narrow band — most competitors never leave 1200–1900 — so a zero-based
 * axis would compress every real movement into a flat line. The scale is therefore fitted to
 * the data, which exaggerates small changes, which is exactly what makes the graph readable.
 * Both bounds are labelled so nobody has to guess what they are looking at.
 *
 * <p>A single contest draws a point rather than a line: one rating is not a trend, and
 * joining it to nothing would be drawing a shape the data does not support.
 */
export function RatingGraph({ points }: Props) {
  if (points.length === 0) {
    return (
      <p className="status status--muted">
        No rated contests yet. The graph appears after the first one is finalised.
      </p>
    );
  }

  const ratings = points.map((point) => point.rating);
  const lowest = Math.min(...ratings);
  const highest = Math.max(...ratings);

  // A flat history would give a zero-height scale and divide by zero. Padded to a readable
  // band instead, which draws the (true) horizontal line through the middle.
  const span = highest - lowest;
  const min = span === 0 ? lowest - 25 : lowest - Math.ceil(span * 0.1);
  const max = span === 0 ? highest + 25 : highest + Math.ceil(span * 0.1);

  const plotWidth = WIDTH - PADDING.left - PADDING.right;
  const plotHeight = HEIGHT - PADDING.top - PADDING.bottom;

  const x = (index: number) =>
    points.length === 1
      ? PADDING.left + plotWidth / 2
      : PADDING.left + (index / (points.length - 1)) * plotWidth;

  const y = (rating: number) =>
    PADDING.top + plotHeight - ((rating - min) / (max - min)) * plotHeight;

  const path = points.map((point, index) => `${x(index)},${y(point.rating)}`).join(' ');
  const latest = points[points.length - 1];

  return (
    <figure className="rating-graph">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`Rating over ${points.length} rated contest${points.length === 1 ? '' : 's'}, from ${points[0].rating} to ${latest.rating}`}
        className="rating-graph__svg"
        preserveAspectRatio="xMidYMid meet"
      >
        {/* Axis frame. Drawn rather than styled so the graph is legible on its own. */}
        <line
          x1={PADDING.left}
          y1={PADDING.top}
          x2={PADDING.left}
          y2={PADDING.top + plotHeight}
          className="rating-graph__axis"
        />
        <line
          x1={PADDING.left}
          y1={PADDING.top + plotHeight}
          x2={PADDING.left + plotWidth}
          y2={PADDING.top + plotHeight}
          className="rating-graph__axis"
        />

        <text x={PADDING.left - 8} y={PADDING.top + 4} className="rating-graph__tick" textAnchor="end">
          {max}
        </text>
        <text
          x={PADDING.left - 8}
          y={PADDING.top + plotHeight + 4}
          className="rating-graph__tick"
          textAnchor="end"
        >
          {min}
        </text>

        {points.length > 1 && <polyline points={path} className="rating-graph__line" />}

        {points.map((point, index) => (
          <circle
            key={`${point.at}-${index}`}
            cx={x(index)}
            cy={y(point.rating)}
            r={4}
            className="rating-graph__point"
          >
            {/* The native tooltip: no JavaScript, and it works with the keyboard's focus
                order as well as with a pointer. */}
            <title>
              {point.contestTitle} — {point.rating} on{' '}
              {new Date(point.at).toLocaleDateString()}
            </title>
          </circle>
        ))}
      </svg>

      <figcaption className="rating-graph__caption">
        Rating after each rated contest, oldest first. The vertical scale is fitted to this
        competitor&rsquo;s range ({min}&ndash;{max}) rather than starting at zero.
      </figcaption>
    </figure>
  );
}
