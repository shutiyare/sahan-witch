-- =====================================================================================
-- V9: security schema (Task 1)
--
--  * participants get an API key (stored only as a SHA-256 hash, never in clear text) and a
--    list of roles that key is allowed to act with.
--  * users are the people who sign in to the operations portal (username + password -> JWT).
--    An ADMIN user belongs to the switch operator; any other user belongs to one participant.
--
-- Existing participants keep working: they simply have no API key until an administrator
-- issues one (POST /api/v1/participants/{id}/api-key).
-- =====================================================================================

ALTER TABLE participants
    ADD COLUMN IF NOT EXISTS api_key_hash  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS allowed_roles VARCHAR(100) NOT NULL DEFAULT 'PARTICIPANT';

-- Authentication looks participants up by key hash on every API-key request, so it must be
-- indexed. Unique: two participants can never share a key. Partial: most rows have none.
CREATE UNIQUE INDEX IF NOT EXISTS uk_participants_api_key_hash
    ON participants (api_key_hash)
    WHERE api_key_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS users (
    id             UUID PRIMARY KEY,

    username       VARCHAR(50)  NOT NULL,

    -- BCrypt hash. The clear-text password is never stored or logged.
    password_hash  VARCHAR(255) NOT NULL,

    -- ADMIN = switch operator (sees everything); PARTICIPANT = acts for one institution.
    role           VARCHAR(50)  NOT NULL,

    participant_id UUID,

    -- Same type as every other timestamp in the schema (the JPA mapping expects timestamptz).
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_users_username UNIQUE (username),

    CONSTRAINT fk_users_participant
        FOREIGN KEY (participant_id) REFERENCES participants (id),

    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'PARTICIPANT')),

    -- A participant user must say which participant it acts for.
    CONSTRAINT chk_users_participant_required
        CHECK (role = 'ADMIN' OR participant_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_users_participant_id ON users (participant_id);
