SET @feed_seed_published_at_ms = FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000);

INSERT INTO feed_card_catalog
    (id, kind, tone, category_id, author_type, author_label, author_user_id, content_json, available, published_at_ms)
VALUES
    ('feed_demo_empathy_01', 'text', 'empathy', 'career', 'seed', '먼저 지나간 양', NULL,
        JSON_OBJECT('text', '결과를 기다리는 밤은 더 길게 느껴지지. 오늘은 이미 할 만큼 했어.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_empathy_02', 'text', 'empathy', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '오늘 답을 찾지 않아도 괜찮아. 마음이 복잡한 채로 하루를 마쳐도 돼.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_empathy_03', 'text', 'empathy', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '마음에 걸리는 일이 있다면, 오늘은 그 일이 신경 쓰였다는 것만 알아줘도 충분해.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_light_01', 'text', 'light', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '오늘의 할 일 목록에 쉬는 시간도 적어 두면 어때? 아무것도 하지 않는 시간도 네 시간이니까.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_light_02', 'text', 'light', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '내일 아침에 제일 먼저 마시고 싶은 걸 하나 골라 봐. 따뜻한 물 한 잔도 좋아.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_light_03', 'text', 'light', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '지금 이불에서 가장 따뜻한 곳을 찾아 발을 쏙 넣어 봐.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_warm_01', 'text', 'warm', NULL, 'seed', '먼저 지나간 양', NULL,
        JSON_OBJECT('text', '오늘 하루도 버틴 너, 이제 눈 감아도 괜찮아.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_warm_02', 'text', 'warm', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '여기까지 세느라 수고했어. 남은 일은 내일의 네가 천천히 이어 가면 돼.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_warm_03', 'text', 'warm', NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('text', '오늘 하루를 여기서 내려놓자. 이제 편하게 쉬어도 될 시간이야.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_song_01', 'song', NULL, NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('track', JSON_OBJECT('id', 'feed_demo_track_01', 'title', 'Clair de Lune', 'artist', 'Claude Debussy',
            'cover_url', NULL, 'spotify_search_url', 'https://open.spotify.com/search/Clair%20de%20Lune%20Claude%20Debussy'),
            'recommendation', '조용히 불을 낮추고 피아노 소리를 따라가 봐.'), TRUE, @feed_seed_published_at_ms),
    ('feed_demo_song_02', 'song', NULL, NULL, 'editorial', '놀러온 양', NULL,
        JSON_OBJECT('track', JSON_OBJECT('id', 'feed_demo_track_02', 'title', 'Gymnopédie No. 1', 'artist', 'Erik Satie',
            'cover_url', NULL, 'spotify_search_url', 'https://open.spotify.com/search/Gymnop%C3%A9die%20No.%201%20Erik%20Satie'),
            'recommendation', '서두르고 싶지 않은 밤에 천천히 듣기 좋은 곡이야.'), TRUE, @feed_seed_published_at_ms)
ON DUPLICATE KEY UPDATE id = feed_card_catalog.id;

INSERT INTO feed_card_history (card_id, changed_at_ms, available, tone)
SELECT id, published_at_ms, available, tone
FROM feed_card_catalog
WHERE id IN ('feed_demo_empathy_01', 'feed_demo_empathy_02', 'feed_demo_empathy_03',
             'feed_demo_light_01', 'feed_demo_light_02', 'feed_demo_light_03',
             'feed_demo_warm_01', 'feed_demo_warm_02', 'feed_demo_warm_03',
             'feed_demo_song_01', 'feed_demo_song_02')
  AND published_at_ms = @feed_seed_published_at_ms
  AND available = TRUE
ON DUPLICATE KEY UPDATE card_id = feed_card_history.card_id;
