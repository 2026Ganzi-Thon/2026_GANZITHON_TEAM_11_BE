package com.yangsenunbam.night

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.yangsenunbam.common.*
import jakarta.validation.constraints.AssertTrue
import java.time.LocalDate

data class CategoryResponse(val id: CategoryId, val label: String, val colorKey: String?)
data class NightResponse(val id: String, val nightDate: LocalDate, val categoryId: CategoryId, val status: NightStatus, val support: SupportResponse?)
data class WorryRequest(
    val categoryId: CategoryId,
    @param:JsonProperty(required = true) @field:TextLength(min = 1, max = 500) val worryText: String?,
) {
    @AssertTrue(message = "고민 없음은 null, 다른 카테고리는 고민 내용을 보내 주세요.")
    @JsonIgnore
    fun isWorryConsistent(): Boolean = (categoryId == CategoryId.NONE) == (worryText == null)
}
data class WritingOfferRequest(val choice: WritingChoice)
data class WritingOfferResponse(val state: WritingState)
