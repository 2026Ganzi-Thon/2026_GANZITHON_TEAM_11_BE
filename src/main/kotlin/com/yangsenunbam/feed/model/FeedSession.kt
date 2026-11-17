package com.yangsenunbam.feed.model

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.ProgressRequest
import com.yangsenunbam.feed.ProgressResponse
import com.yangsenunbam.session.SessionResponse
import java.time.Instant

/** BE1은 생성/종료 시 같은 세션과 열람 기록을 원자적으로 저장해야 한다. */
data class FeedSession(
    val state: SessionResponse,
    val userId: String,
    val items: List<StoredFeedItem>,
    val lastProgress: ProgressRequest? = null,
    val views: Map<String, CardView> = emptyMap(),
    val closingCardId: String? = null,
    val closingViewedAt: Instant? = null,
) {
    init {
        require(state.sheepCount in 3..30)
        require(items.size == state.sheepCount)
        require(items.map { it.position } == (1..state.sheepCount).toList())
        require(items.map { it.id }.distinct().size == items.size)
    }

    fun progress() = ProgressResponse(state.position, state.furthestPosition, state.sequence, state.writerEpoch)
}

sealed interface StoredFeedItem {
    val id: String
    val position: Int
}

data class StoredCard(override val id: String, override val position: Int, val cardId: String) : StoredFeedItem
data class StoredPause(
    override val id: String, override val position: Int, val kind: PauseKind, val action: PauseAction? = null,
) : StoredFeedItem

data class CardView(
    val cardId: String, val sessionId: String, val position: Int?, val sheepCount: Int,
    val viewedAt: Instant, val order: Long,
)

data class ResumeReceipt(val sessionId: String, val response: SessionResponse, val expiresAt: Instant)
