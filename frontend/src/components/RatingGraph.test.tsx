import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RatingGraph } from './RatingGraph';
import type { ProgressionPoint } from '../types/rating';

function point(rating: number, iso: string, title = 'A contest'): ProgressionPoint {
  return { at: iso, rating, contestTitle: title };
}

describe('RatingGraph', () => {
  /**
   * An empty graph must say why it is empty. A blank rectangle is indistinguishable from a
   * broken one, and the honest answer — "no rated contests yet" — is also the useful one.
   */
  it('explains itself when there is nothing to plot', () => {
    render(<RatingGraph points={[]} />);

    expect(screen.getByText(/No rated contests yet/)).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('describes the whole series to a screen reader', () => {
    render(
      <RatingGraph
        points={[
          point(1500, '2026-01-01T12:00:00Z'),
          point(1540, '2026-02-01T12:00:00Z'),
          point(1522, '2026-03-01T12:00:00Z'),
        ]}
      />,
    );

    const figure = screen.getByRole('img');
    expect(figure).toHaveAttribute(
      'aria-label',
      'Rating over 3 rated contests, from 1500 to 1522',
    );
  });

  /**
   * One rating is not a trend. Joining a single point to nothing would draw a shape the data
   * does not support, so a lone contest is a dot.
   */
  it('draws a point and no line for a single contest', () => {
    const { container } = render(<RatingGraph points={[point(1500, '2026-01-01T12:00:00Z')]} />);

    expect(container.querySelector('polyline')).toBeNull();
    expect(container.querySelectorAll('circle')).toHaveLength(1);
  });

  it('draws one point per contest, joined by a line', () => {
    const { container } = render(
      <RatingGraph
        points={[
          point(1500, '2026-01-01T12:00:00Z'),
          point(1600, '2026-02-01T12:00:00Z'),
          point(1550, '2026-03-01T12:00:00Z'),
          point(1700, '2026-04-01T12:00:00Z'),
        ]}
      />,
    );

    expect(container.querySelectorAll('circle')).toHaveLength(4);
    expect(container.querySelector('polyline')).not.toBeNull();
  });

  /**
   * A flat history would make the scale zero-height and divide by zero. It has to render a
   * real, readable line through the middle instead of NaN coordinates.
   */
  it('survives a competitor whose rating has never moved', () => {
    const { container } = render(
      <RatingGraph
        points={[
          point(1500, '2026-01-01T12:00:00Z'),
          point(1500, '2026-02-01T12:00:00Z'),
          point(1500, '2026-03-01T12:00:00Z'),
        ]}
      />,
    );

    const polyline = container.querySelector('polyline');
    expect(polyline?.getAttribute('points')).not.toMatch(/NaN/);
    expect(container.querySelectorAll('circle')).toHaveLength(3);
  });

  /**
   * The axis is fitted to the data rather than starting at zero, because ratings live in a
   * narrow band and a zero-based axis would flatten every real movement. That is a defensible
   * choice only if the page says so, so the caption is part of the contract.
   */
  it('states that the vertical scale does not start at zero', () => {
    render(
      <RatingGraph
        points={[point(1500, '2026-01-01T12:00:00Z'), point(1600, '2026-02-01T12:00:00Z')]}
      />,
    );

    expect(screen.getByText(/rather than starting at zero/)).toBeInTheDocument();
  });

  it('handles a negative rating without breaking the scale', () => {
    // There is no rating floor on the server, so this is reachable, if rare.
    const { container } = render(
      <RatingGraph
        points={[point(40, '2026-01-01T12:00:00Z'), point(-20, '2026-02-01T12:00:00Z')]}
      />,
    );

    expect(container.querySelector('polyline')?.getAttribute('points')).not.toMatch(/NaN/);
  });
});
