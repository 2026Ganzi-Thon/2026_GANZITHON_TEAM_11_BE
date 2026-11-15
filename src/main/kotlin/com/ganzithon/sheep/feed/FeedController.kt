package com.ganzithon.sheep.feed

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import com.ganzithon.sheep.session.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class FeedController(private val service: FeedService) {
    @GetMapping("/sessions/{session_id}/feed")
    @NoRequestBody
    fun feed(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("session_id") sessionId: String
    ): FeedResponse = service.feed(user, sessionId)

    @PostMapping("/sessions/{session_id}/resume")
    @NoRequestBody
    fun resume(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("session_id") sessionId: String,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): SessionResponse = service.resume(user, sessionId, idempotencyKey)

    @PutMapping("/sessions/{session_id}/progress")
    fun saveProgress(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("session_id") sessionId: String,
        @Valid @RequestBody request: ProgressRequest
    ): ProgressResponse = service.saveProgress(user, sessionId, request)

    @PutMapping("/sessions/{session_id}/pauses/{item_id}")
    fun handlePause(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("session_id") sessionId: String,
        @PathVariable("item_id") itemId: String,
        @Valid @RequestBody request: PauseRequest
    ): PauseStateResponse = service.handlePause(user, sessionId, itemId, request)

    @PutMapping("/sessions/{session_id}/closing-view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun acknowledgeClosing(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("session_id") sessionId: String,
        @Valid @RequestBody request: ClosingViewRequest
    ): Unit = service.acknowledgeClosing(user, sessionId, request)

    @PutMapping("/nights/{night_id}/fluff/{card_id}")
    fun saveFluff(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String,
        @PathVariable("card_id") cardId: String,
        @Valid @RequestBody request: SaveRequest
    ): SaveResponse = service.saveFluff(user, nightId, cardId, request)

    @PutMapping("/nights/{night_id}/songs/{card_id}")
    fun saveSong(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String,
        @PathVariable("card_id") cardId: String,
        @Valid @RequestBody request: SaveRequest
    ): SaveResponse = service.saveSong(user, nightId, cardId, request)
}
