package com.yangsenunbam.admin

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.yangsenunbam.common.ModerationDecision
import com.yangsenunbam.common.TextLength
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min

data class ModerationRequest(
    val decision: ModerationDecision,
    @param:JsonProperty(required = true) @field:TextLength(min = 1, max = 300) val reason: String?,
    @field:Min(1) val revision: Int,
) {
    @AssertTrue(message = "반려 사유를 입력하고, 승인 시에는 null을 보내 주세요.") @JsonIgnore
    fun isReasonConsistent(): Boolean = (decision == ModerationDecision.REJECTED) == (reason != null)
}
