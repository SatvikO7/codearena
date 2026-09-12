-- Problems, their worked examples, their judge test cases and their tags.
--
-- Identifier strategy follows V2: a BIGSERIAL internal key that foreign keys point at,
-- and a UUID public_id that is the only identifier the API exposes. Submissions in a
-- later phase will reference problem(id); the UUID is what travels over HTTP, so a
-- sequential id never leaks how many problems exist.
--
-- Examples and test cases are separate tables rather than JSONB columns. JSONB would be
-- a defensible fit -- both are owned entirely by their problem and replaced wholesale on
-- edit -- but relational rows buy two things that matter here. Per-column NOT NULL and
-- CHECK constraints give the second validation layer the brief asks for, which JSONB
-- cannot express without awkward assertions over JSON paths. And the judge will later
-- read test cases filtered by visibility and ordered by position; that is a query, and
-- queries belong on columns.
CREATE TABLE problems (
    id            BIGSERIAL    PRIMARY KEY,
    public_id     UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- The URL handle. Stable across edits unless an admin changes it deliberately.
    slug          VARCHAR(120) NOT NULL,
    title         VARCHAR(200) NOT NULL,

    -- Long-form authoring content. TEXT rather than VARCHAR(n): statements legitimately
    -- run to thousands of characters and an arbitrary ceiling would only ever be wrong.
    --
    -- Nullable on purpose. A draft is a work in progress: an author must be able to save
    -- a title and come back to the statement tomorrow. Completeness is not a property of
    -- the row, it is a precondition of publication, and that is where it is enforced --
    -- by Problem.publish, which refuses and names every field still missing. Marking
    -- these NOT NULL would make an unfinished draft unsaveable, which is the opposite of
    -- what a draft is for.
    statement     TEXT,
    input_format  TEXT,
    output_format TEXT,
    constraints   TEXT,
    -- Optional editorial note shown after the examples.
    explanation   TEXT,

    difficulty    VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    -- Execution budgets. Carried here because they are properties an author sets while
    -- writing the problem; nothing in this phase enforces them.
    time_limit_ms     INTEGER  NOT NULL DEFAULT 1000,
    memory_limit_mb   INTEGER  NOT NULL DEFAULT 256,

    created_by    BIGINT       NOT NULL,
    updated_by    BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_problems_public_id UNIQUE (public_id),
    CONSTRAINT uq_problems_slug      UNIQUE (slug),

    -- Authors are accounts. RESTRICT rather than CASCADE: deleting a user must never
    -- silently delete the problems they wrote.
    CONSTRAINT fk_problems_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_problems_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT ck_problems_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT ck_problems_status     CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),

    -- Slugs are lower-case, digit-and-hyphen separated, never leading/trailing/doubled
    -- hyphens. Enforced here as well as in the application so that no other write path
    -- can introduce a slug that breaks a URL.
    CONSTRAINT ck_problems_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),

    CONSTRAINT ck_problems_title_not_blank     CHECK (btrim(title) <> ''),
    -- NULL passes a CHECK, so this reads as "if a statement is present it is not blank".
    CONSTRAINT ck_problems_statement_not_blank CHECK (btrim(statement) <> ''),
    CONSTRAINT ck_problems_time_limit   CHECK (time_limit_ms BETWEEN 100 AND 15000),
    CONSTRAINT ck_problems_memory_limit CHECK (memory_limit_mb BETWEEN 16 AND 1024)
);

-- The catalogue listing is always "status = PUBLISHED, ordered deterministically", often
-- narrowed by difficulty. A composite index over exactly that access path lets PostgreSQL
-- satisfy the common query without sorting the whole table.
CREATE INDEX ix_problems_status_difficulty_id ON problems (status, difficulty, id);
-- The admin listing is the same query without the status filter.
CREATE INDEX ix_problems_status_id            ON problems (status, id);

-- Case-insensitive title search. Without this, ILIKE '%term%' degrades to a sequential
-- scan; a trigram index keeps a substring match usable as the catalogue grows.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_problems_title_trgm ON problems USING gin (title gin_trgm_ops);


-- Worked examples shown on the problem page. Public by definition: an example exists to
-- be read. Anything that must stay secret is a test case, not an example.
CREATE TABLE problem_examples (
    id          BIGSERIAL   PRIMARY KEY,
    problem_id  BIGINT      NOT NULL,
    -- Zero-based display order. Unique per problem so ordering is total and stable
    -- rather than dependent on whatever order the database happens to return rows in.
    position    INTEGER     NOT NULL,
    input       TEXT        NOT NULL,
    output      TEXT        NOT NULL,
    explanation TEXT,

    CONSTRAINT fk_problem_examples_problem FOREIGN KEY (problem_id)
        REFERENCES problems (id) ON DELETE CASCADE,
    CONSTRAINT uq_problem_examples_position UNIQUE (problem_id, position),
    CONSTRAINT ck_problem_examples_position CHECK (position >= 0)
);

CREATE INDEX ix_problem_examples_problem ON problem_examples (problem_id, position);


-- Judge test cases.
--
-- `hidden` is the security boundary of this phase. A hidden case's expected_output is
-- the answer key: leaking it would let anyone hard-code their way to an ACCEPTED verdict
-- once judging exists. No user-facing query ever selects from this table; only the admin
-- API and, from Phase 7, the judge worker read it.
CREATE TABLE problem_test_cases (
    id              BIGSERIAL  PRIMARY KEY,
    problem_id      BIGINT     NOT NULL,
    position        INTEGER    NOT NULL,
    input           TEXT       NOT NULL,
    expected_output TEXT       NOT NULL,
    -- Hidden by default. A test case that is secret only because someone remembered to
    -- set a flag is a test case that will eventually be published by accident.
    hidden          BOOLEAN    NOT NULL DEFAULT TRUE,
    -- Relative contribution to a partial score. Unused until judging exists; stored now
    -- because it is authoring data an admin sets while writing the problem.
    weight          INTEGER    NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_problem_test_cases_problem FOREIGN KEY (problem_id)
        REFERENCES problems (id) ON DELETE CASCADE,
    CONSTRAINT uq_problem_test_cases_position UNIQUE (problem_id, position),
    CONSTRAINT ck_problem_test_cases_position CHECK (position >= 0),
    CONSTRAINT ck_problem_test_cases_weight   CHECK (weight BETWEEN 1 AND 100)
);

CREATE INDEX ix_problem_test_cases_problem ON problem_test_cases (problem_id, position);


-- Tags, as a constrained set rather than free text. Free-text tags drift into "dp",
-- "DP", "dynamic-programming" and "dynamicProgramming" describing one concept, which
-- makes filtering useless. The permitted values are fixed here and mirrored by a Java
-- enum; adding one is a migration plus an enum constant.
CREATE TABLE problem_tags (
    problem_id BIGINT      NOT NULL,
    tag        VARCHAR(32) NOT NULL,

    CONSTRAINT pk_problem_tags PRIMARY KEY (problem_id, tag),
    CONSTRAINT fk_problem_tags_problem FOREIGN KEY (problem_id)
        REFERENCES problems (id) ON DELETE CASCADE,
    CONSTRAINT ck_problem_tags_tag CHECK (tag IN (
        'ARRAY', 'STRING', 'HASHING', 'SORTING', 'BINARY_SEARCH', 'TWO_POINTERS',
        'STACK', 'QUEUE', 'LINKED_LIST', 'TREE', 'GRAPH', 'GREEDY',
        'DYNAMIC_PROGRAMMING', 'DSU', 'MATH', 'BIT_MANIPULATION', 'HEAP', 'RECURSION'
    ))
);

-- Supports "every problem carrying tag X", the direction the catalogue filter reads in.
CREATE INDEX ix_problem_tags_tag ON problem_tags (tag, problem_id);

COMMENT ON TABLE problems           IS 'Authored problems. Only PUBLISHED rows are visible to non-admin users.';
COMMENT ON TABLE problem_test_cases IS 'Judge test cases. Rows with hidden = true must never reach a user-facing API response.';
