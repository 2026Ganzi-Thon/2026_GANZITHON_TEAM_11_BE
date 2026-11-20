package com.yangsenunbam.be2

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.*
import com.yangsenunbam.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FeedServiceTest {
    private val f = Be2Fixture()

    @Test
    fun `download preserves order without creating views and stays readable after closing`() {
        val response = f.feed.feed(f.user, "s1")
        assertThat(response.items.map { it.position }).containsExactly(1, 2, 3, 4, 5, 6, 7)
        assertThat(f.store.sessions.getValue("s1").views).isEmpty()
        f.day()
        assertThat(f.feed.feed(f.user, "s1")).isEqualTo(response)
    }

    @Test
    fun `progress preserves maximum and does not refresh old cards during backtracking`() {
        f.progress(7)
        val firstView = f.store.sessions.getValue("s1").views.getValue("s1-c7")
        f.clock.current = f.clock.current.plusSeconds(10)
        val updated = f.progress(2, 2, 2)
        assertThat(updated).isEqualTo(ProgressResponse(2, 7, 2, 1))
        assertThat(f.store.sessions.getValue("s1").views.getValue("s1-c7")).isEqualTo(firstView)
        assertThat(f.store.sessions.getValue("s1").views.getValue("s1-c2").viewedAt).isEqualTo(f.clock.current)
        assertThat(f.store.sessions.getValue("s1").views).hasSize(6)
    }

    @Test
    fun `equal sequence compares original input rather than merged maximum`() {
        f.progress(7)
        val original = f.progress(2, 2, 2)
        val views = f.store.sessions.getValue("s1").views
        f.clock.current = f.clock.current.plusSeconds(10)
        assertThat(f.progress(2, 2, 2)).isEqualTo(original)
        assertThat(f.progress(1, 1, 1)).isEqualTo(original)
        assertThat(f.store.sessions.getValue("s1").views).isEqualTo(views)
        fails(ErrorCode.SEQUENCE_CONFLICT) { f.progress(2, 7, 2) }
    }

    @Test
    fun `resume is idempotent and old writers cannot reuse a higher sequence`() {
        f.progress(3)
        val first = f.feed.resume(f.user, "s1", "resume-1")
        assertThat(first.writerEpoch).isEqualTo(2)
        assertThat(first.sequence).isZero()
        assertThat(first.position).isEqualTo(3)
        f.progress(5, sequence = 1, epoch = 2)
        val second = f.feed.resume(f.user, "s1", "resume-2")
        assertThat(second.writerEpoch).isEqualTo(3)
        assertThat(f.feed.resume(f.user, "s1", "resume-1")).isEqualTo(first)
        assertThat(f.store.sessions.getValue("s1").state.writerEpoch).isEqualTo(3)
        fails(ErrorCode.WRITER_REPLACED) { f.progress(7, sequence = 99, epoch = 1) }
        f.addSession("s2")
        fails(ErrorCode.IDEMPOTENCY_CONFLICT) { f.feed.resume(f.user, "s2", "resume-1") }
        f.day()
        assertThat(f.feed.resume(f.user, "s1", "resume-1")).isEqualTo(first)
        fails(ErrorCode.SESSION_CLOSED) { f.feed.resume(f.user, "s1", "new") }
    }

    @Test
    fun `progress rejects invalid ranges and rolls back when commit crosses 04`() {
        fails(ErrorCode.VALIDATION_ERROR) { f.progress(8) }
        fails(ErrorCode.VALIDATION_ERROR) { f.progress(3, 2) }
        f.clock.current = f.closesAt.minusNanos(1)
        f.store.onBeforeCommit = { f.day() }
        fails(ErrorCode.SESSION_CLOSED) { f.progress(3) }
        assertThat(f.store.sessions.getValue("s1").state.furthestPosition).isZero()
        assertThat(f.store.sessions.getValue("s1").views).isEmpty()
        assertThat(f.store.nights.getValue("n1").viewSequence).isZero()
    }

    @Test
    fun `resume receipt and epoch both roll back on a late commit`() {
        f.store.onBeforeCommit = { f.day() }
        fails(ErrorCode.SESSION_CLOSED) { f.feed.resume(f.user, "s1", "late") }
        assertThat(f.store.sessions.getValue("s1").state.writerEpoch).isEqualTo(1)
        assertThat(f.store.receipts).isEmpty()
    }

    @Test
    fun `only current entrust pause accepts an immutable answer`() {
        fails(ErrorCode.INVALID_PAUSE_ITEM) { f.feed.handlePause(f.user, "s1", "s1-i4", PauseRequest(PauseAction.ENTRUSTED)) }
        f.progress(4)
        val request = PauseRequest(PauseAction.ENTRUSTED)
        val result = f.feed.handlePause(f.user, "s1", "s1-i4", request)
        assertThat(f.feed.handlePause(f.user, "s1", "s1-i4", request)).isEqualTo(result)
        fails(ErrorCode.PAUSE_ALREADY_HANDLED) { f.feed.handlePause(f.user, "s1", "s1-i4", PauseRequest(PauseAction.SKIPPED)) }
        f.addSession("s16", 16)
        f.progress(14, sessionId = "s16")
        fails(ErrorCode.INVALID_PAUSE_ITEM) { f.feed.handlePause(f.user, "s16", "s16-i14", request) }
    }

    @Test
    fun `closing card is only counted after acknowledgement and retries preserve timestamp`() {
        fails(ErrorCode.CLOSING_NOT_AVAILABLE) { f.feed.acknowledgeClosing(f.user, "s1", ClosingViewRequest(true)) }
        f.stopped()
        assertThat(f.store.sessions.getValue("s1").views).doesNotContainKey("s1-c7")
        f.feed.acknowledgeClosing(f.user, "s1", ClosingViewRequest(true))
        val view = f.store.sessions.getValue("s1").views.getValue("s1-c7")
        assertThat(view.position).isNull()
        f.clock.current = f.clock.current.plusSeconds(10)
        f.feed.acknowledgeClosing(f.user, "s1", ClosingViewRequest(true))
        assertThat(f.store.sessions.getValue("s1").views.getValue("s1-c7")).isEqualTo(view)
        f.day()
        fails(ErrorCode.NIGHT_CLOSED) { f.feed.acknowledgeClosing(f.user, "s1", ClosingViewRequest(true)) }
    }

    @Test
    fun `deleted closing card is not acknowledged or exposed`() {
        f.stopped()
        f.deleteCard("s1-c7")
        fails(ErrorCode.CARD_UNAVAILABLE) { f.feed.acknowledgeClosing(f.user, "s1", ClosingViewRequest(true)) }
        val card = (f.feed.feed(f.user, "s1").items.last() as CardFeedItem).card
        assertThat(card.content).isNull()
        assertThat(card.viewer.fluffSaved).isFalse()
        assertThat(f.store.sessions.getValue("s1").closingViewedAt).isNull()
    }

    @Test
    fun `reactions require actual views and song save time survives retries`() {
        fails(ErrorCode.CARD_NOT_VIEWED) { f.feed.saveFluff(f.user, "n1", "s1-c1", SaveRequest(true)) }
        f.progress(5)
        fails(ErrorCode.NOT_SONG_CARD) { f.feed.saveSong(f.user, "n1", "s1-c1", SaveRequest(true)) }
        f.feed.saveFluff(f.user, "n1", "s1-c1", SaveRequest(true))
        f.feed.saveSong(f.user, "n1", "s1-c5", SaveRequest(true))
        val savedAt = f.store.nights.getValue("n1").reactions.getValue("s1-c5").songSavedAt
        f.clock.current = f.clock.current.plusSeconds(10)
        f.feed.saveSong(f.user, "n1", "s1-c5", SaveRequest(true))
        assertThat(f.store.nights.getValue("n1").reactions.getValue("s1-c5").songSavedAt).isEqualTo(savedAt)
        f.day()
        fails(ErrorCode.NIGHT_CLOSED) { f.feed.saveFluff(f.user, "n1", "s1-c1", SaveRequest(false)) }
        fails(ErrorCode.NIGHT_CLOSED) { f.feed.saveSong(f.user, "n1", "s1-c5", SaveRequest(true)) }
        assertThat(f.feed.saveSong(f.user, "n1", "s1-c5", SaveRequest(false)).saved).isFalse()
        assertThat(f.morning.morning(f.user, null).savedSongs).isEmpty()
    }

    @Test
    fun `ownership mismatch is hidden before state changes`() {
        val other = CurrentUser("other", true)
        fails(ErrorCode.RESOURCE_NOT_FOUND) { f.feed.feed(other, "s1") }
        fails(ErrorCode.RESOURCE_NOT_FOUND) { f.feed.resume(other, "s1", "key") }
        fails(ErrorCode.RESOURCE_NOT_FOUND) { f.feed.saveProgress(other, "s1", ProgressRequest(1, 1, 1, 1)) }
        fails(ErrorCode.RESOURCE_NOT_FOUND) { f.feed.saveFluff(other, "n1", "s1-c1", SaveRequest(true)) }
    }
}

internal fun fails(code: ErrorCode, action: () -> Unit) {
    assertThat(assertThrows<ApiException>(action).code).isEqualTo(code)
}
