package com.ganzithon.sheep.auth

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE1
@Service
class AuthService {
    fun register(request: RegisterRequest, idempotencyKey: String): AuthResponse = notImplemented()

    fun login(request: LoginRequest, idempotencyKey: String): AuthResponse = notImplemented()

    fun refresh(request: RefreshTokenRequest, idempotencyKey: String): AuthResponse = notImplemented()

    fun logout(user: CurrentUser, request: RefreshTokenRequest): Unit = notImplemented()

    fun requestPasswordReset(request: PasswordResetRequest): PasswordResetAccepted = notImplemented()

    fun confirmPasswordReset(request: PasswordResetConfirmRequest): Unit = notImplemented()
}
