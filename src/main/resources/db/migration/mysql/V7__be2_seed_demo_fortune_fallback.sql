-- Demo fallback from the frontend relaxed preview. It supplies no zodiac ranking.
-- Preserve any approved templates already present in the database.
INSERT INTO fortune_approved_templates (zodiac, sheep_type, message, lucky_color, lucky_item, small_action)
SELECT zodiac, 'relaxed', '천천히 가도 괜찮은 날. 점심은 좋아하는 걸로', '연두', '손수건', '미뤄 둔 연락 하나 해 볼까?'
FROM fortune_zodiac_locks
WHERE TRUE
ON DUPLICATE KEY UPDATE zodiac = fortune_approved_templates.zodiac;
