-- =============================================================================
-- V8 — indexes for the operational status view
--
-- Phase 10 added two queries that a dashboard polls every ten seconds: the
-- pipeline's own error rate, and how many judgements are being retried. Both
-- were sequential scans over `submissions`, which is the table that grows
-- fastest and never shrinks.
--
-- These were added from measured plans, not from a hunch. Against a synthetic
-- table of one million submissions:
--
--   system errors in the last hour   cost 18679  ->     8.4   (parallel seq scan -> index only scan)
--   judgements being retried         cost 16596  ->    56.5   (parallel seq scan -> index only scan)
--
-- Both are PARTIAL indexes, and that is what makes them worth having rather
-- than merely effective. Each one covers a small, self-limiting slice of the
-- table -- the failures, and the submissions that have not settled yet -- so on
-- that same million rows they measured 40 kB and 48 kB against a 21 MB primary
-- key. A full index on `status` would have covered every ACCEPTED row in the
-- system's history to answer a question that is only ever asked about the few
-- that are not.
--
-- The partial predicates also keep the write cost near zero where it matters
-- most: a row leaves `ix_submissions_unsettled` for good the moment it reaches
-- a terminal status, so the index stays the size of the work in flight rather
-- than the size of the archive.
-- =============================================================================

-- The judge's own failure rate. SYSTEM_ERROR is the one verdict that is never
-- the submitter's fault, so a spike here is an incident rather than a hard
-- problem set, and it is the most direct "something is broken" signal the
-- system produces.
CREATE INDEX ix_submissions_system_errors
    ON submissions (finished_at)
    WHERE status = 'SYSTEM_ERROR';

-- Submissions that have not reached a verdict. Answers "how many judgements are
-- being retried", which rises before submissions start exhausting their
-- attempts and failing -- the warning ahead of the incident above.
CREATE INDEX ix_submissions_unsettled
    ON submissions (status, attempts)
    WHERE status IN ('QUEUED', 'RUNNING');

COMMENT ON INDEX ix_submissions_system_errors IS
    'Partial: judge failures only. Feeds the operational status view.';
COMMENT ON INDEX ix_submissions_unsettled IS
    'Partial: submissions still in flight. Feeds retry counts in the status view.';
