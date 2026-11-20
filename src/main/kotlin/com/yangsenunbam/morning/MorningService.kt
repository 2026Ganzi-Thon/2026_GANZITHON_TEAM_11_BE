package com.yangsenunbam.morning

import com.yangsenunbam.common.*
import com.yangsenunbam.card.SongCardContent
import com.yangsenunbam.feed.NightCards
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.feed.model.AdoptionRecord
import com.yangsenunbam.feed.model.StoredPause
import com.yangsenunbam.feed.port.*
import com.yangsenunbam.night.WritingOfferResponse
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.flock.SheepResponse
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MorningService(private val store: NightStore, private val time: NightTime, private val finalizer: NightFinalizer) {
    fun morning(user: CurrentUser, nightId: String?): MorningResponse = store.withUser(user.id) { tx ->
        time.guardDay(tx)
        val found = if (nightId == null) tx.nightOn(time.previousNightDate()) else tx.ownedNight(user.id, nightId)
        if (found == null) return@withUser MorningResponse(null, null, emptyList(), null, null, null, emptyList(), null)
        val night = finalizer.finalize(tx, tx.ownedNight(user.id, found.id))
        val sessions = tx.sessions(night.id)
        val candidates = NightCards.views(sessions).map {
            val card = NightCards.render(tx, night, it.cardId)
            CandidateResponse(card, card.available, night.reactions[it.cardId]?.fluffSaved == true, it.position, it.sheepCount, it.viewedAt)
        }.sortedWith(compareByDescending<CandidateResponse> { it.fluffSaved }.thenByDescending { it.lastViewedAt }.thenBy { it.card.id })
        val songs = night.reactions.mapNotNull { (cardId, reaction) ->
            val savedAt = reaction.songSavedAt ?: return@mapNotNull null
            val card = NightCards.render(tx, night, cardId)
            val song = card.content as? SongCardContent ?: return@mapNotNull null
            SavedSongResponse(cardId, song.track, savedAt)
        }.sortedWith(compareByDescending<SavedSongResponse> { it.savedAt }.thenBy { it.cardId })
        val entrusted = sessions.any { session -> session.items.filterIsInstance<StoredPause>().any { it.action == PauseAction.ENTRUSTED } }
        MorningResponse(
            MorningNight(night.id, night.nightDate, night.worryText, night.worrySavedAt, entrusted, night.categoryId),
            sessions.sumOf { it.state.furthestPosition }, candidates, night.defaultCardId, night.adoption?.sheep,
            night.reflection, songs, night.writingState?.let { WritingOfferResponse(it) },
        )
    }

    fun adopt(user: CurrentUser, nightId: String, request: AdoptionRequest): SheepResponse = store.withUser(user.id) { tx ->
        time.guardDay(tx)
        val night = finalizer.finalize(tx, tx.ownedNight(user.id, nightId))
        val cardId = request.cardId ?: night.defaultCardId
            ?: throw ApiException(ErrorCode.NO_ADOPTION_CANDIDATE, "채택할 카드가 없습니다.")
        night.adoption?.let {
            if (it.sheep.cardId != cardId) throw ApiException(ErrorCode.ADOPTION_ALREADY_FINALIZED, "이 밤의 양을 이미 선택했습니다.")
            return@withUser it.sheep
        }
        val views = NightCards.views(tx.sessions(nightId))
        if (views.isEmpty()) throw ApiException(ErrorCode.NO_ADOPTION_CANDIDATE, "채택할 카드가 없습니다.")
        val view = views.find { it.cardId == cardId }
            ?: throw ApiException(ErrorCode.INVALID_ADOPTION_CARD, "실제로 본 후보 카드만 선택할 수 있습니다.")
        val card = tx.card(cardId)
        if (!card.available) throw ApiException(ErrorCode.CARD_UNAVAILABLE, "선택한 카드를 사용할 수 없습니다.")
        val colorKey = if (night.categoryId == CategoryId.NONE) "gray" else card.categoryId?.let { tx.colorKey(it) }
        val sheep = SheepResponse("sh_${UUID.randomUUID()}", nightId, night.nightDate, cardId, colorKey, time.now(), emptyList(), 1)
        tx.saveNight(night.copy(adoption = AdoptionRecord(sheep, view.position, view.sheepCount)))
        sheep
    }

    fun reflect(user: CurrentUser, nightId: String, request: ReflectionRequest): ReflectionResponse = store.withUser(user.id) { tx ->
        time.guardDay(tx)
        val night = finalizer.finalize(tx, tx.ownedNight(user.id, nightId))
        if (night.categoryId == CategoryId.NONE || night.worryText == null) {
            throw ApiException(ErrorCode.REFLECTION_NOT_AVAILABLE, "회고할 고민 기록이 없습니다.")
        }
        val message = when (request.answer) {
            ReflectionAnswer.SMALLER -> "봐, 밤엔 커 보였던 거야."
            ReflectionAnswer.MUCH_BETTER -> "한결 가벼워진 마음으로 하루를 시작해 봐요."
            ReflectionAnswer.SAME -> "아직 같은 마음이어도 괜찮아요."
            ReflectionAnswer.BIGGER -> "지금 마음을 천천히 돌봐 주세요."
            ReflectionAnswer.NEW_WORRY -> "새로운 고민도 차근차근 살펴봐요."
            ReflectionAnswer.CANNOT_RECALL -> "기억나지 않아도 괜찮아요."
        }
        ReflectionResponse(request.answer, message).also { tx.saveNight(night.copy(reflection = it)) }
    }
}
