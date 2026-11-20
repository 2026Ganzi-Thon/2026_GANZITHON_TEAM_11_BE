package com.yangsenunbam.feed

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.session.SessionResponse
import org.springframework.stereotype.Service

@Service
class FeedService(private val store: NightStore, private val time: NightTime) {
    fun feed(user: CurrentUser, sessionId: String): FeedResponse = store.withUser(user.id) { tx ->
        val session = tx.ownedSession(user.id, sessionId)
        val night = tx.ownedNight(user.id, session.state.nightId)
        FeedResponse(sessionId, session.state.feedVersion, session.state.sheepCount, session.items.map {
            when (it) {
                is StoredCard -> CardFeedItem(it.id, it.position, NightCards.render(tx, night, it.cardId))
                is StoredPause -> PauseFeedItem(it.id, it.position, PauseResponse(it.kind, it.action))
            }
        })
    }

    fun resume(user: CurrentUser, sessionId: String, idempotencyKey: String): SessionResponse = store.withUser(user.id) { tx ->
        val session = tx.ownedSession(user.id, sessionId)
        val now = time.now()
        tx.resumeReceipt(idempotencyKey)?.takeIf { now.isBefore(it.expiresAt) }?.let {
            if (it.sessionId != sessionId) throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "다른 세션에 사용한 멱등키입니다.")
            return@withUser it.response
        }
        requireActive(tx, session)
        val updated = session.copy(state = session.state.copy(writerEpoch = Math.addExact(session.state.writerEpoch, 1), sequence = 0), lastProgress = null)
        tx.saveSession(updated)
        tx.saveResumeReceipt(idempotencyKey, ResumeReceipt(sessionId, updated.state, now.plusSeconds(86_400)))
        updated.state
    }

    fun saveProgress(user: CurrentUser, sessionId: String, request: ProgressRequest): ProgressResponse = store.withUser(user.id) { tx ->
        val session = tx.ownedSession(user.id, sessionId)
        val night = tx.ownedNight(user.id, session.state.nightId)
        requireActive(tx, session)
        val update = ProgressPolicy.apply(night, session, request, time.now())
        if (update.session != session) {
            tx.saveSession(update.session)
            tx.saveNight(update.night)
        }
        update.session.progress()
    }

    fun handlePause(user: CurrentUser, sessionId: String, itemId: String, request: PauseRequest): PauseStateResponse = store.withUser(user.id) { tx ->
        val session = tx.ownedSession(user.id, sessionId)
        requireActive(tx, session)
        val item = session.items.filterIsInstance<StoredPause>().find { it.id == itemId }
        if (item == null || item.kind != PauseKind.ENTRUST_WORRY || item.position != session.state.position) {
            throw ApiException(ErrorCode.INVALID_PAUSE_ITEM, "현재 표시된 맡기기 항목이 아닙니다.")
        }
        if (item.action != null && item.action != request.action) {
            throw ApiException(ErrorCode.PAUSE_ALREADY_HANDLED, "이미 선택한 맡기기 응답은 바꿀 수 없습니다.")
        }
        if (item.action == null) tx.saveSession(session.copy(items = session.items.map {
            if (it.id == itemId) item.copy(action = request.action) else it
        }))
        PauseStateResponse(itemId, request.action)
    }

    fun acknowledgeClosing(user: CurrentUser, sessionId: String, request: ClosingViewRequest): Unit = store.withUser(user.id) { tx ->
        val session = tx.ownedSession(user.id, sessionId)
        val night = tx.ownedNight(user.id, session.state.nightId)
        time.guardOpen(tx, night.closesAt)
        if (!request.viewed) throw ApiException(ErrorCode.VALIDATION_ERROR, "표시한 마무리 카드만 확인할 수 있습니다.")
        val cardId = session.closingCardId
        if (session.state.status !in setOf(SessionStatus.COMPLETED, SessionStatus.STOPPED) || cardId == null) {
            throw ApiException(ErrorCode.CLOSING_NOT_AVAILABLE, "확인할 마무리 카드가 없습니다.")
        }
        requireAvailable(tx, cardId)
        if (session.closingViewedAt == null) {
            val now = time.now()
            // completed의 마지막 카드는 진행 저장 때 이미 기록한 칸을 유지한다.
            val position = if (session.state.status == SessionStatus.COMPLETED) session.state.sheepCount else null
            val latestOrder = maxOf(night.viewSequence, session.views.values.maxOfOrNull { it.order } ?: 0)
            val view = CardView(cardId, sessionId, position, session.state.sheepCount, now, latestOrder + 1)
            tx.saveSession(session.copy(closingViewedAt = now, views = session.views + (cardId to view)))
            tx.saveNight(night.copy(viewSequence = view.order))
        }
    }

    fun saveFluff(user: CurrentUser, nightId: String, cardId: String, request: SaveRequest): SaveResponse =
        saveReaction(user, nightId, cardId, request, song = false)

    fun saveSong(user: CurrentUser, nightId: String, cardId: String, request: SaveRequest): SaveResponse =
        saveReaction(user, nightId, cardId, request, song = true)

    private fun saveReaction(user: CurrentUser, nightId: String, cardId: String, request: SaveRequest, song: Boolean): SaveResponse =
        store.withUser(user.id) { tx ->
            val night = tx.ownedNight(user.id, nightId)
            if (!song || request.saved) time.guardOpen(tx, night.closesAt)
            if (NightCards.views(tx.sessions(nightId)).none { it.cardId == cardId }) {
                throw ApiException(ErrorCode.CARD_NOT_VIEWED, "이 밤에 표시한 카드만 저장할 수 있습니다.")
            }
            val card = requireAvailable(tx, cardId)
            if (song && card.kind != CardKind.SONG) throw ApiException(ErrorCode.NOT_SONG_CARD, "노래 카드만 담을 수 있습니다.")
            val previous = night.reactions[cardId] ?: NightReaction()
            val reaction = if (song) previous.copy(songSavedAt = if (request.saved) previous.songSavedAt ?: time.now() else null)
                else previous.copy(fluffSaved = request.saved)
            tx.saveNight(night.copy(reactions = night.reactions + (cardId to reaction)))
            SaveResponse(cardId, request.saved)
        }

    private fun requireActive(tx: NightTransaction, session: FeedSession) {
        if (session.state.status != SessionStatus.ACTIVE) throw ApiException(ErrorCode.SESSION_CLOSED, "종료된 세션입니다.")
        time.guardOpen(tx, session.state.closesAt, ErrorCode.SESSION_CLOSED)
    }

    private fun requireAvailable(tx: NightTransaction, cardId: String) = tx.card(cardId).also {
        if (!it.available) throw ApiException(ErrorCode.CARD_UNAVAILABLE, "삭제되었거나 사용할 수 없는 카드입니다.")
    }
}
