-- Baseline migration.
--
-- `citext` provides a case-insensitive text type. User e-mail addresses and
-- usernames are stored as citext so that uniqueness is enforced by the database
-- itself rather than by application-level lower-casing, which is easy to bypass
-- and easy to forget.
CREATE EXTENSION IF NOT EXISTS citext;
