-- Initial BE1 authentication schema. For production, replace bootstrap SQL with versioned migrations.
CREATE TABLE IF NOT EXISTS auth_users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at_ms BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_refresh_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    revoked_at_ms BIGINT NULL,
    CONSTRAINT fk_auth_refresh_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
);

CREATE TABLE IF NOT EXISTS auth_idempotency (
    actor_scope VARCHAR(128) NOT NULL,
    operation_name VARCHAR(30) NOT NULL,
    idem_key VARCHAR(120) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    encrypted_response TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY(actor_scope, operation_name, idem_key)
);


-- BE1 phase 2 onboarding profile: FK to the auth table, initial values are safe defaults.
CREATE TABLE IF NOT EXISTS user_onboarding (
    user_id VARCHAR(36) PRIMARY KEY,
    terms_version VARCHAR(80) NULL,
    privacy_version VARCHAR(80) NULL,
    terms_accepted_at_ms BIGINT NULL,
    privacy_accepted_at_ms BIGINT NULL,
    birth_date DATE NULL,
    birth_date_edits_used INTEGER NOT NULL DEFAULT 0,
    day_intro_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_local_time VARCHAR(5) NULL,
    CONSTRAINT fk_user_onboarding_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
);
