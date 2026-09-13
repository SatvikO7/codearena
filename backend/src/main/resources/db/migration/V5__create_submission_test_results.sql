-- Per-test judging results.
--
-- Phase 4 stored only the aggregate: how many tests passed, and the index of the first
-- failure. That is enough to decide a verdict and not enough to explain one. A solver
-- looking at WRONG_ANSWER wants to know whether they failed the first test or the last,
-- and how close to the time limit the passing ones ran.
--
-- WHAT THIS TABLE DELIBERATELY DOES NOT HOLD
-- No input, no expected output, no actual output. Not "stored but filtered on read" --
-- there is no column for them, so no query, projection or future refactor can leak them.
-- For a hidden test the expected output is the answer key, and an endpoint that returned
-- per-test diffs would hand it out one submission at a time. `hidden` is copied from the
-- test case so the UI can label a row "Hidden test 3" without saying anything about it.
CREATE TABLE submission_test_results (
    id            BIGSERIAL   PRIMARY KEY,
    submission_id BIGINT      NOT NULL,

    -- Zero-based, matching problem_test_cases.position, so "test 3" means the same thing
    -- in the verdict, in this table and on the problem.
    position      INTEGER     NOT NULL,
    passed        BOOLEAN     NOT NULL,

    -- Wall-clock for this test alone. NULL when the test never ran, which is the normal
    -- case for every test after the first failure: judging stops there, and recording a
    -- zero would claim they ran and passed instantly.
    runtime_ms    INTEGER,

    -- Mirrors problem_test_cases.hidden at judging time. Denormalised on purpose: a
    -- result is a historical record, and an admin later un-hiding a test case must not
    -- retroactively change what an old submission's results were allowed to show.
    hidden        BOOLEAN     NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- CASCADE here, unlike submissions themselves. A test result has no meaning without
    -- its submission, so it is a component of that record rather than history in its own
    -- right; submissions remain RESTRICT-protected against problem and user deletion.
    CONSTRAINT fk_submission_test_results_submission FOREIGN KEY (submission_id)
        REFERENCES submissions (id) ON DELETE CASCADE,

    CONSTRAINT uq_submission_test_results_position UNIQUE (submission_id, position),
    CONSTRAINT ck_submission_test_results_position CHECK (position >= 0),
    CONSTRAINT ck_submission_test_results_runtime CHECK (runtime_ms IS NULL OR runtime_ms >= 0)
);

-- The only access path: "every result for this submission, in test order".
CREATE INDEX ix_submission_test_results_submission
    ON submission_test_results (submission_id, position);


-- "My submissions with this verdict, newest first" -- the history page's verdict filter.
--
-- Only one index is added. The existing ix_submissions_user_created already serves the
-- unfiltered history and the problem filter well, because a single user's submissions are
-- a bounded slice however large the table grows. The language filter has three possible
-- values, so an index on it would be poorly selective and would cost a write on every
-- judged submission to save a filter step on a handful of rows.
CREATE INDEX ix_submissions_user_status_created
    ON submissions (user_id, status, created_at DESC);

COMMENT ON TABLE submission_test_results IS
    'Per-test outcomes. Holds no input, expected output or actual output: there is deliberately no column capable of carrying hidden test data.';
