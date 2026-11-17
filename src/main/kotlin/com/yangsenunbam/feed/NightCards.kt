package com.yangsenunbam.feed

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.card.CardViewer
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.NightTransaction

object NightCards {
    fun views(sessions: List<FeedSession>): List<CardView> = sessions.flatMap { session ->
        session.views.values.filter { view ->
            if (view.position == null) {
                session.closingViewedAt != null && session.closingCardId == view.cardId
            } else {
                view.position <= session.state.furthestPosition && session.items.any {
                    it is StoredCard && it.position == view.position && it.cardId == view.cardId
                }
            }
        }
    }.groupBy { it.cardId }.values.map { views ->
        views.maxWith(compareBy<CardView> { it.viewedAt }.thenBy { it.order }.thenBy { it.sessionId })
    }

    fun render(tx: NightTransaction, night: NightRecord, cardId: String): CardResponse {
        val card = tx.card(cardId)
        if (!card.available) return card.copy(content = null, viewer = CardViewer(false, false, false))
        val reaction = night.reactions[cardId] ?: NightReaction()
        return card.copy(viewer = card.viewer.copy(fluffSaved = reaction.fluffSaved, songSaved = reaction.songSavedAt != null))
    }
}
