-- New, empty MySQL database only. Existing databases require an audited baseline.
CREATE TABLE auth_users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_refresh_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    revoked_at_ms BIGINT NULL,
    CONSTRAINT fk_auth_refresh_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_idempotency (
    actor_scope VARCHAR(128) NOT NULL,
    operation_name VARCHAR(30) NOT NULL,
    idem_key VARCHAR(120) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    encrypted_response TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY(actor_scope, operation_name, idem_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE user_onboarding (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_password_resets (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    used_at_ms BIGINT NULL,
    CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE auth_reset_outbox (
    reset_id VARCHAR(36) PRIMARY KEY,
    encrypted_token TEXT NOT NULL,
    next_attempt_at_ms BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at_ms BIGINT NULL,
    CONSTRAINT fk_outbox_reset FOREIGN KEY (reset_id) REFERENCES auth_password_resets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE be1_nights (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    night_date DATE NOT NULL,
    category_id VARCHAR(32) NOT NULL,
    worry_text VARCHAR(2000) NULL,
    worry_saved_at_ms BIGINT NOT NULL,
    worry_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    support_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    support_json TEXT NULL,
    support_continued_at_ms BIGINT NULL,
    writing_state VARCHAR(30) NULL,
    CONSTRAINT uq_be1_night UNIQUE(user_id, night_date),
    CONSTRAINT fk_be1_night_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE be1_sessions (
    id VARCHAR(36) PRIMARY KEY,
    night_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    status VARCHAR(15) NOT NULL,
    sheep_count INTEGER NOT NULL,
    feed_version INTEGER NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    furthest_position INTEGER NOT NULL DEFAULT 0,
    sequence BIGINT NOT NULL DEFAULT 0,
    writer_epoch BIGINT NOT NULL DEFAULT 1,
    started_at_ms BIGINT NOT NULL,
    closes_at_ms BIGINT NOT NULL,
    finished_at_ms BIGINT NULL,
    finish_reason VARCHAR(25) NULL,
    finish_position INTEGER NULL,
    finish_sequence BIGINT NULL,
    finish_writer_epoch BIGINT NULL,
    closing_card_json TEXT NULL,
    closing_viewed_at_ms BIGINT NULL,
    entrusted_count INTEGER NOT NULL DEFAULT 0,
    fluff_count INTEGER NOT NULL DEFAULT 0,
    yarn_sent_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_be1_session_night FOREIGN KEY (night_id) REFERENCES be1_nights(id),
    CONSTRAINT fk_be1_session_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;



CREATE TABLE be1_session_replays (
    user_id VARCHAR(36) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idem_key VARCHAR(120) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY(user_id, operation, idem_key),
    CONSTRAINT fk_be1_replay_user FOREIGN KEY (user_id) REFERENCES auth_users(id),
    CONSTRAINT fk_be1_replay_session FOREIGN KEY (session_id) REFERENCES be1_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
