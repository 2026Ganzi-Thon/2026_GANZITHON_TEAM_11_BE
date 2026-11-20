package com.yangsenunbam.flock

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.card.CardViewer
import com.yangsenunbam.card.SongCardContent
import com.yangsenunbam.card.TextCardContent
import com.yangsenunbam.common.CardKind
import com.yangsenunbam.common.CardTone
import com.yangsenunbam.common.CategoryId
import com.yangsenunbam.feed.persistence.first
import com.yangsenunbam.feed.persistence.instant
import com.yangsenunbam.feed.persistence.optionalInt
import com.yangsenunbam.feed.persistence.wire
import com.yangsenunbam.morning.ReflectionResponse
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.Date
import java.sql.ResultSet

@Repository
@Profile("mysql")
class MysqlFlockStore(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) : FlockStore {
    @Transactional(readOnly = true)
    override fun sheepPage(userId: String, after: SheepCursor?, limit: Int): List<SheepResponse> {
        val rowMapper = RowMapper { rs, _ -> sheep(rs) }
        return if (after == null) {
            jdbc.query("SELECT * FROM adopted_sheep WHERE user_id = ? ORDER BY night_date DESC, id DESC LIMIT ?",
                rowMapper, userId, limit)
        } else {
            val date = Date.valueOf(after.nightDate)
            jdbc.query("""SELECT * FROM adopted_sheep
                WHERE user_id = ? AND (night_date < ? OR (night_date = ? AND id < ?))
                ORDER BY night_date DESC, id DESC LIMIT ?""", rowMapper, userId, date, date, after.id, limit)
        }
    }

    @Transactional(readOnly = true)
    override fun sheepDetail(userId: String, sheepId: String): SheepDetailResponse? {
        val row = jdbc.first("""SELECT s.*, n.category_id AS night_category_id,
                CASE WHEN n.worry_deleted THEN NULL ELSE n.worry_text END AS night_worry_text,
                f.reflection_answer, f.reflection_message
            FROM adopted_sheep s
            JOIN be1_nights n ON n.id = s.night_id
            LEFT JOIN night_feed_state f ON f.night_id = s.night_id
            WHERE s.id = ? AND s.user_id = ?""", sheepId, userId) { rs ->
            val sheep = sheep(rs)
            val night = SheepNight(
                sheep.nightDate, rs.getString("night_worry_text"), wire<CategoryId>(rs.getString("night_category_id")),
                rs.getString("reflection_answer")?.let { ReflectionResponse(wire(it), rs.getString("reflection_message")) },
            )
            DetailRow(sheep, rs.optionalInt("position"), rs.getInt("sheep_count"), night)
        } ?: return null

        val card = card(userId, row.sheep.nightId, row.sheep.cardId)
        return SheepDetailResponse(row.sheep, card, row.position, row.sheepCount, row.night)
    }

    override fun stickers(): List<StickerResponse> =
        jdbc.query("SELECT id, label, asset_url FROM stickers ORDER BY sort_order, id", RowMapper { rs, _ ->
            StickerResponse(rs.getString("id"), rs.getString("label"), rs.getString("asset_url"))
        })

    override fun lockSheep(userId: String, sheepId: String): SheepResponse? =
        jdbc.first("SELECT * FROM adopted_sheep WHERE id = ? AND user_id = ? FOR UPDATE", sheepId, userId) { sheep(it) }

    override fun unknownStickers(stickerIds: Set<String>): Set<String> {
        if (stickerIds.isEmpty()) return emptySet()
        val known = jdbc.queryForList("SELECT id FROM stickers WHERE id IN (${stickerIds.joinToString(",") { "?" }})",
            String::class.java, *stickerIds.toTypedArray()).filterNotNull().toSet()
        return stickerIds - known
    }

    override fun savePlacements(sheepId: String, placements: List<Placement>, version: Int) {
        jdbc.update("UPDATE adopted_sheep SET placements_json = ?, version = ? WHERE id = ?",
            mapper.writeValueAsString(placements), version, sheepId)
    }

    private fun card(userId: String, nightId: String, cardId: String): CardResponse {
        val card = jdbc.first("SELECT * FROM feed_card_catalog WHERE id = ?", cardId) { rs ->
            val kind = wire<CardKind>(rs.getString("kind"))
            val available = rs.getBoolean("available")
            val json = rs.getString("content_json")
            val content = if (!available || json == null) null else when (kind) {
                CardKind.TEXT -> mapper.readValue(json, TextCardContent::class.java)
                CardKind.SONG -> mapper.readValue(json, SongCardContent::class.java)
            }
            CardResponse(rs.getString("id"), available && content != null, kind,
                rs.getString("tone")?.let { wire<CardTone>(it) }, rs.getString("category_id")?.let { wire<CategoryId>(it) },
                wire(rs.getString("author_type")), rs.getString("author_label"), content,
                CardViewer(yarnSent = false, fluffSaved = false, songSaved = false))
        } ?: error("adopted sheep refers to missing card $cardId")
        if (!card.available) return card

        val yarnSent = jdbc.queryForObject("SELECT COUNT(*) FROM feed_card_yarn WHERE user_id = ? AND card_id = ?",
            Int::class.java, userId, cardId) == 1
        val reaction = jdbc.first("SELECT fluff_saved, song_saved_at_ms FROM night_reactions WHERE night_id = ? AND card_id = ?",
            nightId, cardId) { it.getBoolean("fluff_saved") to (it.getObject("song_saved_at_ms") != null) }
        val songSaved = card.kind == CardKind.SONG && reaction?.second == true
        return card.copy(viewer = CardViewer(yarnSent, reaction?.first == true, songSaved))
    }

    private fun sheep(rs: ResultSet): SheepResponse = SheepResponse(
        rs.getString("id"), rs.getString("night_id"), rs.getDate("night_date").toLocalDate(), rs.getString("card_id"),
        rs.getString("color_key"), rs.instant("created_at_ms"),
        mapper.readValue(rs.getString("placements_json"), Array<Placement>::class.java).toList(), rs.getInt("version"),
    )

    private data class DetailRow(val sheep: SheepResponse, val position: Int?, val sheepCount: Int, val night: SheepNight)
}
