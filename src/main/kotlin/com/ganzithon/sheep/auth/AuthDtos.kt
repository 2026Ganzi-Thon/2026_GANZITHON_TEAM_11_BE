package com.ganzithon.sheep.auth

import com.ganzithon.sheep.common.TextLength
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

class RegisterRequest(
    @field:NotBlank @field:Email @field:TextLength(max = 254) val email: String,
    @field:TextLength(min = 8, max = 128, trim = false) val password: String,
)
class LoginRequest(
    @field:NotBlank @field:Email @field:TextLength(max = 254) val email: String,
    @field:NotBlank val password: String,
)
class RefreshTokenRequest(@field:NotBlank val refreshToken: String)
class PasswordResetRequest(@field:NotBlank @field:Email @field:TextLength(max = 254) val email: String)
class PasswordResetConfirmRequest(
    @field:NotBlank val token: String,
    @field:TextLength(min = 8, max = 128, trim = false) val newPassword: String,
)
class AuthResponse(val accessToken: String, val refreshToken: String, val tokenType: String = "Bearer", val expiresIn: Long)
data class PasswordResetAccepted(val accepted: Boolean)
