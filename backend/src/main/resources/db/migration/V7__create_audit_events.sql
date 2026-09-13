-- The audit log: an append-only record of security-sensitive and administrative events.
--
-- WHAT THIS IS NOT
--
-- It is not an event store. CodeArena's authoritative state stays in the domain tables --
-- users, problems, contests, submissions -- and nothing is ever reconstructed from this
-- table. An audit event describes something that happened; it is not the thing itself.
-- Deleting every row here would lose the history of who did what and change no user's
-- password, no problem's status and no contest's standings. See ADR-036.
--
-- APPEND-ONLY
--
-- There is no UPDATE or DELETE path in the application, and the trigger at the foot of
-- this migration refuses both at the database level. An audit log an administrator can
-- quietly edit is not an audit log -- the one person most worth holding to account is the
-- one with the most access. See ADR-037.
--
-- NO CLIENT INPUT
--
-- Every column is written by the server. There is no request body anywhere in the API that
-- contributes an actor, an action, an outcome, a timestamp or a metadata value, so none of
-- them can be forged by a caller.

CREATE TABLE audit_events (
    id              BIGSERIAL    PRIMARY KEY,
    public_id       UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- Written by the database, never by the application and certainly never by a client.
    -- DEFAULT now() rather than a value the service supplies: a clock the caller cannot
    -- reach is the point of an audit timestamp.
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Who, by the user's PUBLIC id rather than its internal one.
    --
    -- Two reasons. The authenticated principal already carries the public id, so writing an
    -- audit row needs no lookup -- and these rows are written on paths that must stay fast.
    -- And the audit API returns this value directly: the project never exposes users.id,
    -- so storing it here would mean translating on every read.
    --
    -- NULL for ANONYMOUS and SYSTEM actors, and for a failed login where the account could
    -- not be identified -- deliberately nullable rather than pointing at a sentinel row that
    -- would look like a real user in every join.
    --
    -- NOT a foreign key, for the same reason entity_id is not one: the record must outlive
    -- the thing it describes. A foreign key here would have to do something when a user is
    -- deleted, and every option is wrong -- CASCADE erases the history of what they did,
    -- and SET NULL is an UPDATE, which the append-only trigger below refuses outright. An
    -- audit row is a statement about the past; deleting an account does not change what
    -- that account did.
    actor_user_id   UUID,
    -- Denormalised at write time. An audit record has to stay readable after the account is
    -- renamed or removed, and a join that returns nothing is not an answer to "who did this".
    actor_username  VARCHAR(150),
    actor_type      VARCHAR(16)  NOT NULL,

    action          VARCHAR(64)  NOT NULL,
    outcome         VARCHAR(16)  NOT NULL,

    -- What was acted on. entity_id is the public UUID or slug as text, not a foreign key:
    -- the audit record must survive the entity's deletion, which a foreign key would
    -- actively prevent.
    entity_type     VARCHAR(32),
    entity_id       VARCHAR(200),

    -- Correlates this event with the application log lines from the same request.
    request_id      VARCHAR(64),

    -- Structured, bounded, and curated per action. Never a serialised request body; see
    -- AuditMetadata for the rules and the redaction that enforces them.
    metadata        JSONB,

    CONSTRAINT uq_audit_events_public_id UNIQUE (public_id),


    CONSTRAINT ck_audit_events_actor_type CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'ANONYMOUS')),
    CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_audit_events_action_not_blank CHECK (btrim(action) <> ''),

    -- A bound on metadata size, enforced here as well as in the application. Audit rows are
    -- written on paths that must stay fast and are never deleted, so an unbounded blob is a
    -- permanent cost; 4 kB is far more than any curated metadata map needs.
    CONSTRAINT ck_audit_events_metadata_size CHECK (
        metadata IS NULL OR pg_column_size(metadata) <= 4096
    )
);

-- ---------------------------------------------------------------------------------------
-- Indexes, chosen from the queries the audit API actually issues.
--
-- Every one of them leads with the filter and ends with occurred_at DESC, because the API's
-- default ordering is newest-first and every filter is combined with it.
-- ---------------------------------------------------------------------------------------

-- The unfiltered view, which is the one an administrator opens first.
CREATE INDEX ix_audit_events_time ON audit_events (occurred_at DESC);

-- "What has this person been doing?" -- by public id, which is what the API filters on.
CREATE INDEX ix_audit_events_actor ON audit_events (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

-- "Show me every publish", "show me every failed login".
CREATE INDEX ix_audit_events_action ON audit_events (action, occurred_at DESC);

-- "What has happened to this contest?" -- the history of one entity.
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id, occurred_at DESC)
    WHERE entity_type IS NOT NULL;

-- Partial, on the interesting outcomes only. SUCCESS is the overwhelming majority and a
-- full index on outcome would mostly be a slower table scan; DENIED and FAILURE are the
-- rows somebody investigating an incident actually wants.
CREATE INDEX ix_audit_events_bad_outcomes ON audit_events (outcome, occurred_at DESC)
    WHERE outcome <> 'SUCCESS';


-- ---------------------------------------------------------------------------------------
-- Append-only, enforced by the database.
--
-- The application has no update or delete path, but "the code does not do that" is a weaker
-- guarantee than "the database refuses". This is the table whose integrity matters most
-- precisely when someone with database access is the problem.
--
-- A future retention policy would be a deliberate, separately governed operation that drops
-- the trigger, prunes, and restores it -- not an ordinary DELETE that happens to be allowed.
-- See ADR-037 and docs/audit.md.
-- ---------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_events_are_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_are_append_only();

CREATE TRIGGER trg_audit_events_no_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_are_append_only();
