package com.yangsenunbam.be2

import com.yangsenunbam.card.*
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.*
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.*
import com.yangsenunbam.music.TrackResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class FeedBuilderTest {
    @ParameterizedTest
    @MethodSource("combinations")
    fun `all 56 layouts preserve protected slots and song counts`(input: LayoutCase) {
        val slots = FeedLayout.slots(input.count, input.category)
        assertThat(slots.map { it.position }).containsExactlyElementsOf((1..input.count).toList())
        assertThat(slots.last().tone).isEqualTo(CardTone.WARM)
        assertThat(slots.first().tone).isEqualTo(if (input.category == CategoryId.NONE) CardTone.LIGHT else CardTone.EMPATHY)
        val expectedSongs = when (input.count) { in 3..4 -> 0; in 5..14 -> 1; else -> 2 }
        assertThat(slots.count { it.song }).isEqualTo(expectedSongs)
        assertThat(slots.filter { it.song }.all { it.position in 2..input.count - 2 && it.pause == null && it.tone == null }).isTrue()
        for (position in listOf(7, 13, 21, 28).filter { it < input.count }) {
            assertThat(slots[position - 1].tone).isEqualTo(CardTone.WARM)
        }
        assertThat(slots.count { it.pause == PauseKind.ENTRUST_WORRY }).isEqualTo(if (input.count >= 7 && input.category != CategoryId.NONE) 1 else 0)
        assertThat(slots.count { it.pause == PauseKind.STOP_OFFER }).isEqualTo(if (input.count >= 16) 1 else 0)
        if (input.count >= 16) assertThat(slots[13].pause).isEqualTo(PauseKind.STOP_OFFER)
    }

    @Test
    fun `pause and warm collisions move songs to the specification positions`() {
        assertThat(FeedLayout.slots(7, CategoryId.CAREER).filter { it.song }.map { it.position }).containsExactly(5)
        assertThat(FeedLayout.slots(7, CategoryId.NONE).filter { it.song }.map { it.position }).containsExactly(4)
        assertThat(FeedLayout.slots(16, CategoryId.CAREER).filter { it.song }.map { it.position }).containsExactly(8, 12)
        assertThat(FeedLayout.slots(24, CategoryId.CAREER).filter { it.song }.map { it.position }).containsExactly(10, 22)
    }

    @Test
    fun `no worry missing user song falls back to light text and never asks for AI`() {
        val requests = mutableListOf<FeedCardSelection>()
        val source = FeedCardSource { selection ->
            requests += selection
            if (selection.slot.song) null else textCard(selection.slot)
        }
        val items = FeedBuilder(source).build("user", 7, CategoryId.NONE)
        assertThat(items).hasSize(7).allMatch { it is StoredCard }
        assertThat(requests).allMatch { !it.allowAi && it.userSongsOnly }
        assertThat(requests.filter { it.slot.position == 4 }.map { it.slot.song }).containsExactly(true, false)
        assertThat(requests.last().excludedCardIds).hasSize(6)
    }

    @Test
    fun `missing unavailable or wrong tone cards fail instead of emitting a partial feed`() {
        fails(ErrorCode.FEED_BUILD_UNAVAILABLE) { FeedBuilder { null }.build("user", 7, CategoryId.CAREER) }
        fails(ErrorCode.FEED_BUILD_UNAVAILABLE) {
            FeedBuilder { textCard(it.slot).copy(available = false) }.build("user", 3, CategoryId.CAREER)
        }
        fails(ErrorCode.FEED_BUILD_UNAVAILABLE) {
            FeedBuilder { textCard(it.slot).copy(tone = CardTone.LIGHT) }.build("user", 3, CategoryId.CAREER)
        }
        fails(ErrorCode.FEED_BUILD_UNAVAILABLE) {
            FeedBuilder { textCard(it.slot).copy(authorType = AuthorType.AI) }.build("user", 3, CategoryId.NONE)
        }
    }

    @Test
    fun `second song requests a user recommendation and rejects AI in that slot`() {
        val requests = mutableListOf<FeedCardSelection>()
        val source = FeedCardSource { selection ->
            requests += selection
            if (selection.slot.song) textCard(selection.slot).copy(kind = CardKind.SONG,
                authorType = if (selection.userSongsOnly) AuthorType.USER else AuthorType.AI,
                content = SongCardContent(TrackResponse("track", "제목", "가수", null, "https://open.spotify.com/search/test"), null))
            else textCard(selection.slot)
        }
        assertThat(FeedBuilder(source).build("user", 16, CategoryId.CAREER)).hasSize(16)
        val songs = requests.filter { it.slot.song }
        assertThat(songs.map { it.userSongsOnly }).containsExactly(false, true)
        assertThat(songs.map { it.allowAi }).containsExactly(true, false)
        requests.clear()
        val fallbackItems = FeedBuilder { selection ->
            if (selection.slot.song && selection.userSongsOnly) { requests += selection; null }
            else source.select(selection)?.let { if (selection.slot.song && !selection.allowAi) it.copy(authorType = AuthorType.EDITORIAL) else it }
        }.build("user", 16, CategoryId.CAREER)
        assertThat(fallbackItems).hasSize(16)
        assertThat(requests.filter { it.slot.position == 12 }.map { it.userSongsOnly to it.allowAi })
            .containsExactly(true to false, false to false)
        fails(ErrorCode.FEED_BUILD_UNAVAILABLE) {
            FeedBuilder { selection -> source.select(selection)?.let {
                if (selection.slot.song) it.copy(authorType = AuthorType.AI) else it
            } }.build("user", 16, CategoryId.CAREER)
        }
    }

    private fun textCard(slot: FeedSlot) = CardResponse("c${slot.position}", true, CardKind.TEXT, slot.tone,
        CategoryId.CAREER, AuthorType.SEED, null, TextCardContent("검수된 카드"), CardViewer(false, false, false))

    companion object {
        @JvmStatic
        fun combinations(): Stream<LayoutCase> = (3..30).flatMap { count ->
            listOf(CategoryId.CAREER, CategoryId.NONE).map { LayoutCase(count, it) }
        }.stream()
    }
}

data class LayoutCase(val count: Int, val category: CategoryId)
