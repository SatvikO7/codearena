interface Props {
  page: number;
  totalPages: number;
  totalItems: number;
  hasNext: boolean;
  hasPrevious: boolean;
  onChange: (page: number) => void;
}

/** Previous/next paging. Pages are zero-based on the wire and one-based on screen. */
export function Pagination({ page, totalPages, totalItems, hasNext, hasPrevious, onChange }: Props) {
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
        Page {page + 1} of {totalPages} &middot; {totalItems} problem{totalItems === 1 ? '' : 's'}
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
