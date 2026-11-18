-- auth_users/user_onboarding은 BE1 소유다. 이 마이그레이션은 해당 테이블을 변경하지 않는다.
-- BE1이 밤/세션을 생성할 때 MysqlNightStore의 생성 메서드를 같은 트랜잭션에서 사용한다.
CREATE TABLE night_records (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    night_date DATE NOT NULL,
    closes_at_ms BIGINT NOT NULL,
    category_id VARCHAR(20) NOT NULL,
    worry_text TEXT NULL,
    worry_saved_at_ms BIGINT NULL,
    writing_state VARCHAR(20) NULL,
    view_sequence BIGINT NOT NULL DEFAULT 0,
    default_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    default_card_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    reflection_answer VARCHAR(20) NULL,
    reflection_message VARCHAR(255) NULL,
    UNIQUE KEY uk_night_user_date (user_id, night_date),
    INDEX ix_night_closing (default_frozen, closes_at_ms, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_card_catalog (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    kind VARCHAR(10) NOT NULL,
    tone VARCHAR(20) NULL,
    category_id VARCHAR(20) NULL,
    author_type VARCHAR(20) NOT NULL,
    author_label VARCHAR(100) NULL,
    author_user_id VARCHAR(36) COLLATE utf8mb4_bin NULL,
    content_json JSON NULL,
    available BOOLEAN NOT NULL,
    published_at_ms BIGINT NOT NULL,
    INDEX ix_feed_card_selection (available, kind, tone, category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 삭제된 본문은 남기지 않고 마감 당시의 공개 가능 여부/톤만 보존한다.
CREATE TABLE feed_card_history (
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    changed_at_ms BIGINT NOT NULL,
    available BOOLEAN NOT NULL,
    tone VARCHAR(20) NULL,
    PRIMARY KEY (card_id, changed_at_ms),
    CONSTRAINT fk_card_history FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_card_yarn (
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (user_id, card_id),
    CONSTRAINT fk_feed_yarn_card FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_sessions (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    night_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    sheep_count INT NOT NULL,
    feed_version INT NOT NULL,
    position INT NOT NULL DEFAULT 0,
    furthest_position INT NOT NULL DEFAULT 0,
    sequence BIGINT NOT NULL DEFAULT 0,
    writer_epoch BIGINT NOT NULL DEFAULT 1,
    last_request_position INT NULL,
    last_request_furthest INT NULL,
    started_at_ms BIGINT NOT NULL,
    closes_at_ms BIGINT NOT NULL,
    closing_card_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    closing_viewed_at_ms BIGINT NULL,
    active_night_id VARCHAR(64) COLLATE utf8mb4_bin
        GENERATED ALWAYS AS (CASE WHEN status = 'active' THEN night_id ELSE NULL END) STORED,
    UNIQUE KEY uk_active_night (active_night_id),
    INDEX ix_session_night (night_id),
    CONSTRAINT fk_session_night FOREIGN KEY (night_id) REFERENCES night_records(id),
    CONSTRAINT fk_closing_card FOREIGN KEY (closing_card_id) REFERENCES feed_card_catalog(id),
    CHECK (status IN ('active', 'completed', 'stopped', 'expired')),
    CHECK (sheep_count BETWEEN 3 AND 30),
    CHECK (position >= 0 AND position <= furthest_position AND furthest_position <= sheep_count),
    CHECK (sequence >= 0 AND writer_epoch >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_items (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    position INT NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
    pause_kind VARCHAR(20) NULL,
    pause_action VARCHAR(20) NULL,
    UNIQUE KEY uk_feed_position (session_id, position),
    CONSTRAINT fk_item_session FOREIGN KEY (session_id) REFERENCES feed_sessions(id),
    CONSTRAINT fk_item_card FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id),
    CHECK (position BETWEEN 1 AND 30),
    CHECK ((card_id IS NOT NULL AND pause_kind IS NULL AND pause_action IS NULL)
        OR (card_id IS NULL AND pause_kind IS NOT NULL AND pause_kind IN ('entrust_worry', 'stop_offer'))),
    CHECK (pause_action IS NULL OR (pause_kind = 'entrust_worry' AND pause_action IN ('entrusted', 'skipped')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_card_views (
    session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    position INT NULL,
    sheep_count INT NOT NULL,
    viewed_at_ms BIGINT NOT NULL,
    view_order BIGINT NOT NULL,
    PRIMARY KEY (session_id, card_id),
    CONSTRAINT fk_view_session FOREIGN KEY (session_id) REFERENCES feed_sessions(id),
    CONSTRAINT fk_view_card FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE night_reactions (
    night_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    fluff_saved BOOLEAN NOT NULL DEFAULT FALSE,
    song_saved_at_ms BIGINT NULL,
    PRIMARY KEY (night_id, card_id),
    CONSTRAINT fk_reaction_night FOREIGN KEY (night_id) REFERENCES night_records(id),
    CONSTRAINT fk_reaction_card FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- BE3는 이 테이블의 placements_json/version을 갱신한다. BE2는 채택 시 한 번만 삽입한다.
CREATE TABLE adopted_sheep (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    night_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    night_date DATE NOT NULL,
    card_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    color_key VARCHAR(40) NULL,
    created_at_ms BIGINT NOT NULL,
    position INT NULL,
    sheep_count INT NOT NULL,
    placements_json JSON NOT NULL,
    version INT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_sheep_night (night_id),
    INDEX ix_sheep_user_date (user_id, night_date, id),
    CONSTRAINT fk_sheep_night FOREIGN KEY (night_id) REFERENCES night_records(id),
    CONSTRAINT fk_sheep_card FOREIGN KEY (card_id) REFERENCES feed_card_catalog(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_resume_receipts (
    user_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    response_json JSON NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    PRIMARY KEY (user_id, key_hash),
    INDEX ix_resume_expiry (expires_at_ms),
    CONSTRAINT fk_resume_session FOREIGN KEY (session_id) REFERENCES feed_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feed_category_colors (
    category_id VARCHAR(20) PRIMARY KEY,
    color_key VARCHAR(40) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO feed_category_colors(category_id, color_key) VALUES
    ('romance', NULL), ('career', NULL), ('study', NULL), ('health', NULL),
    ('relationships', NULL), ('family', NULL), ('other', NULL), ('none', 'gray');

CREATE TABLE fortune_zodiac_locks (zodiac VARCHAR(20) PRIMARY KEY) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO fortune_zodiac_locks VALUES ('aries'), ('taurus'), ('gemini'), ('cancer'), ('leo'), ('virgo'),
    ('libra'), ('scorpio'), ('sagittarius'), ('capricorn'), ('aquarius'), ('pisces');

CREATE TABLE fortune_approved_daily (
    fortune_date DATE NOT NULL,
    zodiac VARCHAR(20) NOT NULL,
    response_json JSON NOT NULL,
    PRIMARY KEY (fortune_date, zodiac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fortune_approved_templates (
    zodiac VARCHAR(20) PRIMARY KEY,
    sheep_type VARCHAR(20) NOT NULL,
    message VARCHAR(500) NOT NULL,
    lucky_color VARCHAR(100) NOT NULL,
    lucky_item VARCHAR(100) NOT NULL,
    small_action VARCHAR(500) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fortune_results (
    fortune_date DATE NOT NULL,
    zodiac VARCHAR(20) NOT NULL,
    response_json JSON NOT NULL,
    PRIMARY KEY (fortune_date, zodiac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
