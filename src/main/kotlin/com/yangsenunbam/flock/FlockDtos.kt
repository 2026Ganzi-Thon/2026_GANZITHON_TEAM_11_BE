package com.yangsenunbam.flock

import com.fasterxml.jackson.annotation.JsonIgnore
import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.CategoryId
import com.yangsenunbam.morning.ReflectionResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class SheepResponse(
    val id: String, val nightId: String, val nightDate: LocalDate, val cardId: String, val colorKey: String?,
    val createdAt: Instant, val placements: List<Placement>, val version: Int,
)
data class SheepDetailResponse(val sheep: SheepResponse, val card: CardResponse, val position: Int?, val sheepCount: Int?, val night: SheepNight)
data class SheepNight(val nightDate: LocalDate, val worryText: String?, val categoryId: CategoryId, val reflection: ReflectionResponse?)
data class StickerResponse(val id: String, val label: String, val assetUrl: String)
data class Placement(
    @field:NotBlank val instanceId: String,
    @field:NotBlank val stickerId: String,
    @field:DecimalMin("0") @field:DecimalMax("1") val x: BigDecimal,
    @field:DecimalMin("0") @field:DecimalMax("1") val y: BigDecimal,
    @field:DecimalMin("-180") @field:DecimalMax("180") val rotation: BigDecimal,
    @field:DecimalMin("0.25") @field:DecimalMax("3") val scale: BigDecimal,
)
data class SaveStickersRequest(@field:Min(1) val ifVersion: Int, @field:Valid @field:Size(max = 20) val placements: List<Placement>) {
    @AssertTrue(message = "스티커 인스턴스 ID가 중복됩니다.") @JsonIgnore
    fun isInstanceUnique(): Boolean = placements.map { it.instanceId }.distinct().size == placements.size
}
