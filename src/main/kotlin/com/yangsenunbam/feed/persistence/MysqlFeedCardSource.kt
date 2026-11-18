package com.yangsenunbam.feed.persistence

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.port.FeedCardSelection
import com.yangsenunbam.feed.port.FeedCardSource
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Repository
@Profile("mysql")
class MysqlFeedCardSource(private val jdbc: JdbcTemplate, private val cards: MysqlCardCatalog) : FeedCardSource {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun select(request: FeedCardSelection): CardResponse? {
        val conditions = mutableListOf("c.available = TRUE", "c.kind = ?")
        val args = mutableListOf<Any?>(if (request.slot.song) "song" else "text")
        if (!request.slot.song) { conditions += "c.tone = ?"; args += request.slot.tone?.value }
        if (!request.allowAi) conditions += "c.author_type <> 'ai'"
        if (request.slot.song && request.userSongsOnly) conditions += "c.author_type = 'user'"
        val repeated = if (request.excludedCardIds.isEmpty()) "" else {
            args.addAll(request.excludedCardIds)
            "CASE WHEN c.id IN (${request.excludedCardIds.joinToString(",") { "?" }}) THEN 1 ELSE 0 END,"
        }
        args += request.category.value
        val aiPriority = if (request.slot.song && request.allowAi) "CASE WHEN c.author_type = 'ai' THEN 0 ELSE 1 END," else ""
        val ids = jdbc.queryForList("""SELECT c.id FROM feed_card_catalog c
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY $repeated CASE WHEN c.category_id = ? THEN 0 ELSE 1 END, $aiPriority
                (SELECT COUNT(*) FROM feed_card_yarn y WHERE y.card_id = c.id) DESC, c.id
            LIMIT 100""", String::class.java, *args.toTypedArray())
        return ids.filterNotNull().asSequence().map { cards.readLocked(request.userId, it) }.firstOrNull { it.available }
    }
}
