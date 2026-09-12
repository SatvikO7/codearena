-- Users and their role assignment.
--
-- Case-insensitive identity is enforced by unique indexes over lower(...) rather than by
-- the CITEXT type. CITEXT would express the intent more directly, but Hibernate reports
-- it as Types#OTHER and then refuses to start under `ddl-auto: validate`. The available
-- workarounds all amount to relaxing schema validation, and that check is worth more
-- than the syntactic nicety: it is what catches an entity mapping drifting away from the
-- migrations. A functional unique index gives exactly the same guarantee -- the database,
-- not the application, rejects "Alice@example.com" when "alice@example.com" exists.
--
-- Two identifiers, deliberately:
--   id         BIGSERIAL, the internal key. Foreign keys from submissions, contests and
--              audit rows point here; a sequential 8-byte key keeps those indexes compact
--              and avoids the write amplification of random UUID keys.
--   public_id  UUID, the only identifier ever exposed by the API. Sequential ids would
--              otherwise leak the user count and let anyone enumerate accounts.
CREATE TABLE users (
    id            BIGSERIAL     PRIMARY KEY,
    public_id     UUID          NOT NULL DEFAULT gen_random_uuid(),
    username      VARCHAR(32)   NOT NULL,
    email         VARCHAR(254)  NOT NULL,
    password_hash TEXT          NOT NULL,
    role          VARCHAR(32)   NOT NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_public_id UNIQUE (public_id),

    -- Length bounds mirror the bean validation rules. The application is the friendly
    -- gatekeeper; these are the backstop for anything reaching the table by another route
    -- (a migration, a script, a future admin tool).
    CONSTRAINT ck_users_username_length CHECK (char_length(username) BETWEEN 3 AND 32),
    CONSTRAINT ck_users_email_length    CHECK (char_length(email) BETWEEN 3 AND 254),

    -- Roles are stored as text rather than an ordinal: reordering the Java enum must
    -- never silently promote a user to ADMIN.
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Case-insensitive uniqueness. These also serve the login lookup, which queries
-- lower(username) / lower(email) so that it uses the index rather than scanning.
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower    ON users (lower(email));

COMMENT ON TABLE  users IS 'Registered accounts. password_hash is a Spring Security DelegatingPasswordEncoder value, prefixed with its algorithm id.';
COMMENT ON COLUMN users.public_id IS 'Externally visible identifier; the API never exposes users.id.';

-- No index on created_at yet: nothing orders by it. The administrative "list accounts by
-- recency" screen adds one in the phase that introduces it, rather than speculatively.
