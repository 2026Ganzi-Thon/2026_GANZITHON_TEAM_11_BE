package com.yangsenunbam.card

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.yangsenunbam.common.*
import com.yangsenunbam.music.TrackResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import java.time.Instant

data class CardResponse(
    val id: String, val available: Boolean, val kind: CardKind, val tone: CardTone?, val categoryId: CategoryId?,
    val authorType: AuthorType, val authorLabel: String?, val content: CardContentResponse?, val viewer: CardViewer,
)
data class CardViewer(val yarnSent: Boolean, val fluffSaved: Boolean, val songSaved: Boolean)
sealed interface CardContentResponse
data class TextCardContent(val text: String) : CardContentResponse
data class SongCardContent(val track: TrackResponse, val recommendation: String?) : CardContentResponse

sealed interface CardContentRequest
data class TextContentRequest(@field:TextLength(min = 1, max = 200) val text: String) : CardContentRequest
data class ManualTrackRequest(
    @field:TextLength(min = 1, max = 200) val title: String,
    @field:TextLength(min = 1, max = 200) val artist: String,
)
data class SongContentRequest(
    @field:TextLength(min = 1) val trackId: String? = null,
    @field:Valid val manualTrack: ManualTrackRequest? = null,
    @field:TextLength(min = 1, max = 50) val recommendation: String,
) : CardContentRequest {
    @AssertTrue(message = "검색한 곡 또는 직접 입력한 곡 중 하나를 보내 주세요.")
    @JsonIgnore
    fun isTrackExclusive(): Boolean = (trackId != null) xor (manualTrack != null)
}
data class SubmitCardRequest(
    val kind: CardKind,
    val categoryId: CategoryId,
    @field:Valid
    @field:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
    @field:JsonSubTypes(JsonSubTypes.Type(value = TextContentRequest::class, name = "text"), JsonSubTypes.Type(value = SongContentRequest::class, name = "song"))
    val content: CardContentRequest,
    @field:TextLength(min = 1) val sourceNightId: String? = null,
) {
    @AssertTrue @JsonIgnore
    fun isCategoryAllowed(): Boolean = categoryId != CategoryId.NONE
}
data class ResubmitCardRequest(
    val kind: CardKind,
    val categoryId: CategoryId,
    @field:Valid
    @field:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
    @field:JsonSubTypes(JsonSubTypes.Type(value = TextContentRequest::class, name = "text"), JsonSubTypes.Type(value = SongContentRequest::class, name = "song"))
    val content: CardContentRequest,
) {
    @AssertTrue @JsonIgnore
    fun isCategoryAllowed(): Boolean = categoryId != CategoryId.NONE
}
data class SubmissionResponse(
    val id: String, val kind: CardKind, val categoryId: CategoryId, val content: CardContentResponse,
    val moderationStatus: ModerationStatus, val rejectionReason: String?, val yarnCount: Long,
    val createdAt: Instant, val revision: Int,
)
