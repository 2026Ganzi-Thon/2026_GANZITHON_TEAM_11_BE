package com.yangsenunbam.admin

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.card.SubmissionResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class ModerationController(private val service: ModerationService) {
    @GetMapping("/admin/cards")
    @NoRequestBody
    fun pendingCards(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(defaultValue = "pending") @Pattern(regexp = "pending") status: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int
    ): PageResponse<SubmissionResponse> = service.pendingCards(user, status, cursor, limit)

    @PutMapping("/admin/cards/{card_id}/moderation")
    fun moderate(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("card_id") cardId: String,
        @Valid @RequestBody request: ModerationRequest
    ): SubmissionResponse = service.moderate(user, cardId, request)
}
