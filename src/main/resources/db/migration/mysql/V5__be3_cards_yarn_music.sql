CREATE TABLE music_tracks (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    artist VARCHAR(500) NOT NULL,
    cover_url VARCHAR(1000) NULL,
    spotify_search_url VARCHAR(1500) NOT NULL,
    cached_at_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE music_search_cache (
    query_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    track_ids_json JSON NOT NULL,
    cached_at_ms BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE card_submissions (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    kind VARCHAR(10) NOT NULL,
    category_id VARCHAR(20) NOT NULL,
    content_json JSON NULL,
    manual_track BOOLEAN NOT NULL DEFAULT FALSE,
    moderation_status VARCHAR(20) NOT NULL,
    rejection_reason VARCHAR(1200) NULL,
    revision INT NOT NULL DEFAULT 1,
    source_night_id VARCHAR(36) COLLATE utf8mb4_bin NULL,
    created_at_ms BIGINT NOT NULL,
    submitted_at_ms BIGINT NOT NULL,
    moderated_at_ms BIGINT NULL,
    deleted_at_ms BIGINT NULL,
    INDEX ix_card_submissions_owner (user_id, created_at_ms, id),
    INDEX ix_card_submissions_queue (moderation_status, submitted_at_ms, id),
    CONSTRAINT fk_card_submission_user FOREIGN KEY (user_id) REFERENCES auth_users(id),
    CHECK (kind IN ('text', 'song')),
    CHECK (moderation_status IN ('pending', 'approved', 'rejected')),
    CHECK (deleted_at_ms IS NOT NULL OR content_json IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE card_idempotency (
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    operation VARCHAR(20) NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    response_json JSON NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY (user_id, operation, key_hash),
    INDEX ix_card_idempotency_expiry (expires_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE yarn_events (
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    first_sent_at_ms BIGINT NOT NULL,
    canceled_at_ms BIGINT NULL,
    PRIMARY KEY (user_id, card_id),
    INDEX ix_yarn_events_card (card_id, first_sent_at_ms),
    CONSTRAINT fk_yarn_event_card FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE yarn_summaries (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    summary_date DATE NOT NULL,
    seen_at_ms BIGINT NULL,
    UNIQUE KEY uk_yarn_summary_day (user_id, summary_date),
    CONSTRAINT fk_yarn_summary_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
