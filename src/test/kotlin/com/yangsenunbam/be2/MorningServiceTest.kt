package com.yangsenunbam.be2

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.*
import com.yangsenunbam.feed.model.StoredCard
import com.yangsenunbam.morning.*
import com.yangsenunbam.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MorningServiceTest {
    private val f = Be2Fixture()

    @Test
    fun `morning expires active sessions and defers unfinished writing without counting downloads`() {
        fails(ErrorCode.MORNING_NOT_OPEN) { f.morning.morning(f.user, null) }
        f.day()
        val result = f.morning.morning(f.user, null)
        assertThat(result.countedSheep).isZero()
        assertThat(result.candidateCards).isEmpty()
        assertThat(result.defaultCardId).isNull()
        assertThat(result.writingOffer?.state).isEqualTo(WritingState.DEFERRED)
        assertThat(f.store.sessions.getValue("s1").state.status).isEqualTo(SessionStatus.EXPIRED)
        fails(ErrorCode.NO_ADOPTION_CANDIDATE) { f.morning.adopt(f.user, "n1", AdoptionRequest(null)) }
    }

    @Test
    fun `default morning only looks at previous night and historical lookup is explicit`() {
        f.day()
        f.clock.current = f.clock.current.plusSeconds(86_400)
        val empty = f.morning.morning(f.user, null)
        assertThat(empty).isEqualTo(MorningResponse(null, null, emptyList(), null, null, null, emptyList(), null))
        assertThat(f.morning.morning(f.user, "n1").night?.id).isEqualTo("n1")
        fails(ErrorCode.RESOURCE_NOT_FOUND) { f.morning.morning(f.user, "missing") }
        fails(ErrorCode.RESOURCE_NOT_FOUND) { f.morning.morning(CurrentUser("other", true), "n1") }
    }

    @Test
    fun `candidates combine sessions deduplicate cards and retain latest display context`() {
        f.progress(7)
        f.feed.saveFluff(f.user, "n1", "s1-c3", SaveRequest(true))
        val second = f.addSession("s2", 3)
        f.store.sessions["s2"] = second.copy(items = second.items.map {
            if (it.position == 1) StoredCard(it.id, 1, "s1-c1") else it
        })
        f.clock.current = f.clock.current.plusSeconds(10)
        f.progress(2, sessionId = "s2")
        f.day()
        val result = f.morning.morning(f.user, null)
        assertThat(result.countedSheep).isEqualTo(9)
        assertThat(result.candidateCards).hasSize(7)
        assertThat(result.candidateCards.first().card.id).isEqualTo("s1-c3")
        val duplicate = result.candidateCards.single { it.card.id == "s1-c1" }
        assertThat(duplicate.position).isEqualTo(1)
        assertThat(duplicate.sheepCount).isEqualTo(3)
        assertThat(result.defaultCardId).isEqualTo("s1-c7")
    }

    @Test
    fun `unacknowledged closing card is excluded and acknowledged one has no position`() {
        f.stopped()
        f.day()
        assertThat(f.morning.morning(f.user, null).candidateCards.map { it.card.id }).doesNotContain("s1-c7")
        val acknowledged = Be2Fixture()
        acknowledged.stopped()
        acknowledged.feed.acknowledgeClosing(acknowledged.user, "s1", ClosingViewRequest(true))
        acknowledged.day()
        val result = acknowledged.morning.morning(acknowledged.user, null)
        assertThat(result.defaultCardId).isEqualTo("s1-c7")
        assertThat(result.candidateCards.single { it.card.id == "s1-c7" }.position).isNull()
        assertThat(result.countedSheep).isEqualTo(2)
    }

    @Test
    fun `delayed finalization uses availability at close and never replaces a deleted default`() {
        f.progress(7)
        f.day()
        f.deleteCard("s1-c7")
        val result = f.morning.morning(f.user, null)
        assertThat(result.defaultCardId).isEqualTo("s1-c7")
        val placeholder = result.candidateCards.single { it.card.id == "s1-c7" }
        assertThat(placeholder.canAdopt).isFalse()
        assertThat(placeholder.card.content).isNull()
        fails(ErrorCode.CARD_UNAVAILABLE) { f.morning.adopt(f.user, "n1", AdoptionRequest(null)) }
        assertThat(f.morning.morning(f.user, null).defaultCardId).isEqualTo("s1-c7")
        assertThat(f.morning.adopt(f.user, "n1", AdoptionRequest("s1-c2")).cardId).isEqualTo("s1-c2")
    }

    @Test
    fun `warm deleted before close falls back to the latest available general card`() {
        f.progress(7)
        f.deleteCard("s1-c7", beforeClose = true)
        f.day()
        assertThat(f.morning.morning(f.user, null).defaultCardId).isEqualTo("s1-c6")
    }

    @Test
    fun `adoption is fixed and retains context for BE3 sheep details`() {
        f.progress(7)
        f.day()
        fails(ErrorCode.INVALID_ADOPTION_CARD) { f.morning.adopt(f.user, "n1", AdoptionRequest("unseen")) }
        val sheep = f.morning.adopt(f.user, "n1", AdoptionRequest(null))
        assertThat(sheep.cardId).isEqualTo("s1-c7")
        assertThat(sheep.colorKey).isEqualTo("career")
        assertThat(sheep.version).isEqualTo(1)
        assertThat(sheep.placements).isEmpty()
        assertThat(f.morning.adopt(f.user, "n1", AdoptionRequest("s1-c7"))).isEqualTo(sheep)
        fails(ErrorCode.ADOPTION_ALREADY_FINALIZED) { f.morning.adopt(f.user, "n1", AdoptionRequest("s1-c1")) }
        assertThat(f.store.nights.getValue("n1").adoption?.position).isEqualTo(7)
        assertThat(f.store.nights.getValue("n1").adoption?.sheepCount).isEqualTo(7)
        f.deleteCard("s1-c7")
        assertThat(f.morning.adopt(f.user, "n1", AdoptionRequest(null))).isEqualTo(sheep)
    }

    @Test
    fun `simultaneous adoption requests return one sheep under the storage transaction contract`() {
        f.progress(7)
        f.day()
        Executors.newFixedThreadPool(4).use { pool ->
            val results = pool.invokeAll((1..12).map { Callable { f.morning.adopt(f.user, "n1", AdoptionRequest(null)) } }).map { it.get() }
            assertThat(results.map { it.id }.distinct()).hasSize(1)
        }
    }

    @Test
    fun `adoption and finalization roll back when daytime ends before commit`() {
        f.progress(3)
        f.day()
        f.store.onBeforeCommit = { f.clock.current = java.time.Instant.parse("2026-11-11T12:00:00Z") }
        fails(ErrorCode.MORNING_NOT_OPEN) { f.morning.adopt(f.user, "n1", AdoptionRequest(null)) }
        assertThat(f.store.nights.getValue("n1").adoption).isNull()
        assertThat(f.store.nights.getValue("n1").defaultFrozen).isFalse()
    }

    @Test
    fun `reflection can change but deleting worry preserves old answer and blocks new answers`() {
        f.day()
        val first = f.morning.reflect(f.user, "n1", ReflectionRequest(ReflectionAnswer.SMALLER))
        assertThat(first.message).isEqualTo("봐, 밤엔 커 보였던 거야.")
        f.morning.reflect(f.user, "n1", ReflectionRequest(ReflectionAnswer.MUCH_BETTER))
        val night = f.store.nights.getValue("n1")
        f.store.nights["n1"] = night.copy(worryText = null)
        fails(ErrorCode.REFLECTION_NOT_AVAILABLE) { f.morning.reflect(f.user, "n1", ReflectionRequest(ReflectionAnswer.SAME)) }
        assertThat(f.morning.morning(f.user, null).reflection?.answer).isEqualTo(ReflectionAnswer.MUCH_BETTER)
    }

    @Test
    fun `no worry uses gray sheep and cannot submit reflection`() {
        f.store.nights["n1"] = f.store.nights.getValue("n1").copy(categoryId = CategoryId.NONE, worryText = null, worrySavedAt = null)
        f.progress(3)
        f.day()
        assertThat(f.morning.adopt(f.user, "n1", AdoptionRequest(null)).colorKey).isEqualTo("gray")
        fails(ErrorCode.REFLECTION_NOT_AVAILABLE) { f.morning.reflect(f.user, "n1", ReflectionRequest(ReflectionAnswer.SMALLER)) }
    }

    @Test
    fun `unconfirmed category color is returned as null`() {
        f.store.colorKeys = emptyMap()
        f.progress(3)
        f.day()
        assertThat(f.morning.adopt(f.user, "n1", AdoptionRequest(null)).colorKey).isNull()
    }

    @Test
    fun `morning includes entrusted state saved song and fulfilled writing offer`() {
        f.progress(4)
        f.feed.handlePause(f.user, "s1", "s1-i4", PauseRequest(PauseAction.ENTRUSTED))
        f.progress(5, sequence = 2)
        f.feed.saveSong(f.user, "n1", "s1-c5", SaveRequest(true))
        f.store.nights["n1"] = f.store.nights.getValue("n1").copy(writingState = WritingState.FULFILLED)
        f.day()
        val result = f.morning.morning(f.user, null)
        assertThat(result.night?.entrusted).isTrue()
        assertThat(result.night?.worrySavedAt).isNotNull()
        assertThat(result.savedSongs.single().cardId).isEqualTo("s1-c5")
        assertThat(result.writingOffer?.state).isEqualTo(WritingState.FULFILLED)
    }
}
