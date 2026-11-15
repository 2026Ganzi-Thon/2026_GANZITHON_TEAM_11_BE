package com.ganzithon.sheep.session

import com.fasterxml.jackson.annotation.JsonIgnore
import com.ganzithon.sheep.card.CardResponse
import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.night.WritingOfferResponse
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant

data class CreateSessionRequest(@field:Min(3) @field:Max(30) val sheepCount: Int)
data class SessionResponse(
    val id: String, val nightId: String, val status: SessionStatus, val sheepCount: Int, val feedVersion: Int,
    val position: Int, val furthestPosition: Int, val sequence: Long, val writerEpoch: Long,
    val startedAt: Instant, val closesAt: Instant,
)
data class ProgressRequest(
    @field:Min(1) @field:Max(30) val position: Int,
    @field:Min(1) @field:Max(30) val furthestPosition: Int,
    @field:Min(1) val sequence: Long,
    @field:Min(1) val writerEpoch: Long,
) {
    @AssertTrue @JsonIgnore
    fun isPositionOrdered(): Boolean = position <= furthestPosition
}
data class ProgressResponse(val position: Int, val furthestPosition: Int, val sequence: Long, val writerEpoch: Long)
data class FinishRequest(
    val reason: FinishReason,
    @field:Min(1) @field:Max(30) val position: Int,
    @field:Min(1) @field:Max(30) val furthestPosition: Int,
    @field:Min(1) val sequence: Long,
    @field:Min(1) val writerEpoch: Long,
) {
    @AssertTrue @JsonIgnore
    fun isPositionOrdered(): Boolean = position <= furthestPosition
}
data class NightSummary(
    val counted: Int, val status: FinishedStatus, val finishedAt: Instant, val entrustedCount: Int,
    val fluffCount: Int, val yarnSentCount: Int, val closingCard: CardResponse?,
)
data class FinishResponse(
    val id: String, val status: FinishedStatus, val reason: FinishReason, val furthestPosition: Int,
    val closingCard: CardResponse, val writingOffer: WritingOfferResponse, val summary: NightSummary,
)
