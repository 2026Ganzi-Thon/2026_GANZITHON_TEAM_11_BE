-- Keep the IDs and order used in the frontend decoration catalog.
-- Pin image addresses to the asset commit so future changes keep existing placements renderable.
SET @sticker_asset_base = 'https://raw.githubusercontent.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_11_BE/37a26ed0a39d2a036576b7d83aa1a1f96e848462/assets/stickers/';

INSERT INTO stickers (id, label, asset_url, sort_order) VALUES
    ('st-1', '별', CONCAT(@sticker_asset_base, 'star.svg'), 1),
    ('st-2', '동그라미', CONCAT(@sticker_asset_base, 'circle.svg'), 2),
    ('st-3', '세모', CONCAT(@sticker_asset_base, 'triangle.svg'), 3),
    ('st-4', '반짝별', CONCAT(@sticker_asset_base, 'sparkle.svg'), 4),
    ('st-5', '마름모', CONCAT(@sticker_asset_base, 'diamond.svg'), 5),
    ('st-6', '동그라미 2', CONCAT(@sticker_asset_base, 'circle.svg'), 6),
    ('st-7', '세모 2', CONCAT(@sticker_asset_base, 'triangle.svg'), 7)
ON DUPLICATE KEY UPDATE id = stickers.id;
