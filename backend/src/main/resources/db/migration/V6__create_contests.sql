-- Contests, participation and contest submissions.
--
-- THE CENTRAL DESIGN DECISION: STATUS IS DERIVED, NOT STORED
--
-- `lifecycle` holds only what a human decided: DRAFT (being written), PUBLISHED (released
-- to users) or CANCELLED (called off). The status users actually see -- UPCOMING, LIVE,
-- ENDED -- is computed from that plus start_at, end_at and the current time.
--
-- The alternative, a stored status advanced by a scheduled job, has a failure mode that
-- matters: if the application is down at end_at, or the job is late, or the clock skews,
-- the stored value says LIVE when the contest is over and submissions are accepted that
-- should not be. Deriving it means a contest ends on time even if nothing is running to
-- notice, and a restart cannot resurrect a finished contest. See ADR-032.
--
-- TIME
--
-- Every timestamp is TIMESTAMPTZ and every comparison is against now() in UTC. The
-- window is HALF-OPEN: start_at <= t < end_at. At exactly start_at a contest is LIVE; at
-- exactly end_at it is ENDED and submissions are refused. There is no instant belonging
-- to both states and none belonging to neither.

CREATE TABLE contests (
    id            BIGSERIAL    PRIMARY KEY,
    public_id     UUID         NOT NULL DEFAULT gen_random_uuid(),

    title         VARCHAR(200) NOT NULL,
    -- The public URL identifier, as with problems: stable, readable, and not an integer
    -- that invites enumeration.
    slug          VARCHAR(200) NOT NULL,
    description   TEXT,

    -- Stored in UTC. The API emits ISO-8601 with an offset and the browser renders local
    -- time; no contest rule ever consults a server's local zone.
    start_at      TIMESTAMPTZ  NOT NULL,
    end_at        TIMESTAMPTZ  NOT NULL,

    -- What a human decided. NOT the status a user sees.
    lifecycle     VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    created_by    BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_contests_public_id UNIQUE (public_id),

    CONSTRAINT fk_contests_creator FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT ck_contests_lifecycle CHECK (lifecycle IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT ck_contests_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_contests_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    -- A contest that ends before it starts is not a schedule, and no amount of application
    -- validation is worth as much as the database refusing to hold one.
    CONSTRAINT ck_contests_window CHECK (end_at > start_at)
);

-- Case-insensitive uniqueness on the slug, matching how problems and usernames do it
-- (ADR-012): a functional index rather than a CITEXT column, because Hibernate validates
-- CITEXT as an unknown type.
CREATE UNIQUE INDEX uq_contests_slug_lower ON contests (lower(slug));

-- The catalogue's access path: published contests ordered by when they start.
CREATE INDEX ix_contests_lifecycle_start ON contests (lifecycle, start_at DESC);


-- ---------------------------------------------------------------------------------------
-- The problems in a contest.
--
-- A REFERENCE, not a copy. The problem's statement, examples and test cases stay in their
-- own tables; this table adds only what is true of the problem *within this contest*: where
-- it appears, what it is worth, and the label contestants call it by.
--
-- Copying the problem would create a second version of the answer key to keep in step, and
-- would mean a correction to a statement never reached the contest using it.
-- ---------------------------------------------------------------------------------------
CREATE TABLE contest_problems (
    id            BIGSERIAL    PRIMARY KEY,
    contest_id    BIGINT       NOT NULL,
    problem_id    BIGINT       NOT NULL,

    -- Position in the contest, 0-based. Contestants see a letter derived from it (A, B, C)
    -- rather than the problem's real title ordering.
    display_order INTEGER      NOT NULL,
    points        INTEGER      NOT NULL DEFAULT 100,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_contest_problems_contest FOREIGN KEY (contest_id)
        REFERENCES contests (id) ON DELETE CASCADE,
    -- RESTRICT, deliberately: a problem used by a contest cannot be deleted out from under
    -- it. The contest's history would otherwise reference a problem that no longer exists.
    CONSTRAINT fk_contest_problems_problem FOREIGN KEY (problem_id)
        REFERENCES problems (id) ON DELETE RESTRICT,

    -- The same problem cannot appear twice in one contest.
    CONSTRAINT uq_contest_problems_problem UNIQUE (contest_id, problem_id),
    -- Ordering is deterministic because the database refuses to hold two problems in the
    -- same slot, rather than because a query happens to sort consistently.
    CONSTRAINT uq_contest_problems_order UNIQUE (contest_id, display_order),

    CONSTRAINT ck_contest_problems_points CHECK (points > 0),
    CONSTRAINT ck_contest_problems_order CHECK (display_order >= 0)
);

CREATE INDEX ix_contest_problems_contest ON contest_problems (contest_id, display_order);


-- ---------------------------------------------------------------------------------------
-- Who is taking part.
--
-- Registration is a row. The unique constraint below is what actually prevents a double
-- registration -- not an application check, which two concurrent requests can both pass.
-- ---------------------------------------------------------------------------------------
CREATE TABLE contest_participants (
    id             BIGSERIAL   PRIMARY KEY,
    contest_id     BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_contest_participants_contest FOREIGN KEY (contest_id)
        REFERENCES contests (id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_participants_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,

    -- The real guard against duplicate registration.
    CONSTRAINT uq_contest_participants UNIQUE (contest_id, user_id)
);

CREATE INDEX ix_contest_participants_user ON contest_participants (user_id, registered_at DESC);


-- ---------------------------------------------------------------------------------------
-- Contest context on submissions.
--
-- NULLABLE, and null means practice. Every submission made before this migration is a
-- practice submission, which is exactly what a null column gives them -- no backfill, no
-- ambiguity, and the existing judging pipeline is untouched.
--
-- ON DELETE RESTRICT: a contest that has submissions cannot be deleted. Contest history is
-- a record of what people did and stays durable; cancelling is a lifecycle change, not a
-- deletion. See ADR-035.
-- ---------------------------------------------------------------------------------------
ALTER TABLE submissions
    ADD COLUMN contest_id BIGINT,
    ADD CONSTRAINT fk_submissions_contest FOREIGN KEY (contest_id)
        REFERENCES contests (id) ON DELETE RESTRICT;

-- The standings query's access path. Every scoring read is "the submissions for this
-- contest", and it wants them grouped by user and problem with the earliest accepted one
-- first -- so the index carries created_at rather than making the planner sort.
--
-- Partial, on contest_id IS NOT NULL: practice submissions vastly outnumber contest ones
-- and none of them belong in this index.
CREATE INDEX ix_submissions_contest_scoring
    ON submissions (contest_id, user_id, problem_id, created_at)
    WHERE contest_id IS NOT NULL;

-- "My submissions in this contest", newest first, for the contest submission list.
CREATE INDEX ix_submissions_contest_user_created
    ON submissions (contest_id, user_id, created_at DESC)
    WHERE contest_id IS NOT NULL;
