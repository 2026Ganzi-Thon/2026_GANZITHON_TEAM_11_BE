package com.yangsenunbam.auth

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class AuthController(private val service: AuthService) {
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): AuthResponse = service.register(request, idempotencyKey)

    @PostMapping("/auth/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): AuthResponse = service.login(request, idempotencyKey)

    @PostMapping("/auth/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshTokenRequest,
        @RequestHeader("Idempotency-Key") @NotBlank idempotencyKey: String
    ): AuthResponse = service.refresh(request, idempotencyKey)

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: RefreshTokenRequest
    ): Unit = service.logout(user, request)

    @PostMapping("/auth/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPasswordReset(
        @Valid @RequestBody request: PasswordResetRequest
    ): PasswordResetAccepted = service.requestPasswordReset(request)

    @PostMapping("/auth/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmPasswordReset(
        @Valid @RequestBody request: PasswordResetConfirmRequest
    ): Unit = service.confirmPasswordReset(request)
}
