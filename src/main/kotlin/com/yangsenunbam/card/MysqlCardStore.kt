package com.yangsenunbam.card

import com.yangsenunbam.auth.AuthStore
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.persistence.MysqlCardCatalog
import com.yangsenunbam.feed.persistence.first
import com.yangsenunbam.feed.persistence.wire
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

/**
 * card_submissions(V5)에 제출·검수 상태를 저장한다. 승인·삭제 시 BE2의 [MysqlCardCatalog]로 공개 목록을 갱신한다.
 * 잠금 순서: auth_users(작성자) → card_submissions → feed_card_catalog.
 */
@Repository
@Profile("mysql")
class MysqlCardStore(
    private val jdbc: JdbcTemplate,
    private val auth: AuthStore,
    private val catalog: MysqlCardCatalog,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : CardStore {

    override fun lockUser(userId: String) {
        auth.lockUser(userId) ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "계정을 찾을 수 없어요.")
    }

    override fun replay(userId: String, operation: String, keyHash: String): CardReplay? = jdbc.first(
        """SELECT fingerprint, response_json FROM card_idempotency
           WHERE user_id = ? AND operation = ? AND key_hash = ? AND expires_at_ms > ?""",
        userId, operation, keyHash, clock.millis(),
    ) { CardReplay(it.getString("fingerprint"), fromSnapshot(it.getString("response_json"))) }

    override fun saveReplay(userId: String, operation: String, keyHash: String, fingerprint: String,
                            response: SubmissionResponse, expiresAt: Instant) {
        jdbc.update("DELETE FROM card_idempotency WHERE user_id = ? AND expires_at_ms <= ?", userId, clock.millis())
        jdbc.update("""INSERT INTO card_idempotency(user_id, operation, key_hash, fingerprint, card_id, response_json, expires_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)""",
            userId, operation, keyHash, fingerprint, response.id, toSnapshot(response), expiresAt.toEpochMilli())
    }

    override fun insert(record: SubmissionRecord) {
        jdbc.update("""INSERT INTO card_submissions
            (id, user_id, kind, category_id, content_json, manual_track, moderation_status, rejection_reason, revision,
             source_night_id, created_at_ms, submitted_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            record.id, record.userId, record.kind.value, record.categoryId.value, mapper.writeValueAsString(record.content),
            record.manualTrack, record.status.value, record.rejectionReason, record.revision, record.sourceNightId,
            record.createdAt.toEpochMilli(), record.submittedAt.toEpochMilli())
    }

    override fun owned(userId: String, cardId: String, lock: Boolean): SubmissionRecord? = jdbc.first(
        "SELECT * FROM card_submissions WHERE id = ? AND user_id = ?" + if (lock) " FOR UPDATE" else "",
        cardId, userId,
    ) { record(it) }

    override fun byId(cardId: String, lock: Boolean): SubmissionRecord? = jdbc.first(
        "SELECT * FROM card_submissions WHERE id = ?" + if (lock) " FOR UPDATE" else "", cardId,
    ) { record(it) }

    override fun resubmit(cardId: String, change: SubmissionContent, revision: Int, at: Instant) {
        jdbc.update("""UPDATE card_submissions SET kind = ?, category_id = ?, content_json = ?, manual_track = ?,
                moderation_status = 'pending', rejection_reason = NULL, revision = ?, submitted_at_ms = ?, moderated_at_ms = NULL
            WHERE id = ?""",
            change.kind.value, change.categoryId.value, mapper.writeValueAsString(change.content), change.manualTrack,
            revision, at.toEpochMilli(), cardId)
    }

    override fun moderate(cardId: String, status: ModerationStatus, reason: String?, at: Instant) {
        jdbc.update("UPDATE card_submissions SET moderation_status = ?, rejection_reason = ?, moderated_at_ms = ? WHERE id = ?",
            status.value, reason, at.toEpochMilli(), cardId)
    }

    override fun markDeleted(cardId: String, at: Instant) {
        jdbc.update("UPDATE card_submissions SET content_json = NULL, rejection_reason = NULL, deleted_at_ms = ? WHERE id = ?",
            at.toEpochMilli(), cardId)
        // 받은 털실의 전송 이력도 남기지 않는다(현재 털실은 unpublish가 지운다).
        jdbc.update("DELETE FROM yarn_events WHERE card_id = ?", cardId)
    }

    override fun ownedPage(userId: String, status: ModerationStatus?, after: SubmissionCursor?, limit: Int): List<SubmissionRecord> {
        val conditions = mutableListOf("user_id = ?", "deleted_at_ms IS NULL")
        val args = mutableListOf<Any?>(userId)
        status?.let { conditions += "moderation_status = ?"; args += it.value }
        after?.let {
            conditions += "(created_at_ms < ? OR (created_at_ms = ? AND id < ?))"
            args.addAll(listOf(it.atMs, it.atMs, it.id))
        }
        args += limit
        return jdbc.query("""SELECT * FROM card_submissions WHERE ${conditions.joinToString(" AND ")}
            ORDER BY created_at_ms DESC, id DESC LIMIT ?""", RowMapper { rs, _ -> record(rs) }, *args.toTypedArray())
    }

    override fun pendingPage(after: SubmissionCursor?, limit: Int): List<SubmissionRecord> {
        val rowMapper = RowMapper { rs, _ -> record(rs) }
        return if (after == null) {
            jdbc.query("""SELECT * FROM card_submissions WHERE moderation_status = 'pending' AND deleted_at_ms IS NULL
                ORDER BY submitted_at_ms, id LIMIT ?""", rowMapper, limit)
        } else {
            jdbc.query("""SELECT * FROM card_submissions WHERE moderation_status = 'pending' AND deleted_at_ms IS NULL
                AND (submitted_at_ms > ? OR (submitted_at_ms = ? AND id > ?))
                ORDER BY submitted_at_ms, id LIMIT ?""", rowMapper, after.atMs, after.atMs, after.id, limit)
        }
    }

    override fun yarnCounts(cardIds: Collection<String>): Map<String, Long> {
        if (cardIds.isEmpty()) return emptyMap()
        val counts = mutableMapOf<String, Long>()
        jdbc.query("""SELECT card_id, COUNT(*) AS yarn_count FROM feed_card_yarn
            WHERE card_id IN (${cardIds.joinToString(",") { "?" }}) GROUP BY card_id""",
            RowCallbackHandler { rs -> counts[rs.getString("card_id")] = rs.getLong("yarn_count") }, *cardIds.toTypedArray())
        return counts
    }

    override fun publish(card: CardResponse, authorUserId: String, at: Instant) =
        catalog.publishApproved(card, authorUserId, at)

    override fun unpublish(cardId: String, at: Instant) = catalog.remove(cardId, at)

    private fun record(rs: ResultSet): SubmissionRecord {
        val kind = wire<CardKind>(rs.getString("kind"))
        return SubmissionRecord(
            rs.getString("id"), rs.getString("user_id"), kind, wire(rs.getString("category_id")),
            rs.getString("content_json")?.let { content(kind, it) }, rs.getBoolean("manual_track"),
            wire(rs.getString("moderation_status")), rs.getString("rejection_reason"), rs.getInt("revision"),
            rs.getString("source_night_id"), Instant.ofEpochMilli(rs.getLong("created_at_ms")),
            Instant.ofEpochMilli(rs.getLong("submitted_at_ms")),
            rs.getObject("deleted_at_ms")?.let { Instant.ofEpochMilli(rs.getLong("deleted_at_ms")) },
        )
    }

    private fun content(kind: CardKind, json: String): CardContentResponse = when (kind) {
        CardKind.TEXT -> mapper.readValue(json, TextCardContent::class.java)
        CardKind.SONG -> mapper.readValue(json, SongCardContent::class.java)
    }

    /** SubmissionResponse.content는 sealed 타입이라 kind와 본문 JSON을 나눠 저장한다. */
    private data class Snapshot(
        val id: String, val kind: String, val categoryId: String, val contentJson: String, val moderationStatus: String,
        val rejectionReason: String?, val yarnCount: Long, val createdAtMs: Long, val revision: Int,
    )

    private fun toSnapshot(response: SubmissionResponse): String = mapper.writeValueAsString(Snapshot(
        response.id, response.kind.value, response.categoryId.value, mapper.writeValueAsString(response.content),
        response.moderationStatus.value, response.rejectionReason, response.yarnCount, response.createdAt.toEpochMilli(),
        response.revision,
    ))

    private fun fromSnapshot(json: String): SubmissionResponse {
        val s = mapper.readValue(json, Snapshot::class.java)
        val kind = wire<CardKind>(s.kind)
        return SubmissionResponse(s.id, kind, wire(s.categoryId), content(kind, s.contentJson), wire(s.moderationStatus),
            s.rejectionReason, s.yarnCount, Instant.ofEpochMilli(s.createdAtMs), s.revision)
    }
}
