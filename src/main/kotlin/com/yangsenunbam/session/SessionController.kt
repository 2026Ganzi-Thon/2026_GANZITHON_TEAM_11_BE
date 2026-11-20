package com.yangsenunbam.session

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@Profile("mysql | session-test")
@RequestMapping("/api/v1")
class SessionController(private val service: SessionService) {
    @PostMapping("/nights/{night_id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("night_id") nightId: String,
        @Valid @RequestBody request: CreateSessionRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): SessionResponse = service.create(user, nightId, request, idempotencyKey)

    @PostMapping("/sessions/{session_id}/finish")
    fun finish(
        @AuthenticationPrincipal user: CurrentUser,
        @PathVariable("session_id") sessionId: String,
        @Valid @RequestBody request: FinishRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): FinishResponse = service.finish(user, sessionId, request, idempotencyKey)
}
