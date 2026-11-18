package com.yangsenunbam.feed.persistence

import com.yangsenunbam.card.*
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.port.CardAvailability
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant

/** BE3 검수 승인/삭제 트랜잭션에서 호출하는 피드 공개 목록. 미승인 원문은 저장하지 않는다. */
@Repository
@Profile("mysql")
class MysqlCardCatalog(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {
    @Transactional
    fun publishApproved(card: CardResponse, authorUserId: String?, approvedAt: Instant) {
        require(card.available && card.content != null)
        require((card.kind == CardKind.TEXT && card.content is TextCardContent) ||
            (card.kind == CardKind.SONG && card.content is SongCardContent))
        require(card.authorType != AuthorType.USER || authorUserId != null)
        val current = jdbc.first("SELECT id, available FROM feed_card_catalog WHERE id = ? FOR UPDATE", card.id) {
            it.getBoolean("available")
        }
        // 공개 카드 수정은 새로운 ID로 발행한다. 삭제한 ID가 다시 살아나지 않도록 한다.
        if (current != null) throw ApiException(ErrorCode.MODERATION_CONFLICT, "이미 공개 이력이 있는 카드입니다.")
        jdbc.update("""INSERT INTO feed_card_catalog
            (id, kind, tone, category_id, author_type, author_label, author_user_id, content_json, available, published_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)""",
            card.id, card.kind.value, card.tone?.value, card.categoryId?.value, card.authorType.value,
            card.authorLabel, authorUserId, mapper.writeValueAsString(card.content), approvedAt.toEpochMilli())
        jdbc.update("INSERT INTO feed_card_history(card_id, changed_at_ms, available, tone) VALUES (?, ?, TRUE, ?)",
            card.id, approvedAt.toEpochMilli(), card.tone?.value)
    }

    @Transactional
    fun remove(cardId: String, removedAt: Instant) {
        val card = jdbc.first("SELECT * FROM feed_card_catalog WHERE id = ? FOR UPDATE", cardId) { map(it) } ?: return
        if (!card.available) return
        jdbc.update("UPDATE feed_card_catalog SET available = FALSE, content_json = NULL WHERE id = ?", cardId)
        jdbc.update("DELETE FROM feed_card_yarn WHERE card_id = ?", cardId)
        jdbc.update("""INSERT INTO feed_card_history(card_id, changed_at_ms, available, tone) VALUES (?, ?, FALSE, ?)
            ON DUPLICATE KEY UPDATE available = FALSE, tone = ?""",
            cardId, removedAt.toEpochMilli(), card.tone?.value, card.tone?.value)
    }

    /** 사용자 잠금 이후 카드 공유 잠금. 삭제 트랜잭션은 같은 카드의 배타 잠금을 얻는다. */
    fun readLocked(userId: String, cardId: String): CardResponse {
        val card = jdbc.first("SELECT * FROM feed_card_catalog WHERE id = ? FOR SHARE", cardId) { map(it) }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "카드를 찾을 수 없습니다.")
        if (!card.available) return card
        val sent = jdbc.queryForObject("SELECT COUNT(*) FROM feed_card_yarn WHERE user_id = ? AND card_id = ?",
            Int::class.java, userId, cardId) == 1
        return card.copy(viewer = card.viewer.copy(yarnSent = sent))
    }

    fun at(cardId: String, instant: Instant): CardAvailability = jdbc.first(
        "SELECT available, tone FROM feed_card_history WHERE card_id = ? AND changed_at_ms <= ? ORDER BY changed_at_ms DESC LIMIT 1",
        cardId, instant.toEpochMilli(),
    ) { CardAvailability(cardId, it.getBoolean("available"), it.getString("tone")?.let { tone -> wire<CardTone>(tone) }) }
        ?: CardAvailability(cardId, false, null)

    private fun map(rs: ResultSet): CardResponse {
        val kind = wire<CardKind>(rs.getString("kind"))
        val available = rs.getBoolean("available")
        val json = rs.getString("content_json")
        val content = if (!available || json == null) null else when (kind) {
            CardKind.TEXT -> mapper.readValue(json, TextCardContent::class.java)
            CardKind.SONG -> mapper.readValue(json, SongCardContent::class.java)
        }
        return CardResponse(rs.getString("id"), available, kind,
            rs.getString("tone")?.let { wire<CardTone>(it) }, rs.getString("category_id")?.let { wire<CategoryId>(it) },
            wire(rs.getString("author_type")), rs.getString("author_label"), content, CardViewer(false, false, false))
    }
}
