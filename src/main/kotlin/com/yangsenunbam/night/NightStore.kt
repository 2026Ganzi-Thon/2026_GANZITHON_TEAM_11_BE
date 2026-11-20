package com.yangsenunbam.night

import com.yangsenunbam.common.*
import com.yangsenunbam.auth.AuthStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.sql.Date
import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8

/** BE1 owns one night per user and Korean night_date. BE2 owns feed/read history. */
data class NightRecord(
    val id: String, val userId: String, val nightDate: LocalDate, val category: CategoryId,
    val worryText: String?, val worrySavedAtMs: Long, val worryDeleted: Boolean,
    val supportTriggered: Boolean, val supportJson: String?, val supportContinuedAtMs: Long?,
    val writingState: WritingState?,
)

@Repository
class NightStore(private val jdbc: JdbcTemplate, private val auth: AuthStore) {
    private val cols = "id, user_id, night_date, category_id, worry_text, worry_saved_at_ms, worry_deleted, " +
        "support_triggered, support_json, support_continued_at_ms, writing_state"
    private val mapper = RowMapper<NightRecord> { rs, _ ->
        val v = rs.getLong("support_continued_at_ms")
        val continued = if (rs.wasNull()) null else v
        NightRecord(
            rs.getString("id"), rs.getString("user_id"), rs.getDate("night_date").toLocalDate(),
            CategoryId.entries.first { it.value == rs.getString("category_id") },
            rs.getString("worry_text"), rs.getLong("worry_saved_at_ms"), rs.getBoolean("worry_deleted"),
            rs.getBoolean("support_triggered"), rs.getString("support_json"), continued,
            rs.getString("writing_state")?.let { value -> WritingState.entries.first { it.value == value } },
        )
    }

    fun lockAccount(userId: String) {
        if (auth.lockUser(userId) == null) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "계정을 찾을 수 없어요.")
        }
    }

    fun byDate(userId: String, date: LocalDate, lock: Boolean = false): NightRecord? = jdbc.query(
        "SELECT $cols FROM be1_nights WHERE user_id = ? AND night_date = ?" + if (lock) " FOR UPDATE" else "",
        mapper, userId, Date.valueOf(date),
    ).firstOrNull()

    fun owned(userId: String, nightId: String, lock: Boolean = false): NightRecord = jdbc.query(
        "SELECT $cols FROM be1_nights WHERE user_id = ? AND id = ?" + if (lock) " FOR UPDATE" else "",
        mapper, userId, nightId,
    ).firstOrNull() ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "밤 기록을 찾을 수 없어요.")

    fun create(night: NightRecord) {
        jdbc.update("""INSERT INTO be1_nights
            (id,user_id,night_date,category_id,worry_text,worry_saved_at_ms,worry_deleted,support_triggered,support_json,support_continued_at_ms,writing_state)
            VALUES (?,?,?,?,?,?,FALSE,?,?,NULL,NULL)""".trimIndent(),
            night.id, night.userId, Date.valueOf(night.nightDate), night.category.value,
            night.worryText, night.worrySavedAtMs, night.supportTriggered, night.supportJson)
    }

    fun continueAfterSupport(id: String, nowMs: Long) = jdbc.update(
        "UPDATE be1_nights SET support_continued_at_ms = ? WHERE id = ? AND support_continued_at_ms IS NULL", nowMs, id,
    )

    fun writingState(id: String, state: WritingState) = jdbc.update(
        "UPDATE be1_nights SET writing_state = ? WHERE id = ?", state.value, id,
    )

    fun deleteWorry(id: String) = jdbc.update(
        "UPDATE be1_nights SET worry_text = NULL, worry_deleted = TRUE WHERE id = ?", id,
    )

    fun countWorries(userId: String): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM be1_nights WHERE user_id = ? AND worry_text IS NOT NULL", Long::class.java, userId,
    ) ?: 0L

    // Cursor is an opaque date|id pair: no raw private worry text is encoded.
    fun worries(userId: String, cursor: String?, limit: Int): PageResponse<com.yangsenunbam.user.WorryListItem> {
        if (limit !in 1..100) throw ApiException(ErrorCode.VALIDATION_ERROR, "limit은 1~100이에요.")
        val decoded = try { cursor?.let { String(Base64.getUrlDecoder().decode(it), UTF_8) } } catch (_: Exception) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 cursor예요.")
        }
        val components = decoded?.split('|')
        if (components != null && (components.size != 2 || components[1].length != 36)) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 cursor예요.")
        }
        val date = try { components?.let { LocalDate.parse(it[0]) } } catch (_: Exception) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 cursor예요.")
        }
        val sql = "SELECT $cols FROM be1_nights WHERE user_id = ? AND worry_text IS NOT NULL" +
            if (date == null) " ORDER BY night_date DESC, id DESC LIMIT ?" else
                " AND (night_date < ? OR (night_date = ? AND id < ?)) ORDER BY night_date DESC, id DESC LIMIT ?"
        val results = if (date == null) jdbc.query(sql, mapper, userId, limit + 1) else
            jdbc.query(sql, mapper, userId, Date.valueOf(date), Date.valueOf(date), components!![1], limit + 1)
        val selected = results.take(limit)
        val next = if (results.size > limit) selected.lastOrNull()?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString("${it.nightDate}|${it.id}".toByteArray(UTF_8))
        } else null
        return PageResponse(selected.map { com.yangsenunbam.user.WorryListItem(it.id, it.nightDate, it.category, it.worryText!!) }, next)
    }

    fun deleteByUser(userId: String) = jdbc.update("DELETE FROM be1_nights WHERE user_id = ?", userId)
}
