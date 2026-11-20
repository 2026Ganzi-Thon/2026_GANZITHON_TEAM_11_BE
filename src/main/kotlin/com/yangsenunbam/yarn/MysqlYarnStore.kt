package com.yangsenunbam.yarn

import com.yangsenunbam.feed.persistence.MysqlCardCatalog
import com.yangsenunbam.feed.persistence.first
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
@Profile("mysql")
class MysqlYarnStore(private val jdbc: JdbcTemplate, private val catalog: MysqlCardCatalog) : YarnStore {
    override fun lockCardAvailable(cardId: String): Boolean? {
        val exists = jdbc.queryForObject("SELECT COUNT(*) FROM feed_card_catalog WHERE id = ?", Int::class.java, cardId) == 1
        return if (exists) catalog.lockAvailable(cardId) else null
    }

    override fun hasFeedAccess(userId: String, cardId: String): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (
               SELECT 1 FROM feed_items i JOIN be1_sessions s ON s.id = i.session_id
               WHERE s.user_id = ? AND i.card_id = ?
           ) OR EXISTS (
               SELECT 1 FROM be1_sessions s
               WHERE s.user_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(s.closing_card_json, '$.id')) = ?
           )""",
        Boolean::class.java, userId, cardId, userId, cardId,
    ) == true

    override fun send(userId: String, cardId: String, at: Instant) {
        jdbc.update("INSERT IGNORE INTO feed_card_yarn(user_id, card_id) VALUES (?, ?)", userId, cardId)
        jdbc.update("""INSERT INTO yarn_events(user_id, card_id, first_sent_at_ms, canceled_at_ms) VALUES (?, ?, ?, NULL)
            ON DUPLICATE KEY UPDATE canceled_at_ms = NULL""", userId, cardId, at.toEpochMilli())
    }

    override fun cancel(userId: String, cardId: String, at: Instant) {
        jdbc.update("DELETE FROM feed_card_yarn WHERE user_id = ? AND card_id = ?", userId, cardId)
        jdbc.update("UPDATE yarn_events SET canceled_at_ms = ? WHERE user_id = ? AND card_id = ? AND canceled_at_ms IS NULL",
            at.toEpochMilli(), userId, cardId)
    }

    override fun tally(authorId: String, start: Instant, end: Instant): YarnTally = jdbc.first(
        """SELECT COUNT(*) AS yarn_count, COUNT(DISTINCT e.user_id) AS sender_count
           FROM yarn_events e JOIN feed_card_catalog c ON c.id = e.card_id
           WHERE c.author_user_id = ? AND c.author_type = 'user' AND c.available = TRUE
             AND e.user_id <> c.author_user_id
             AND e.first_sent_at_ms >= ? AND e.first_sent_at_ms < ?
             AND (e.canceled_at_ms IS NULL OR e.canceled_at_ms >= ?)""",
        authorId, start.toEpochMilli(), end.toEpochMilli(), end.toEpochMilli(),
    ) { YarnTally(it.getLong("yarn_count"), it.getLong("sender_count")) } ?: YarnTally(0, 0)

    override fun summary(userId: String, date: LocalDate): YarnSummaryRow? = jdbc.first(
        "SELECT id, seen_at_ms FROM yarn_summaries WHERE user_id = ? AND summary_date = ?", userId, Date.valueOf(date),
    ) { YarnSummaryRow(it.getString("id"), it.getObject("seen_at_ms")?.let { _ -> Instant.ofEpochMilli(it.getLong("seen_at_ms")) }) }

    override fun createSummary(userId: String, date: LocalDate): YarnSummaryRow {
        jdbc.update("INSERT IGNORE INTO yarn_summaries(id, user_id, summary_date, seen_at_ms) VALUES (?, ?, ?, NULL)",
            "ys_${UUID.randomUUID()}", userId, Date.valueOf(date))
        return summary(userId, date) ?: error("yarn summary was not created")
    }

    override fun markSeen(userId: String, summaryId: String, at: Instant): Boolean {
        val owned = jdbc.queryForObject("SELECT COUNT(*) FROM yarn_summaries WHERE id = ? AND user_id = ?",
            Int::class.java, summaryId, userId) == 1
        if (owned) {
            jdbc.update("UPDATE yarn_summaries SET seen_at_ms = ? WHERE id = ? AND seen_at_ms IS NULL", at.toEpochMilli(), summaryId)
        }
        return owned
    }
}
