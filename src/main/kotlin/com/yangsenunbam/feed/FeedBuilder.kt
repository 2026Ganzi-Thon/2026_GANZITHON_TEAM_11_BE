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
            var selection = FeedCardSelection(userId, category, slot, used.toSet(), allowAi = !userSongsOnly, userSongsOnly = userSongsOnly)
            var card = source.select(selection)
            if (card == null && slot.song && userSongsOnly) {
                // 고민 없는 피드는 가벼운 카드, 고민 피드는 검수된 운영 추천곡으로 대체한다.
                selection = if (noWorry) selection.copy(slot = slot.copy(song = false, tone = CardTone.LIGHT))
                else selection.copy(userSongsOnly = false)
                card = source.select(selection)
            }
            val selectedSlot = selection.slot
            if (card == null || !card.available || (!selection.allowAi && card.authorType == AuthorType.AI) ||
                (selectedSlot.song && (card.kind != CardKind.SONG || card.content !is SongCardContent || (selection.userSongsOnly && card.authorType != AuthorType.USER))) ||
                (!selectedSlot.song && (card.kind != CardKind.TEXT || card.content !is TextCardContent || card.tone != selectedSlot.tone))) {
                throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "피드를 구성할 검수된 카드가 부족합니다.")
            }
            used += card.id
            StoredCard(id, slot.position, card.id)
        }
    }
}
