-- Submissions, and the judging pipeline's durable state.
--
-- This table is deliberately also the transactional outbox. Creating a submission and
-- recording "this needs to reach the queue" happen in one INSERT, so there is no window in
-- which a submission exists but its intent to be judged does not. `enqueued_at` is the
-- publication marker: NULL means the Redis push has not been confirmed, and the recovery
-- sweeper republishes anything still unpublished or gone stale.
--
-- A separate outbox table would have been the textbook shape. It was rejected because it
-- creates a second row describing the same fact, which then has to be kept consistent with
-- the first -- and because the brief explicitly wants PostgreSQL to stay the single source
-- of truth rather than the queue. One row, one truth, one lifecycle. See ADR-018.
CREATE TABLE submissions (
    id            BIGSERIAL    PRIMARY KEY,
    public_id     UUID         NOT NULL DEFAULT gen_random_uuid(),

    problem_id    BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,

    language      VARCHAR(16)  NOT NULL,
    -- Source is stored as TEXT and never interpreted. It is written to a file inside a
    -- sandbox container and compiled there; nothing concatenates it into a command, and
    -- the size ceiling is enforced by the API before it reaches here.
    source_code   TEXT         NOT NULL,

    status        VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',

    -- Judging results. All NULL until a worker finishes.
    tests_total   INTEGER,
    tests_passed  INTEGER,
    runtime_ms    INTEGER,
    memory_kb     INTEGER,
    -- Compiler diagnostics or a short runtime message, already truncated and stripped of
    -- host paths by the worker. Never contains a hidden test's input or expected output.
    error_message TEXT,
    -- Zero-based index of the first failing test, for "failed on test 3" without ever
    -- disclosing what test 3 contains.
    failed_test_index INTEGER,

    -- Pipeline bookkeeping.
    -- enqueued_at: when the Redis push was confirmed. NULL = pending publication.
    enqueued_at   TIMESTAMPTZ,
    -- claimed_by / claimed_at: the worker holding the lease, and when it took it. The
    -- sweeper treats a claim older than the lease timeout as a dead worker.
    claimed_by    VARCHAR(64),
    claimed_at    TIMESTAMPTZ,
    -- How many times a worker has claimed this submission. Bounded, so a submission that
    -- repeatedly kills its worker cannot cycle forever.
    attempts      INTEGER      NOT NULL DEFAULT 0,

    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_submissions_public_id UNIQUE (public_id),

    -- RESTRICT, not CASCADE. A submission is a historical record of something a person
    -- did; deleting a problem or an account must not silently erase the evidence. If a
    -- deletion is ever genuinely wanted, it has to deal with the submissions explicitly.
    CONSTRAINT fk_submissions_problem FOREIGN KEY (problem_id)
        REFERENCES problems (id) ON DELETE RESTRICT,
    CONSTRAINT fk_submissions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT ck_submissions_language CHECK (language IN ('CPP', 'JAVA', 'PYTHON')),
    CONSTRAINT ck_submissions_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'ACCEPTED', 'WRONG_ANSWER', 'COMPILATION_ERROR',
        'RUNTIME_ERROR', 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED', 'SYSTEM_ERROR')),
    CONSTRAINT ck_submissions_source_not_blank CHECK (btrim(source_code) <> ''),
    CONSTRAINT ck_submissions_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_submissions_counts CHECK (
        (tests_total IS NULL OR tests_total >= 0)
        AND (tests_passed IS NULL OR tests_passed >= 0)
        AND (tests_passed IS NULL OR tests_total IS NULL OR tests_passed <= tests_total))
);

-- "My submissions, newest first" — the user's history page. DESC matches the read order so
-- PostgreSQL walks the index rather than sorting.
CREATE INDEX ix_submissions_user_created ON submissions (user_id, created_at DESC);

-- "Submissions for this problem, newest first", and the per-problem filter on the history
-- page.
CREATE INDEX ix_submissions_problem_created ON submissions (problem_id, created_at DESC);

-- The recovery sweeper's two queries: unpublished or stale QUEUED rows, and RUNNING rows
-- whose lease has expired. Partial, because terminal rows are the overwhelming majority
-- and the sweeper never looks at them — indexing them would cost write throughput on every
-- completed judgement for nothing.
CREATE INDEX ix_submissions_pending ON submissions (status, enqueued_at)
    WHERE status = 'QUEUED';
CREATE INDEX ix_submissions_claimed ON submissions (status, claimed_at)
    WHERE status = 'RUNNING';

COMMENT ON TABLE  submissions IS
    'Submitted solutions and their judging state. Also acts as the transactional outbox: enqueued_at NULL means the queue publication is still pending.';
COMMENT ON COLUMN submissions.source_code IS
    'Untrusted user input. Written to a file inside an isolated container; never interpreted, logged, or placed on a command line.';
COMMENT ON COLUMN submissions.error_message IS
    'Sanitised compiler or runtime output. Must never contain hidden test data or host paths.';
