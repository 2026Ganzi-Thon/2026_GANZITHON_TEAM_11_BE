package com.yangsenunbam.feed.port

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.CategoryId
import com.yangsenunbam.feed.FeedSlot

/**
 * BE3의 검수 완료 카드만 반환한다. 노래의 실재 확인과 인기순 선정도 어댑터가 담당한다.
 * AI 추천 실패 시에는 검수된 곡을 조회하고, 사용할 콘텐츠가 없으면 null을 반환한다.
 */
fun interface FeedCardSource {
    fun select(request: FeedCardSelection): CardResponse?
}

data class FeedCardSelection(
    val userId: String, val category: CategoryId, val slot: FeedSlot, val excludedCardIds: Set<String>,
    val allowAi: Boolean, val userSongsOnly: Boolean,
)
