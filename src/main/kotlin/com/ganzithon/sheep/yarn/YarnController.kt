package com.ganzithon.sheep.yarn

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class YarnController(private val service: YarnService) {
    @PutMapping("/cards/{card_id}/yarn")
    fun save(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("card_id") cardId: String,
        @Valid @RequestBody request: YarnRequest
    ): YarnResponse = service.save(user, cardId, request)

    @GetMapping("/me/yarn-summary")
    @NoRequestBody
    fun summary(
        @AuthenticationPrincipal user: CurrentUser
    ): YarnSummaryResponse = service.summary(user)

    @PutMapping("/me/yarn-summary/{summary_id}/seen")
    @NoRequestBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markSeen(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("summary_id") summaryId: String
    ): Unit = service.markSeen(user, summaryId)
}
