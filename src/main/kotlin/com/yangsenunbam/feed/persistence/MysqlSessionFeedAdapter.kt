package com.yangsenunbam.feed.persistence

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.FeedBuilder
import com.yangsenunbam.feed.FeedSlot
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.feed.model.StoredCard
import com.yangsenunbam.feed.port.FeedCardSelection
import com.yangsenunbam.feed.port.FeedCardSource
import com.yangsenunbam.feed.port.ownedNight
import com.yangsenunbam.feed.port.ownedSession
import com.yangsenunbam.session.ClosingCardPort
import com.yangsenunbam.session.ClosingCardVisibilityPort
import com.yangsenunbam.session.FeedPreparationPort
import com.yangsenunbam.session.SessionStore
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Profile("mysql")
@Transactional(propagation = Propagation.MANDATORY)
class MysqlSessionFeedAdapter(
    private val store: MysqlNightStore,
    private val sessions: SessionStore,
    private val builder: FeedBuilder,
    private val source: FeedCardSource,
    private val catalog: MysqlCardCatalog,
    private val jdbc: JdbcTemplate,
    private val time: NightTime,
) : FeedPreparationPort, ClosingCardPort, ClosingCardVisibilityPort {
    override fun prepare(userId: String, nightId: String, sessionId: String, sheepCount: Int): Int = store.withUser(userId) { tx ->
        val session = sessions.owned(userId, sessionId, lock = true)
        val night = tx.ownedNight(userId, nightId)
        check(session.nightId == night.id && session.sheepCount == sheepCount && session.status == SessionStatus.ACTIVE)
        time.guardOpen(tx, night.closesAt)
        store.saveFeed(sessionId, builder.build(userId, sheepCount, night.categoryId))
        session.feedVersion
    }

    override fun canFinishStopPause(sessionId: String, position: Int): Boolean = jdbc.first(
        "SELECT pause_kind FROM feed_items WHERE session_id = ? AND position = ?", sessionId, position,
    ) { it.getString("pause_kind") } == PauseKind.STOP_OFFER.value

    override fun countSavedFluff(nightId: String, sessionId: String, furthestPosition: Int): Int = jdbc.queryForObject(
        """SELECT COUNT(DISTINCT i.card_id)
            FROM feed_items i
            JOIN night_reactions r ON r.card_id = i.card_id AND r.night_id = ? AND r.fluff_saved = TRUE
            JOIN feed_card_catalog c ON c.id = i.card_id AND c.available = TRUE
            WHERE i.session_id = ? AND i.position <= ?""",
        Int::class.java, nightId, sessionId, furthestPosition,
    ) ?: 0

    override fun choose(userId: String, nightId: String, sessionId: String, reason: FinishReason): CardResponse = store.withUser(userId) { tx ->
        val session = tx.ownedSession(userId, sessionId)
        val night = tx.ownedNight(userId, nightId)
        time.guardOpen(tx, session.state.closesAt, ErrorCode.SESSION_CLOSED)
        if (reason == FinishReason.COMPLETED) {
            val last = session.items.lastOrNull() as? StoredCard ?: unavailable()
            return@withUser tx.card(last.cardId).takeIf { it.available } ?: unavailable()
        }
        val alreadySeen = tx.sessions(nightId).flatMap { it.views.keys + listOfNotNull(it.closingCardId) }.toSet()
        source.select(FeedCardSelection(userId, night.categoryId, FeedSlot(session.state.position, CardTone.WARM),
            alreadySeen, allowAi = false, userSongsOnly = false)) ?: unavailable()
    }

    override fun lockAndCheckAvailable(cardId: String): Boolean = catalog.lockAvailable(cardId)

    private fun unavailable(): Nothing = throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "마무리 카드가 준비되지 않았어요.")
}
