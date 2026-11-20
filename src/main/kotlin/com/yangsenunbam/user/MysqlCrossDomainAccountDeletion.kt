package com.yangsenunbam.user

import com.yangsenunbam.auth.AuthStore
import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.feed.persistence.MysqlCardCatalog
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** V1–V5 shared schema. New ownership tables must extend this adapter.
 * UserService deletes BE1 sessions/nights afterwards; their V3 children cascade.
 * Card tombstones must survive references from other users' feeds and adopted sheep.
 */
@Component
@Profile("mysql")
@Transactional(propagation = Propagation.MANDATORY)
class MysqlCrossDomainAccountDeletion(
    private val jdbc: JdbcTemplate,
    private val auth: AuthStore,
    private val catalog: MysqlCardCatalog,
    private val clock: Clock,
) : CrossDomainAccountDeletionPort {
    override fun deleteOwnedData(userId: String) {
        if (auth.lockUser(userId) == null) throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "계정이 없어요.")
        // BE3 moderation locks card_submissions before feed_card_catalog; keep the same order here.
        jdbc.queryForList("SELECT id FROM card_submissions WHERE user_id = ? ORDER BY id FOR UPDATE", String::class.java, userId)
        // Acquire own domain locks before card locks, including rows CASCADE will delete.
        jdbc.queryForList("SELECT id FROM be1_nights WHERE user_id = ? ORDER BY id FOR UPDATE", String::class.java, userId)
        val cardIds = jdbc.queryForList(
            "SELECT id FROM feed_card_catalog WHERE author_user_id = ? ORDER BY id", String::class.java, userId).filterNotNull()
        // Closing responses contain a saved body even in another user's session.
        // Lock those sessions BEFORE cards, without acquiring another user's account lock.
        val snapshotIds = snapshots(cardIds)
        val sessionIds = (snapshotIds + jdbc.queryForList(
            "SELECT id FROM be1_sessions WHERE user_id = ?", String::class.java, userId).filterNotNull()).toSortedSet()
        sessionIds.forEach { id ->
            jdbc.queryForList("SELECT id FROM be1_sessions WHERE id = ? FOR UPDATE", String::class.java, id)
        }
        cardIds.forEach { id ->
            jdbc.queryForList("SELECT id FROM feed_card_catalog WHERE id = ? FOR UPDATE", String::class.java, id)
        }
        // A finish may have committed between discovery and the card locks. Retry the
        // whole deletion instead of acquiring a new session lock after a card lock.
        val currentSnapshots = snapshots(cardIds)
        if (!sessionIds.containsAll(currentSnapshots)) {
            throw ApiException(ErrorCode.REQUEST_IN_PROGRESS, "카드 열람 기록이 갱신 중이에요. 탈퇴를 다시 시도해 주세요.")
        }
        currentSnapshots.forEach { id ->
            jdbc.update("""UPDATE be1_sessions SET closing_card_json = JSON_SET(closing_card_json,
                '$.content', NULL, '$.author_label', NULL, '$.available', FALSE) WHERE id = ?""", id)
        }
        cardIds.forEach { cardId ->
            catalog.remove(cardId, clock.instant())
            // Already unavailable cards also lose any remaining identity/content/yarn.
            jdbc.update("UPDATE feed_card_catalog SET available = FALSE, content_json = NULL, author_user_id = NULL, author_label = NULL WHERE id = ?", cardId)
            jdbc.update("DELETE FROM feed_card_yarn WHERE card_id = ?", cardId)
        }
        jdbc.update("DELETE FROM feed_card_yarn WHERE user_id = ?", userId)
        // BE3 (V5): submissions incl. unpublished text, idempotency replays, yarn history and summaries.
        cardIds.forEach { jdbc.update("DELETE FROM yarn_events WHERE card_id = ?", it) }
        jdbc.update("DELETE FROM yarn_events WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM yarn_summaries WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM card_idempotency WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM card_submissions WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM feed_resume_receipts WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM adopted_sheep WHERE user_id = ?", userId)
    }

    private fun snapshots(cardIds: List<String>): List<String> {
        if (cardIds.isEmpty()) return emptyList()
        val placeholders = cardIds.joinToString(",") { "?" }
        return jdbc.queryForList("""SELECT id FROM be1_sessions
            WHERE JSON_UNQUOTE(JSON_EXTRACT(closing_card_json, '$.id')) IN ($placeholders)
            ORDER BY id""", String::class.java, *cardIds.toTypedArray()).filterNotNull()
    }
}
