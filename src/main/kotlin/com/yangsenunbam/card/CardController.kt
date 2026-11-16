package com.yangsenunbam.card

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class CardController(private val service: CardService) {
    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: SubmitCardRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): SubmissionResponse = service.submit(user, request, idempotencyKey)

    @GetMapping("/me/cards")
    @NoRequestBody
    fun myCards(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(required = false) status: ModerationStatus?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int
    ): PageResponse<SubmissionResponse> = service.myCards(user, status, cursor, limit)

    @DeleteMapping("/me/cards/{card_id}")
    @NoRequestBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("card_id") cardId: String
    ): Unit = service.delete(user, cardId)

    @PostMapping("/me/cards/{card_id}/resubmit")
    fun resubmit(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("card_id") cardId: String,
        @Valid @RequestBody request: ResubmitCardRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): SubmissionResponse = service.resubmit(user, cardId, request, idempotencyKey)
}
