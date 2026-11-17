package com.yangsenunbam.feed

import com.yangsenunbam.card.SongCardContent
import com.yangsenunbam.card.TextCardContent
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.*
import java.util.UUID

/** BE1 세션 생성 트랜잭션 안에서 호출하고 반환한 순서를 한 번만 저장한다. */
class FeedBuilder(private val source: FeedCardSource) {
    fun build(userId: String, count: Int, category: CategoryId): List<StoredFeedItem> {
        val used = mutableSetOf<String>()
        val layout = FeedLayout.slots(count, category)
        val userSongPosition = layout.filter { it.song }.getOrNull(1)?.position
        return layout.map { slot ->
            val id = "i_${UUID.randomUUID()}"
            if (slot.pause != null) return@map StoredPause(id, slot.position, slot.pause)
            val noWorry = category == CategoryId.NONE
            val userSongsOnly = noWorry || slot.position == userSongPosition
            val selection = FeedCardSelection(userId, category, slot, used.toSet(), allowAi = !userSongsOnly, userSongsOnly = userSongsOnly)
            var selectedSlot = slot
            var card = source.select(selection)
            // 고민 없음에서 승인된 사용자 곡이 부족한 경우 일반 카드로 대체한다.
            if (card == null && slot.song && noWorry) {
                selectedSlot = slot.copy(song = false, tone = CardTone.LIGHT)
                card = source.select(selection.copy(slot = selectedSlot))
            }
            if (card == null || !card.available || (noWorry && card.authorType == AuthorType.AI) ||
                (selectedSlot.song && (card.kind != CardKind.SONG || card.content !is SongCardContent || (userSongsOnly && card.authorType != AuthorType.USER))) ||
                (!selectedSlot.song && (card.kind != CardKind.TEXT || card.content !is TextCardContent || card.tone != selectedSlot.tone))) {
                throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "피드를 구성할 검수된 카드가 부족합니다.")
            }
            used += card.id
            StoredCard(id, slot.position, card.id)
        }
    }
}
