package com.yangsenunbam.feed.persistence

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.feed.ProgressRequest
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.*
import com.yangsenunbam.flock.Placement
import com.yangsenunbam.flock.SheepResponse
import com.yangsenunbam.morning.ReflectionResponse
import com.yangsenunbam.session.SessionResponse
import org.springframework.context.annotation.Profile
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
import java.time.Instant
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

    /** BE1이 방금 생성한 세션의 피드를 같은 트랜잭션에 저장한다. */
    fun saveFeed(sessionId: String, items: List<StoredFeedItem>) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        items.forEach { item ->
            jdbc.update("INSERT INTO feed_items(id, session_id, position, card_id, pause_kind) VALUES (?, ?, ?, ?, ?)",
                item.id, sessionId, item.position, (item as? StoredCard)?.cardId, (item as? StoredPause)?.kind?.value)
        }
    }

    private inner class Transaction(private val userId: String) : NightTransaction {
        override fun night(id: String) = jdbc.first("$nightSelect WHERE n.id = ? AND n.user_id = ?", id, userId) { nightRow(it) }
        override fun nightOn(date: LocalDate) = jdbc.first("$nightSelect WHERE n.night_date = ? AND n.user_id = ?", date, userId) { nightRow(it) }
        override fun session(id: String) = jdbc.first("$sessionSelect WHERE s.id = ? AND s.user_id = ? FOR UPDATE OF s", id, userId) { sessionRow(it) }
        override fun sessions(nightId: String) = jdbc.query(
            "$sessionSelect WHERE s.night_id = ? AND s.user_id = ? ORDER BY s.started_at_ms, s.id FOR UPDATE OF s",
            RowMapper { rs, _ -> sessionRow(rs) }, nightId, userId)

        override fun saveNight(night: NightRecord) {
            val current = ownedNight(userId, night.id)
            if (night.writingState == WritingState.DEFERRED && current.writingState in setOf(WritingState.OFFERED, WritingState.WRITING)) {
                jdbc.update("UPDATE be1_nights SET writing_state = ? WHERE id = ? AND user_id = ?",
                    WritingState.DEFERRED.value, night.id, userId)
            }
            val state = arrayOf<Any?>(night.viewSequence, night.defaultFrozen, night.defaultCardId, night.reflection?.answer?.value, night.reflection?.message)
            jdbc.update("""INSERT INTO night_feed_state
                (night_id, view_sequence, default_frozen, default_card_id, reflection_answer, reflection_message)
                VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE view_sequence = ?, default_frozen = ?,
                default_card_id = ?, reflection_answer = ?, reflection_message = ?""", night.id, *state, *state)
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
            val state = session.state
            val entrusted = session.items.filterIsInstance<StoredPause>().count { it.action == PauseAction.ENTRUSTED }
            jdbc.update("""UPDATE be1_sessions SET status = ?, position = ?, furthest_position = ?, sequence = ?,
                writer_epoch = ?, closing_viewed_at_ms = ?, entrusted_count = ? WHERE id = ? AND user_id = ?""",
                state.status.value, state.position, state.furthestPosition, state.sequence, state.writerEpoch,
                session.closingViewedAt?.toEpochMilli(), entrusted, state.id, userId)
            val progress = arrayOf<Any?>(state.furthestPosition, state.sequence, session.lastProgress?.position, session.lastProgress?.furthestPosition)
            jdbc.update("""INSERT INTO feed_session_progress
                (session_id, recorded_furthest, recorded_sequence, last_request_position, last_request_furthest)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE recorded_furthest = ?, recorded_sequence = ?,
                last_request_position = ?, last_request_furthest = ?""", state.id, *progress, *progress)
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
            val date = rs.getDate("night_date").toLocalDate()
            val closesAt = date.plusDays(1).atTime(4, 0).atZone(NightTime.SEOUL).toInstant()
            val worry = rs.getString("worry_text")
            return NightRecord(id, userId, date, closesAt, wire(rs.getString("category_id")),
                worry, rs.optionalInstant("worry_saved_at_ms").takeIf { worry != null }, rs.getString("writing_state")?.let { wire<WritingState>(it) },
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
            if (items.size != state.sheepCount) {
                throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "세션에 저장된 피드를 찾을 수 없어요.")
            }
            val views = jdbc.query("SELECT * FROM feed_card_views WHERE session_id = ?", RowMapper { row, _ ->
                CardView(row.getString("card_id"), id, row.optionalInt("position"), row.getInt("sheep_count"), row.instant("viewed_at_ms"), row.getLong("view_order"))
            }, id).associateBy { it.cardId }
            val recordedSequence = rs.getLong("recorded_sequence")
            val last = rs.optionalInt("last_request_position")?.let {
                ProgressRequest(it, rs.getInt("last_request_furthest"), recordedSequence, state.writerEpoch)
            }
            val closingCardId = rs.getString("closing_card_json")?.let { mapper.readTree(it).path("id").asString() }
            val finishedAt = rs.optionalInstant("finished_at_ms")
            val currentViews = if (finishedAt != null && state.sequence > recordedSequence) {
                finishedViews(state, items, views, rs.getInt("recorded_furthest"), finishedAt)
            } else views
            return FeedSession(state, userId, items, last, currentViews, closingCardId, rs.optionalInstant("closing_viewed_at_ms"))
        }
    }

    /** BE1 finish may carry a final progress update that never went through /progress. */
    private fun finishedViews(
        state: SessionResponse, items: List<StoredFeedItem>, views: Map<String, CardView>,
        previousFurthest: Int, finishedAt: Instant,
    ): Map<String, CardView> {
        val displayed = items.singleOrNull { it.position == state.position }
        val newlyReached = items.filter { it.position in (previousFurthest + 1)..state.furthestPosition && it != displayed }
        val result = views.toMutableMap()
        var order = views.values.maxOfOrNull { it.order } ?: 0
        (newlyReached + listOfNotNull(displayed)).filterIsInstance<StoredCard>().forEach { item ->
            result[item.cardId] = CardView(item.cardId, state.id, item.position, state.sheepCount, finishedAt, ++order)
        }
        return result
    }

    private val nightSelect = """SELECT n.*, f.view_sequence, f.default_frozen, f.default_card_id,
        f.reflection_answer, f.reflection_message FROM be1_nights n LEFT JOIN night_feed_state f ON f.night_id = n.id"""
    private val sessionSelect = """SELECT s.*, p.recorded_furthest, p.recorded_sequence,
        p.last_request_position, p.last_request_furthest FROM be1_sessions s LEFT JOIN feed_session_progress p ON p.session_id = s.id"""

    private data class Value<T>(val value: T)
    private fun hash(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
