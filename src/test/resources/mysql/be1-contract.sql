-- origin/BE1_auth_onboarding의 auth_users/user_onboarding 읽기 계약. 테스트 DB에만 적용한다.
CREATE TABLE IF NOT EXISTS auth_users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at_ms BIGINT NOT NULL
);
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
