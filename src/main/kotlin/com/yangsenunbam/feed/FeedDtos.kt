package com.yangsenunbam.feed

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.PauseAction
import com.yangsenunbam.common.PauseKind
import jakarta.validation.constraints.AssertTrue

data class FeedResponse(val sessionId: String, val feedVersion: Int, val totalCount: Int, val items: List<FeedItemResponse>)
sealed interface FeedItemResponse {
    val id: String
    val position: Int
    val type: String
}
data class CardFeedItem(override val id: String, override val position: Int, val card: CardResponse) : FeedItemResponse {
    override val type: String = "card"
}
data class PauseFeedItem(override val id: String, override val position: Int, val pause: PauseResponse) : FeedItemResponse {
    override val type: String = "pause"
}
data class PauseResponse(val kind: PauseKind, val action: PauseAction?)
data class PauseRequest(val action: PauseAction)
data class PauseStateResponse(val itemId: String, val action: PauseAction)
data class ClosingViewRequest(@field:AssertTrue val viewed: Boolean)
data class SaveRequest(val saved: Boolean)
data class SaveResponse(val cardId: String, val saved: Boolean)
