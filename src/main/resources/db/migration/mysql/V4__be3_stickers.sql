-- BE3: 스티커 도감 (GET /stickers).
-- 양·카드·털실·반응은 BE2 V3 테이블(adopted_sheep, feed_card_catalog, feed_card_yarn, night_reactions)을 그대로 사용한다.
-- 기본 스티커 시드 데이터는 목록 확정 후 별도 버전으로 추가한다.
CREATE TABLE stickers (
    id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    asset_url VARCHAR(1000) NOT NULL,
    sort_order INT NOT NULL,
    INDEX ix_stickers_order (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
