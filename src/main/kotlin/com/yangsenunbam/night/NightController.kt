package com.yangsenunbam.night

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class NightController(private val service: NightService) {
    @GetMapping("/categories")
    @NoRequestBody
    fun categories(
        @AuthenticationPrincipal user: CurrentUser
    ): ItemsResponse<CategoryResponse> = service.categories(user)

    @PutMapping("/nights/current/worry")
    fun recordWorry(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: WorryRequest
    ): NightResponse = service.recordWorry(user, request)

    @PostMapping("/nights/{night_id}/support/continue")
    @NoRequestBody
    fun continueAfterSupport(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String
    ): NightResponse = service.continueAfterSupport(user, nightId)

    @PutMapping("/nights/{night_id}/writing-offer")
    fun saveWritingOffer(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String,
        @Valid @RequestBody request: WritingOfferRequest
    ): WritingOfferResponse = service.saveWritingOffer(user, nightId, request)

    @DeleteMapping("/nights/{night_id}/worry")
    @NoRequestBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteWorry(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String
    ): Unit = service.deleteWorry(user, nightId)
}
