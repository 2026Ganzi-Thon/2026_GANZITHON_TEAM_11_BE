package com.yangsenunbam.user

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.yangsenunbam.common.*
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PastOrPresent
import jakarta.validation.constraints.Pattern
import java.time.LocalDate

data class UserResponse(val id: String, val email: String, val birthDate: LocalDate?, val birthDateEditsRemaining: Int, val stats: StatsResponse)
data class StatsResponse(val nightCount: Long, val countedSheepTotal: Long, val worryRecordCount: Long)
data class MeResponse(val user: UserResponse, val reminder: ReminderResponse)
data class ReminderResponse(val enabled: Boolean, val localTime: String?)
data class BirthDateResponse(val birthDate: LocalDate, val editsRemaining: Int, val zodiac: Zodiac)
data class LegalDocumentResponse(val type: LegalDocumentType, val version: String, val title: String, val bodyUrl: String, val required: Boolean)
data class ConsentsRequest(
    @field:NotBlank val termsVersion: String,
    @field:NotBlank val privacyVersion: String,
    @field:AssertTrue val termsAccepted: Boolean,
    @field:AssertTrue val privacyAccepted: Boolean,
)
data class DayIntroRequest(@field:AssertTrue val acknowledged: Boolean)
data class BirthDateRequest(@field:PastOrPresent val birthDate: LocalDate)
data class ReminderRequest(
    val enabled: Boolean,
    @param:JsonProperty(required = true)
    @field:Pattern(regexp = "(?:[01][0-9]|2[0-3]):(?:00|15|30|45)") val localTime: String?,
) {
    @AssertTrue(message = "알림이 켜져 있으면 시간을 지정하고, 꺼져 있으면 null을 보내 주세요.")
    @JsonIgnore
    fun isTimeConsistent(): Boolean = enabled == (localTime != null)
}
data class WorryListItem(val nightId: String, val nightDate: LocalDate, val categoryId: CategoryId, val worryText: String)
