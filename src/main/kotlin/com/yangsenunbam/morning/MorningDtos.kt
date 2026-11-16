package com.yangsenunbam.morning

import com.fasterxml.jackson.annotation.JsonCreator
import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.*
import com.yangsenunbam.music.TrackResponse
import com.yangsenunbam.night.WritingOfferResponse
import com.yangsenunbam.flock.SheepResponse
import java.time.Instant
import java.time.LocalDate
import tools.jackson.databind.JsonNode

data class MorningResponse(
    val night: MorningNight?, val countedSheep: Int?, val candidateCards: List<CandidateResponse>,
    val defaultCardId: String?, val adoption: SheepResponse?, val reflection: ReflectionResponse?,
    val savedSongs: List<SavedSongResponse>, val writingOffer: WritingOfferResponse?,
)
data class MorningNight(
    val id: String, val nightDate: LocalDate, val worryText: String?, val worrySavedAt: Instant?,
    val entrusted: Boolean, val categoryId: CategoryId,
)
data class CandidateResponse(
    val card: CardResponse, val canAdopt: Boolean, val fluffSaved: Boolean, val position: Int?,
    val sheepCount: Int, val lastViewedAt: Instant,
)
data class SavedSongResponse(val cardId: String, val track: TrackResponse, val savedAt: Instant)
data class AdoptionRequest(@field:TextLength(min = 1) val cardId: String?) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(fields: Map<String, JsonNode?>): AdoptionRequest {
            require(fields.keys == setOf("card_id")) { "card_id를 문자열 또는 명시적 null로 보내 주세요." }
            val cardId = fields.getValue("card_id")
            require(cardId == null || cardId.isNull || cardId.isString) { "card_id는 문자열 또는 null이어야 합니다." }
            return AdoptionRequest(if (cardId == null || cardId.isNull) null else cardId.asString())
        }
    }
}
data class ReflectionRequest(val answer: ReflectionAnswer)
data class ReflectionResponse(val answer: ReflectionAnswer, val message: String)
