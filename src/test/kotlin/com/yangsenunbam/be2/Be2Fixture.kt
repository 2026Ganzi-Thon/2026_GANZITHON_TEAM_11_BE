package com.yangsenunbam.be2

import com.yangsenunbam.card.*
import com.yangsenunbam.common.*
import com.yangsenunbam.feed.*
import com.yangsenunbam.feed.model.*
import com.yangsenunbam.feed.port.*
import com.yangsenunbam.fortune.*
import com.yangsenunbam.morning.*
import com.yangsenunbam.music.TrackResponse
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.session.SessionResponse
import java.time.*

class MutableClock(var current: Instant = Instant.parse("2026-11-10T18:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
    override fun instant(): Instant = current
}

/** 테스트에서만 쓰는 rollback 가능한 어댑터. 운영 DB 동시성 검증을 대신하지 않는다. */
class MemoryNightStore : NightStore {
    var nights = mutableMapOf<String, NightRecord>()
    var sessions = mutableMapOf<String, FeedSession>()
    var cards = mutableMapOf<String, CardResponse>()
    var cardsAtClose = mutableMapOf<String, CardAvailability>()
    var colorKeys = mapOf(CategoryId.CAREER to "career")
    var receipts = mutableMapOf<Pair<String, String>, ResumeReceipt>()
    var onBeforeCommit: () -> Unit = {}

    @Synchronized
    override fun <T> withUser(userId: String, block: (NightTransaction) -> T): T {
        val nextNights = nights.toMutableMap()
        val nextSessions = sessions.toMutableMap()
        val nextReceipts = receipts.toMutableMap()
        val checks = mutableListOf<() -> Unit>()
        val tx = object : NightTransaction {
            override fun night(id: String) = nextNights[id]
            override fun nightOn(date: LocalDate) = nextNights.values.find { it.userId == userId && it.nightDate == date }
            override fun session(id: String) = nextSessions[id]
            override fun sessions(nightId: String) = nextSessions.values.filter { it.userId == userId && it.state.nightId == nightId }
            override fun saveNight(night: NightRecord) { nextNights[night.id] = night }
            override fun saveSession(session: FeedSession) { nextSessions[session.state.id] = session }
            override fun card(id: String) = cards.getValue(id)
            override fun cardAt(id: String, instant: Instant) = cardsAtClose.getValue(id)
            override fun colorKey(category: CategoryId) = colorKeys[category]
            override fun resumeReceipt(key: String) = nextReceipts[userId to key]
            override fun saveResumeReceipt(key: String, receipt: ResumeReceipt) { nextReceipts[userId to key] = receipt }
            override fun beforeCommit(check: () -> Unit) { checks += check }
        }
        val result = block(tx)
        onBeforeCommit()
        checks.forEach { it() }
        nights = nextNights
        sessions = nextSessions
        receipts = nextReceipts
        return result
    }
}

class MemoryFortunePort : FortunePort {
    var userZodiac: Zodiac? = Zodiac.GEMINI
    val saved = mutableMapOf<Pair<LocalDate, Zodiac>, FortuneResponse>()
    var daily: FortuneResponse? = null
    var fallback: FortuneResponse? = null
    var createCalls = 0
    override fun zodiac(userId: String) = userZodiac
    @Synchronized
    override fun getOrCreate(date: LocalDate, zodiac: Zodiac, create: () -> FortuneResponse): FortuneResponse =
        saved.getOrPut(date to zodiac) { createCalls++; create() }
    override fun approvedDaily(date: LocalDate, zodiac: Zodiac) = daily
    override fun approvedFallback(date: LocalDate, zodiac: Zodiac) = fallback
}

class Be2Fixture {
    val clock = MutableClock()
    val time = NightTime(clock)
    val store = MemoryNightStore()
    val feed = FeedService(store, time)
    val morning = MorningService(store, time, NightFinalizer(time))
    val user = CurrentUser("user-1", true)
    val closesAt: Instant = Instant.parse("2026-11-10T19:00:00Z")

    init {
        store.nights["n1"] = NightRecord("n1", user.id, LocalDate.parse("2026-11-10"), closesAt, CategoryId.CAREER,
            "면접이 걱정돼요.", Instant.parse("2026-11-10T16:55:00Z"), WritingState.OFFERED)
        addSession()
    }

    fun addSession(id: String = "s1", count: Int = 7): FeedSession {
        val items = FeedLayout.slots(count, CategoryId.CAREER).map { slot ->
            if (slot.pause != null) StoredPause("$id-i${slot.position}", slot.position, slot.pause) else {
                val cardId = "$id-c${slot.position}"
                val content = if (slot.song) SongCardContent(TrackResponse("track", "제목", "가수", null, "https://open.spotify.com/search/test"), "추천")
                    else TextCardContent("테스트 카드 ${slot.position}")
                val card = CardResponse(cardId, true, if (slot.song) CardKind.SONG else CardKind.TEXT,
                    slot.tone, CategoryId.CAREER, AuthorType.USER, "먼저 지나간 양", content, CardViewer(false, false, false))
                store.cards[cardId] = card
                store.cardsAtClose[cardId] = CardAvailability(card.id, card.available, card.tone)
                StoredCard("$id-i${slot.position}", slot.position, cardId)
            }
        }
        return FeedSession(SessionResponse(id, "n1", SessionStatus.ACTIVE, count, 1, 0, 0, 0, 1,
            Instant.parse("2026-11-10T17:00:00Z"), closesAt), user.id, items).also { store.sessions[id] = it }
    }

    fun progress(position: Int, furthest: Int = position, sequence: Long = 1, epoch: Long = 1, sessionId: String = "s1") =
        feed.saveProgress(user, sessionId, ProgressRequest(position, furthest, sequence, epoch))

    fun day() { clock.current = closesAt }

    fun deleteCard(id: String, beforeClose: Boolean = false) {
        val deleted = store.cards.getValue(id).copy(available = false)
        store.cards[id] = deleted
        if (beforeClose) store.cardsAtClose[id] = CardAvailability(deleted.id, false, deleted.tone)
    }

    fun stopped() {
        progress(2)
        val session = store.sessions.getValue("s1")
        store.sessions["s1"] = session.copy(state = session.state.copy(status = SessionStatus.STOPPED), closingCardId = "s1-c7")
    }
}
