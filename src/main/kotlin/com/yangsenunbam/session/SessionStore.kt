package com.yangsenunbam.session

import com.yangsenunbam.common.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Contract: BE2 updates progress and viewed counts in this BE1 session row in the SAME database. */
data class SessionRecord(
    val id: String, val nightId: String, val userId: String, val status: SessionStatus,
    val sheepCount: Int, val feedVersion: Int, val position: Int, val furthestPosition: Int,
    val sequence: Long, val writerEpoch: Long, val startedAtMs: Long, val closesAtMs: Long,
    val finishedAtMs: Long?, val finishReason: FinishReason?, val finishPosition: Int?,
    val finishSequence: Long?, val finishWriterEpoch: Long?, val closingCardJson: String?,
    val closingViewedAtMs: Long?, val entrustedCount: Int, val fluffCount: Int, val yarnSentCount: Int,
) {
    fun response() = SessionResponse(id, nightId, status, sheepCount, feedVersion,
        position, furthestPosition, sequence, writerEpoch, Instant.ofEpochMilli(startedAtMs), Instant.ofEpochMilli(closesAtMs))
}

data class SessionReplay(val fingerprint: String, val sessionId: String)

@Repository
class SessionStore(private val jdbc: JdbcTemplate) {
    private val cols = "id,night_id,user_id,status,sheep_count,feed_version,position,furthest_position," +
        "sequence,writer_epoch,started_at_ms,closes_at_ms,finished_at_ms,finish_reason,finish_position," +
        "finish_sequence,finish_writer_epoch,closing_card_json,closing_viewed_at_ms,entrusted_count,fluff_count,yarn_sent_count"
    private val mapper = RowMapper<SessionRecord> { rs, _ ->
        fun optionalLong(column: String): Long? {
            val v = rs.getLong(column)
            return if (rs.wasNull()) null else v
        }
        fun optionalInt(column: String): Int? {
            val v = rs.getInt(column)
            return if (rs.wasNull()) null else v
        }
        SessionRecord(
            rs.getString("id"), rs.getString("night_id"), rs.getString("user_id"),
            SessionStatus.entries.first { it.value == rs.getString("status") },
            rs.getInt("sheep_count"), rs.getInt("feed_version"), rs.getInt("position"),
            rs.getInt("furthest_position"), rs.getLong("sequence"), rs.getLong("writer_epoch"),
            rs.getLong("started_at_ms"), rs.getLong("closes_at_ms"), optionalLong("finished_at_ms"),
            rs.getString("finish_reason")?.let { x -> FinishReason.entries.first { it.value == x } },
            optionalInt("finish_position"), optionalLong("finish_sequence"), optionalLong("finish_writer_epoch"),
            rs.getString("closing_card_json"), optionalLong("closing_viewed_at_ms"),
            rs.getInt("entrusted_count"), rs.getInt("fluff_count"), rs.getInt("yarn_sent_count"),
        )
    }

    fun owned(userId: String, sessionId: String, lock: Boolean = false): SessionRecord = jdbc.query(
        "SELECT $cols FROM be1_sessions WHERE id = ? AND user_id = ?" + if (lock) " FOR UPDATE" else "",
        mapper, sessionId, userId,
    ).firstOrNull() ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "세션을 찾을 수 없어요.")

    fun activeForNight(nightId: String): SessionRecord? = jdbc.query(
        "SELECT $cols FROM be1_sessions WHERE night_id = ? AND status = 'active' " +
            "ORDER BY started_at_ms DESC, id DESC LIMIT 1", mapper, nightId,
    ).firstOrNull()

    fun latestForNight(nightId: String): SessionRecord? = jdbc.query(
        "SELECT $cols FROM be1_sessions WHERE night_id = ? ORDER BY started_at_ms DESC, id DESC LIMIT 1",
        mapper, nightId,
    ).firstOrNull()

    fun countForNight(nightId: String): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM be1_sessions WHERE night_id = ?", Long::class.java, nightId,
    ) ?: 0L

    fun create(userId: String, nightId: String, count: Int, feedVersion: Int, startedAt: Long, closesAt: Long,
        id: String = UUID.randomUUID().toString()) {
        jdbc.update("""INSERT INTO be1_sessions
            (id,night_id,user_id,status,sheep_count,feed_version,position,furthest_position,sequence,writer_epoch,started_at_ms,closes_at_ms,
            finished_at_ms,finish_reason,finish_position,finish_sequence,finish_writer_epoch,closing_card_json,closing_viewed_at_ms,entrusted_count,fluff_count,yarn_sent_count)
            VALUES(?,?,?,'active',?,?,0,0,0,1,?,?,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,0,0)""".trimIndent(),
            id, nightId, userId, count, feedVersion, startedAt, closesAt)
    }

    fun finish(record: SessionRecord, reason: FinishReason, position: Int, furthest: Int, sequence: Long,
        epoch: Long, finishedAt: Long, closingJson: String, status: FinishedStatus, fluffCount: Int) = jdbc.update(
        """UPDATE be1_sessions SET status=?, position=?, furthest_position=?, sequence=?, finished_at_ms=?, finish_reason=?,
          finish_position=?, finish_sequence=?, finish_writer_epoch=?, closing_card_json=?, fluff_count=?
          WHERE id=? AND status='active'""".trimIndent(),
        status.value, position, furthest, sequence, finishedAt, reason.value, position, sequence, epoch, closingJson, fluffCount, record.id,
    )

    fun expire(nowMs: Long) = jdbc.update(
        "UPDATE be1_sessions SET status='expired' WHERE status='active' AND closes_at_ms <= ?", nowMs,
    )

    fun expireForUser(userId: String, nowMs: Long) = jdbc.update(
        "UPDATE be1_sessions SET status='expired' WHERE user_id = ? AND status='active' AND closes_at_ms <= ?",
        userId, nowMs,
    )

    fun markClosingViewed(sessionId: String, nowMs: Long) = jdbc.update(
        "UPDATE be1_sessions SET closing_viewed_at_ms = ? WHERE id = ? AND closing_viewed_at_ms IS NULL", nowMs, sessionId,
    )

    fun countDistinctNights(userId: String): Long = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT night_id) FROM be1_sessions WHERE user_id = ?", Long::class.java, userId,
    ) ?: 0L

    fun sheepSum(userId: String): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(furthest_position),0) FROM be1_sessions WHERE user_id = ?", Long::class.java, userId,
    ) ?: 0L

    fun findReplay(userId: String, operation: String, key: String, nowMs: Long): SessionReplay? {
        jdbc.update("DELETE FROM be1_session_replays WHERE user_id=? AND operation=? AND idem_key=? AND expires_at_ms<=?",
            userId, operation, key, nowMs)
        return jdbc.query("SELECT fingerprint,session_id FROM be1_session_replays " +
            "WHERE user_id=? AND operation=? AND idem_key=?", RowMapper { rs, _ ->
                SessionReplay(rs.getString("fingerprint"), rs.getString("session_id"))
            }, userId, operation, key).firstOrNull()
    }

    fun saveReplay(userId: String, operation: String, key: String, fingerprint: String, sessionId: String, nowMs: Long) {
        jdbc.update("INSERT INTO be1_session_replays(user_id,operation,idem_key,fingerprint,session_id,expires_at_ms) " +
            "VALUES(?,?,?,?,?,?)", userId, operation, key, fingerprint, sessionId, nowMs + 86_400_000L)
    }

    fun deleteByUser(userId: String) {
        jdbc.update("DELETE FROM be1_session_replays WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM be1_sessions WHERE user_id = ?", userId)
    }
}
