package com.yangsenunbam.feed.persistence

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.ProgressRequest
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.*
import com.yangsenunbam.flock.Placement
import com.yangsenunbam.flock.SheepResponse
import com.yangsenunbam.morning.ReflectionResponse
import com.yangsenunbam.session.SessionResponse
import org.springframework.context.annotation.Profile
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.LocalDate

@Repository
@Profile("mysql")
class MysqlNightStore(
    private val jdbc: JdbcTemplate,
    manager: PlatformTransactionManager,
    private val mapper: JsonMapper,
    private val cards: MysqlCardCatalog,
) : NightStore {
    private val transaction = TransactionTemplate(manager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout = 15
    }

    override fun <T> withUser(userId: String, block: (NightTransaction) -> T): T {
        try {
            return transaction.execute {
                // BE1의 실제 회원 행으로 직렬화한다. 탈퇴 처리도 이 행을 먼저 잠가야 한다.
                val user = jdbc.first("SELECT id FROM auth_users WHERE id = ? FOR UPDATE", userId) { it.getString("id") }
                    ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다.")
                Value(block(Transaction(user)))
            }.value
        } catch (ex: PessimisticLockingFailureException) {
            throw ApiException(ErrorCode.REQUEST_IN_PROGRESS, "동시에 처리 중인 요청이 있습니다. 다시 시도해 주세요.")
        }
    }

    /** BE1에서 고민/지원 상태 검증을 마친 뒤 같은 트랜잭션으로 호출한다. */
    fun createNight(night: NightRecord) = withUser(night.userId) {
        jdbc.update("""INSERT INTO night_records
            (id, user_id, night_date, closes_at_ms, category_id, worry_text, worry_saved_at_ms, writing_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", night.id, night.userId, night.nightDate, night.closesAt.toEpochMilli(),
            night.categoryId.value, night.worryText, night.worrySavedAt?.toEpochMilli(), night.writingState?.value)
        Unit
    }

    /** BE1 세션 생성에서 FeedBuilder 결과와 함께 저장한다. 활성 세션 고유 제약을 DB에서도 검사한다. */
    fun createSession(session: FeedSession) = withUser(session.userId) { tx ->
        val state = session.state
        val night = tx.ownedNight(session.userId, state.nightId)
        require(state.closesAt == night.closesAt)
        require(state.status == SessionStatus.ACTIVE && state.position == 0 && state.furthestPosition == 0 && state.sequence == 0L)
        try {
            jdbc.update("""INSERT INTO feed_sessions
                (id, night_id, user_id, status, sheep_count, feed_version, started_at_ms, closes_at_ms, writer_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", state.id, state.nightId, session.userId, state.status.value,
                state.sheepCount, state.feedVersion, state.startedAt.toEpochMilli(), state.closesAt.toEpochMilli(), state.writerEpoch)
        } catch (ex: DuplicateKeyException) {
            throw ApiException(ErrorCode.ACTIVE_SESSION_EXISTS, "이미 진행 중인 세션이 있습니다.")
        }
        session.items.forEach { item ->
            jdbc.update("INSERT INTO feed_items(id, session_id, position, card_id, pause_kind) VALUES (?, ?, ?, ?, ?)",
                item.id, state.id, item.position, (item as? StoredCard)?.cardId, (item as? StoredPause)?.kind?.value)
        }
    }

    private inner class Transaction(private val userId: String) : NightTransaction {
        override fun night(id: String) = jdbc.first("SELECT * FROM night_records WHERE id = ? AND user_id = ?", id, userId) { nightRow(it) }
        override fun nightOn(date: LocalDate) = jdbc.first("SELECT * FROM night_records WHERE night_date = ? AND user_id = ?", date, userId) { nightRow(it) }
        override fun session(id: String) = jdbc.first("SELECT * FROM feed_sessions WHERE id = ? AND user_id = ?", id, userId) { sessionRow(it) }
        override fun sessions(nightId: String) = jdbc.query("SELECT * FROM feed_sessions WHERE night_id = ? AND user_id = ? ORDER BY started_at_ms, id",
            RowMapper { rs, _ -> sessionRow(rs) }, nightId, userId)

        override fun saveNight(night: NightRecord) {
            ownedNight(userId, night.id)
            // 고민 원문/저장 시각/카테고리는 BE1 소유이므로 스냅샷 값으로 덮어쓰지 않는다.
            jdbc.update("""UPDATE night_records SET writing_state = ?, view_sequence = ?, default_frozen = ?,
                default_card_id = ?, reflection_answer = ?, reflection_message = ? WHERE id = ? AND user_id = ?""",
                night.writingState?.value, night.viewSequence, night.defaultFrozen, night.defaultCardId,
                night.reflection?.answer?.value, night.reflection?.message, night.id, userId)
            night.reactions.forEach { (cardId, reaction) ->
                jdbc.update("""INSERT INTO night_reactions(night_id, card_id, fluff_saved, song_saved_at_ms) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE fluff_saved = ?, song_saved_at_ms = ?""", night.id, cardId, reaction.fluffSaved,
                    reaction.songSavedAt?.toEpochMilli(), reaction.fluffSaved, reaction.songSavedAt?.toEpochMilli())
            }
            night.adoption?.let { adoption ->
                val sheep = adoption.sheep
                val existing = jdbc.first("SELECT card_id FROM adopted_sheep WHERE night_id = ?", night.id) { it.getString("card_id") }
                if (existing != null && existing != sheep.cardId) {
                    throw ApiException(ErrorCode.ADOPTION_ALREADY_FINALIZED, "이 밤의 양을 이미 선택했습니다.")
                }
                if (existing == null) jdbc.update("""INSERT INTO adopted_sheep
                    (id, night_id, user_id, night_date, card_id, color_key, created_at_ms, position, sheep_count, placements_json, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", sheep.id, night.id, userId, night.nightDate, sheep.cardId,
                    sheep.colorKey, sheep.createdAt.toEpochMilli(), adoption.position, adoption.sheepCount,
                    mapper.writeValueAsString(sheep.placements), sheep.version)
                // 기존 양의 스티커와 버전은 BE3의 최신 값을 유지한다.
            }
        }

        override fun saveSession(session: FeedSession) {
            ownedSession(userId, session.state.id)
            val state = session.state
            jdbc.update("""UPDATE feed_sessions SET status = ?, position = ?, furthest_position = ?, sequence = ?, writer_epoch = ?,
                last_request_position = ?, last_request_furthest = ?, closing_card_id = ?, closing_viewed_at_ms = ?
                WHERE id = ? AND user_id = ?""", state.status.value, state.position, state.furthestPosition, state.sequence, state.writerEpoch,
                session.lastProgress?.position, session.lastProgress?.furthestPosition, session.closingCardId,
                session.closingViewedAt?.toEpochMilli(), state.id, userId)
            session.items.filterIsInstance<StoredPause>().forEach {
                jdbc.update("UPDATE feed_items SET pause_action = ? WHERE id = ? AND session_id = ?", it.action?.value, it.id, state.id)
            }
            session.views.values.forEach {
                jdbc.update("""INSERT INTO feed_card_views(session_id, card_id, position, sheep_count, viewed_at_ms, view_order)
                    VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE position = ?, sheep_count = ?, viewed_at_ms = ?, view_order = ?""",
                    state.id, it.cardId, it.position, it.sheepCount, it.viewedAt.toEpochMilli(), it.order,
                    it.position, it.sheepCount, it.viewedAt.toEpochMilli(), it.order)
            }
        }

        override fun card(id: String) = cards.readLocked(userId, id)
        override fun cardAt(id: String, instant: java.time.Instant) = cards.at(id, instant)
        override fun colorKey(category: CategoryId): String? = jdbc.first("SELECT color_key FROM feed_category_colors WHERE category_id = ?", category.value) { it.getString("color_key") }
        override fun resumeReceipt(key: String) = jdbc.first("SELECT * FROM feed_resume_receipts WHERE user_id = ? AND key_hash = ?", userId, hash(key)) {
            ResumeReceipt(it.getString("session_id"), mapper.readValue(it.getString("response_json"), SessionResponse::class.java), it.instant("expires_at_ms"))
        }
        override fun saveResumeReceipt(key: String, receipt: ResumeReceipt) {
            val json = mapper.writeValueAsString(receipt.response)
            jdbc.update("""INSERT INTO feed_resume_receipts(user_id, key_hash, session_id, response_json, expires_at_ms) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE session_id = ?, response_json = ?, expires_at_ms = ?""", userId, hash(key), receipt.sessionId, json,
                receipt.expiresAt.toEpochMilli(), receipt.sessionId, json, receipt.expiresAt.toEpochMilli())
        }
        override fun beforeCommit(check: () -> Unit) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) = check()
            })
        }

        private fun nightRow(rs: ResultSet): NightRecord {
            val id = rs.getString("id")
            val reactions = jdbc.query("SELECT * FROM night_reactions WHERE night_id = ?", RowMapper { row, _ ->
                row.getString("card_id") to NightReaction(row.getBoolean("fluff_saved"), row.optionalInstant("song_saved_at_ms"))
            }, id).toMap()
            val adoption = jdbc.first("SELECT * FROM adopted_sheep WHERE night_id = ? AND user_id = ?", id, userId) { row ->
                val placements = mapper.readValue(row.getString("placements_json"), Array<Placement>::class.java).toList()
                AdoptionRecord(SheepResponse(row.getString("id"), id, row.getDate("night_date").toLocalDate(), row.getString("card_id"),
                    row.getString("color_key"), row.instant("created_at_ms"), placements, row.getInt("version")), row.optionalInt("position"), row.getInt("sheep_count"))
            }
            return NightRecord(id, userId, rs.getDate("night_date").toLocalDate(), rs.instant("closes_at_ms"), wire(rs.getString("category_id")),
                rs.getString("worry_text"), rs.optionalInstant("worry_saved_at_ms"), rs.getString("writing_state")?.let { wire<WritingState>(it) },
                rs.getLong("view_sequence"), reactions, rs.getBoolean("default_frozen"), rs.getString("default_card_id"), adoption,
                rs.getString("reflection_answer")?.let { ReflectionResponse(wire(it), rs.getString("reflection_message")) })
        }

        private fun sessionRow(rs: ResultSet): FeedSession {
            val id = rs.getString("id")
            val state = SessionResponse(id, rs.getString("night_id"), wire(rs.getString("status")), rs.getInt("sheep_count"),
                rs.getInt("feed_version"), rs.getInt("position"), rs.getInt("furthest_position"), rs.getLong("sequence"), rs.getLong("writer_epoch"),
                rs.instant("started_at_ms"), rs.instant("closes_at_ms"))
            val items = jdbc.query("SELECT * FROM feed_items WHERE session_id = ? ORDER BY position", RowMapper<StoredFeedItem> { row, _ ->
                val cardId = row.getString("card_id")
                if (cardId != null) StoredCard(row.getString("id"), row.getInt("position"), cardId)
                else StoredPause(row.getString("id"), row.getInt("position"), wire(row.getString("pause_kind")),
                    row.getString("pause_action")?.let { wire<PauseAction>(it) })
            }, id)
            val views = jdbc.query("SELECT * FROM feed_card_views WHERE session_id = ?", RowMapper { row, _ ->
                CardView(row.getString("card_id"), id, row.optionalInt("position"), row.getInt("sheep_count"), row.instant("viewed_at_ms"), row.getLong("view_order"))
            }, id).associateBy { it.cardId }
            val last = rs.optionalInt("last_request_position")?.let {
                ProgressRequest(it, rs.getInt("last_request_furthest"), state.sequence, state.writerEpoch)
            }
            return FeedSession(state, userId, items, last, views, rs.getString("closing_card_id"), rs.optionalInstant("closing_viewed_at_ms"))
        }
    }

    private data class Value<T>(val value: T)
    private fun hash(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
