interface Props {
  page: number;
  totalPages: number;
  totalItems: number;
  hasNext: boolean;
  hasPrevious: boolean;
  onChange: (page: number) => void;
  /**
   * What is being counted, singular.
   *
   * <p>Defaults to "problem" because that is where this component started. A list that
   * counts something else passes its own noun rather than reading "50 problems" under a
   * table of competitors.
   */
  noun?: string;
}

/** Previous/next paging. Pages are zero-based on the wire and one-based on screen. */
export function Pagination({
  page,
  totalPages,
  totalItems,
  hasNext,
  hasPrevious,
  onChange,
  noun = 'problem',
}: Props) {
  if (totalItems === 0) {
    return null;
  }

  return (
    <nav className="pagination" aria-label="Pagination">
      <button
        type="button"
        className="button button--quiet"
        onClick={() => onChange(page - 1)}
        disabled={!hasPrevious}
      >
        Previous
      </button>
      <span className="pagination-status" aria-live="polite">
        Page {page + 1} of {totalPages} &middot; {totalItems} {noun}{totalItems === 1 ? '' : 's'}
      </span>
      <button
        type="button"
        className="button button--quiet"
        onClick={() => onChange(page + 1)}
        disabled={!hasNext}
      >
        Next
      </button>
    </nav>
  );
}
