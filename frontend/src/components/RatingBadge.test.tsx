import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RatingBadge, RatingChangeBadge } from './RatingBadge';
import { RATING_BANDS, bandFor, changeDirection, formatChange } from '../types/rating';

describe('RatingBadge', () => {
  /**
   * The distinction the whole nullable rating exists for. An account that has never entered
   * a rated contest has no rating; rendering 1500, or 0, would report a result it has not
   * earned.
   */
  it('says "Unrated" rather than showing a number when there is no rating', () => {
    render(<RatingBadge rating={null} />);

    expect(screen.getByText('Unrated')).toBeInTheDocument();
    expect(screen.queryByText('1500')).not.toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('shows the rating with its band', () => {
    render(<RatingBadge rating={1750} />);

    expect(screen.getByText('1750')).toBeInTheDocument();
    expect(screen.getByText('Specialist')).toBeInTheDocument();
  });

  it('can drop the band for a dense table', () => {
    render(<RatingBadge rating={1750} showBand={false} />);

    expect(screen.getByText('1750')).toBeInTheDocument();
    expect(screen.queryByText('Specialist')).not.toBeInTheDocument();
  });

  /**
   * Colour must never be the only thing carrying the band. The same reasoning as the
   * submission verdicts: roughly one man in twelve cannot reliably separate the shades a
   * rating palette conventionally uses, so every band has a distinct word too.
   */
  it('names every band in words, and no two share a name', () => {
    const names = new Set(RATING_BANDS.map((band) => band.name));

    expect(names.size).toBe(RATING_BANDS.length);

    for (const band of RATING_BANDS) {
      const { unmount } = render(<RatingBadge rating={band.min} />);
      expect(screen.getByText(band.name)).toBeInTheDocument();
      unmount();
    }
  });
});

describe('bandFor', () => {
  it('has no band for an unrated competitor', () => {
    expect(bandFor(null)).toBeNull();
  });

  it('puts a rating in the band it reaches, at the boundary exactly', () => {
    expect(bandFor(2400)?.name).toBe('Grandmaster');
    expect(bandFor(2399)?.name).toBe('Master');
    expect(bandFor(1600)?.name).toBe('Specialist');
    expect(bandFor(1599)?.name).toBe('Apprentice');
  });

  /**
   * There is no rating floor on the server, so a rating can be negative. The band table must
   * still answer, rather than returning nothing and blanking the page.
   */
  it('bands a negative rating rather than falling through', () => {
    expect(bandFor(-40)?.name).toBe('Novice');
    expect(bandFor(0)?.name).toBe('Novice');
  });
});

describe('RatingChangeBadge', () => {
  it('writes a gain with an explicit plus', () => {
    render(<RatingChangeBadge change={23} />);

    expect(screen.getByText(/\+23/)).toBeInTheDocument();
  });

  it('writes a loss with its minus', () => {
    render(<RatingChangeBadge change={-18} />);

    expect(screen.getByText(/-18/)).toBeInTheDocument();
  });

  /**
   * Zero is a real rating change: the contest went exactly as the ratings predicted. Writing
   * it as "+0" rather than a bare "0" or a blank says that it was computed, rather than
   * looking like a missing value.
   */
  it('writes no movement as +0, not as a blank', () => {
    render(<RatingChangeBadge change={0} />);

    expect(screen.getByText(/\+0/)).toBeInTheDocument();
  });

  it('distinguishes the three directions without colour', () => {
    expect(formatChange(5)).toBe('+5');
    expect(formatChange(-5)).toBe('-5');
    expect(formatChange(0)).toBe('+0');

    expect(changeDirection(5)).toBe('up');
    expect(changeDirection(-5)).toBe('down');
    expect(changeDirection(0)).toBe('level');
  });
});
