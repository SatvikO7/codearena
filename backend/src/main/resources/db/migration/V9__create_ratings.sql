-- =============================================================================
-- V9 — competitive ratings, rating history and contest finalisation
--
-- Turns contest performance into a persistent competitive profile. Three
-- additions, each with one job:
--
--   contests        gains a rated flag and a finalisation claim
--   user_ratings    one row per user who has been rated at least once
--   contest_rating_changes   the immutable record of every rating movement
--
-- The shape of this follows the two patterns the project has already settled on:
-- the append-only trigger from audit_events (V7), and the atomic conditional
-- claim from the submission queue (V4).
-- =============================================================================


-- =============================================================================
-- contests: rated, and the finalisation claim
-- =============================================================================

-- Whether this contest moves ratings.
--
-- DEFAULT FALSE, and that is a migration decision rather than a preference. Every
-- contest that already exists was created, run and judged with no notion of
-- rating, and nobody who entered one consented to it affecting a competitive
-- record that did not exist at the time. Making them rated retroactively would
-- invent history; making them unrated is simply the truth about what they were.
ALTER TABLE contests ADD COLUMN rated BOOLEAN NOT NULL DEFAULT FALSE;

-- When rating finalisation was CLAIMED for this contest.
--
-- This is the concurrency control, not a report. Finalisation begins with
--
--   UPDATE contests SET rating_finalized_at = now()
--    WHERE id = ? AND rating_finalized_at IS NULL
--
-- which exactly one transaction can win, because PostgreSQL serialises the row
-- update. Every other caller updates zero rows, learns it lost, and returns the
-- already-finalised state. The same idea as claiming a submission in V4: the
-- database decides who goes, so two application instances cannot both proceed.
--
-- NULL means "not finalised". An unrated contest is finalised too -- it simply
-- produces no rating changes -- so this field answers "has finalisation run"
-- and the `rated` flag answers "did it change anything".
ALTER TABLE contests ADD COLUMN rating_finalized_at TIMESTAMPTZ;

-- How many participants received a rating change. NULL until finalised; 0 for an
-- unrated contest, or a rated one with too few participants to rate.
ALTER TABLE contests ADD COLUMN rated_participant_count INTEGER;

ALTER TABLE contests ADD CONSTRAINT ck_contests_rated_count_with_finalisation
    CHECK ( (rating_finalized_at IS NULL AND rated_participant_count IS NULL)
         OR (rating_finalized_at IS NOT NULL AND rated_participant_count IS NOT NULL) );

-- Finds the work for the finalisation sweeper: contests that have ended, are
-- published and rated, and have not been finalised. Partial, so it indexes only
-- the handful of contests that are actually outstanding rather than every contest
-- that has ever run.
CREATE INDEX ix_contests_awaiting_finalisation
    ON contests (end_at)
    WHERE rating_finalized_at IS NULL AND rated = TRUE AND lifecycle = 'PUBLISHED';

COMMENT ON COLUMN contests.rated IS
    'Whether this contest moves ratings. Frozen once the contest starts.';
COMMENT ON COLUMN contests.rating_finalized_at IS
    'Finalisation claim. Set by a conditional UPDATE that only one transaction can win.';


-- =============================================================================
-- user_ratings: the current competitive standing of one user
-- =============================================================================
--
-- A separate table rather than columns on `users`, for two reasons. First, a row
-- here means "this account has competed in a rated contest" -- an unrated user has
-- no row at all, rather than a default value that cannot be told apart from a real
-- one. Second, the global ranking reads this table and only this table, so it
-- never touches password hashes or email addresses.
CREATE TABLE user_ratings (
    id                     BIGSERIAL   PRIMARY KEY,

    user_id                BIGINT      NOT NULL,

    -- The current rating. Integer: a rating is displayed, compared and ranked, and
    -- none of those are improved by fractional precision. The arithmetic is done in
    -- double and rounded once, at the end -- see RatingCalculator.
    rating                 INTEGER     NOT NULL,

    -- The highest rating ever held. Never decreases, which is the point of it.
    peak_rating            INTEGER     NOT NULL,

    -- How many rated contests have contributed. Drives the K-factor, so it is part
    -- of the calculation rather than merely a statistic.
    contests_rated         INTEGER     NOT NULL DEFAULT 0,

    last_rated_contest_id  BIGINT,
    last_rated_at          TIMESTAMPTZ,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_user_ratings_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_ratings_last_contest
        FOREIGN KEY (last_rated_contest_id) REFERENCES contests (id) ON DELETE SET NULL,

    -- One rating per user. The whole model depends on it.
    CONSTRAINT uq_user_ratings_user UNIQUE (user_id),

    CONSTRAINT ck_user_ratings_peak_not_below_current CHECK (peak_rating >= rating),
    CONSTRAINT ck_user_ratings_contests_non_negative CHECK (contests_rated >= 0)
);

-- The global ranking: ORDER BY rating DESC, then the deterministic tie-breaks.
-- Every column the ranking orders by is here, so the ordering is an index scan
-- rather than a sort of the whole table.
CREATE INDEX ix_user_ratings_leaderboard
    ON user_ratings (rating DESC, contests_rated DESC, user_id ASC);

COMMENT ON TABLE user_ratings IS
    'Current competitive standing. A row exists only once a user has been rated.';


-- =============================================================================
-- contest_rating_changes: the immutable history
-- =============================================================================
--
-- Append-only, for the same reason audit_events is (V7): this is the record that
-- explains why somebody''s rating is what it is. A system that can quietly rewrite
-- it cannot be used to settle an argument, which is most of what it is for.
--
-- It is also the derivation of the current rating: user_ratings.rating is a
-- cached fold over these rows, and an invariant test asserts the two agree.
CREATE TABLE contest_rating_changes (
    id                 BIGSERIAL   PRIMARY KEY,
    public_id          UUID        NOT NULL DEFAULT gen_random_uuid(),

    user_id            BIGINT      NOT NULL,
    contest_id         BIGINT      NOT NULL,

    rating_before      INTEGER     NOT NULL,
    rating_after       INTEGER     NOT NULL,
    rating_change      INTEGER     NOT NULL,

    -- The contest result this rating movement was computed from. Copied rather
    -- than joined: standings are recomputed from submissions on every request, and
    -- a later correction to a submission must not silently rewrite the inputs of a
    -- rating change that has already been applied. This is what the rating was
    -- based on, permanently.
    rank               INTEGER     NOT NULL,
    participant_count  INTEGER     NOT NULL,
    score              INTEGER     NOT NULL,
    penalty            INTEGER     NOT NULL,

    -- The expected and actual pairwise scores the calculator used, kept so a
    -- rating change can be re-derived and explained without re-running a contest.
    expected_score     DOUBLE PRECISION NOT NULL,
    actual_score       DOUBLE PRECISION NOT NULL,
    k_factor           INTEGER     NOT NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- No foreign keys, and for the same reason audit_events has none (ADR-036): a
    -- record must outlive the thing it describes. A foreign key has to DO something
    -- when its target disappears, and every option is wrong here -- CASCADE erases
    -- exactly the history worth keeping, and SET NULL is an UPDATE, which the
    -- append-only trigger below refuses. That is not hypothetical: it is precisely the
    -- collision that forced the same decision for the audit log.
    --
    -- The cost is that a deleted user leaves dangling rows. There is no user deletion
    -- in this system, and the rows are keyed on ids that are never reused, so they are
    -- unreachable rather than misleading.
    CONSTRAINT uq_rating_changes_public_id UNIQUE (public_id),

    -- The duplicate-prevention constraint, and the second line of defence behind
    -- the finalisation claim. Even if two transactions somehow both believed they
    -- had won the claim, this makes a double rating physically impossible rather
    -- than merely unlikely.
    CONSTRAINT uq_rating_changes_contest_user UNIQUE (contest_id, user_id),

    -- The arithmetic must hold in the row itself, so a bug that computed the three
    -- numbers inconsistently fails at the INSERT rather than appearing on a profile.
    CONSTRAINT ck_rating_changes_arithmetic
        CHECK (rating_after = rating_before + rating_change),
    CONSTRAINT ck_rating_changes_rank_positive CHECK (rank >= 1),
    CONSTRAINT ck_rating_changes_participants CHECK (participant_count >= rank)
);

-- A user's rating history, newest first: the profile page and the rating graph.
CREATE INDEX ix_rating_changes_user_time
    ON contest_rating_changes (user_id, created_at DESC);

-- Everyone's change for one contest: the contest results page.
CREATE INDEX ix_rating_changes_contest
    ON contest_rating_changes (contest_id);


-- =============================================================================
-- Immutability, enforced by the database
-- =============================================================================
--
-- The same three-layer approach as audit_events: the entity is immutable, no
-- repository method or endpoint mutates, and the database refuses outright. The
-- third is the one that holds when the first two are wrong, and a rating history
-- that can be edited is a rating history nobody has to believe.
--
-- A dedicated function rather than reusing audit_events' one: they are separate
-- concerns that happen to share a rule, and coupling them would mean a change to
-- either dragging the other along.
CREATE OR REPLACE FUNCTION contest_rating_changes_are_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'contest_rating_changes is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rating_changes_no_update
    BEFORE UPDATE ON contest_rating_changes
    FOR EACH ROW EXECUTE FUNCTION contest_rating_changes_are_append_only();

CREATE TRIGGER trg_rating_changes_no_delete
    BEFORE DELETE ON contest_rating_changes
    FOR EACH ROW EXECUTE FUNCTION contest_rating_changes_are_append_only();

COMMENT ON TABLE contest_rating_changes IS
    'Append-only rating history. UPDATE and DELETE are refused by trigger.';
