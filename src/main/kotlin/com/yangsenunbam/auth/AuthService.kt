package com.yangsenunbam.auth

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.security.TokenService
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Locale
import java.util.UUID

/** BE1 implementation tranche 1: register, login, refresh, logout. */
@Service
class AuthService(
    private val store: AuthStore,
    private val encoder: PasswordEncoder,
    private val tokens: TokenService,
    private val clock: Clock,
    private val reset: PasswordResetService,
) {
    private fun normalizedEmail(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    private fun replay(scope: String, action: String, key: String, fingerprint: String, nowMs: Long): AuthResponse? {
        ensureKey(key)
        store.removeExpiredIdempotency(scope, action, key, nowMs)
        val cached = store.idempotentResult(scope, action, key) ?: return null
        if (cached.requestFingerprint != fingerprint) {
            throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "동일한 요청 키로 다른 값을 보낼 수 없어요.")
        }
        val parts = tokens.unseal(cached.encryptedResponse).split('\n')
        check(parts.size == 3) { "Stored authentication response is invalid" }
        val session = store.findSessionByHash(tokens.fingerprint(parts[1]), lock = true)
        if (session == null || session.revokedAtMillis != null || session.expiresAtMillis <= clock.millis()) {
            throw ApiException(if (action == "refresh") ErrorCode.INVALID_REFRESH_TOKEN else ErrorCode.INVALID_CREDENTIALS,
                "다시 로그인해 주세요.")
        }
        return AuthResponse(parts[0], parts[1], expiresIn = parts[2].toLong())
    }

    private fun ensureKey(key: String) {
        if (key.isBlank() || key.length > 120) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key는 1~120자로 입력해 주세요.")
        }
    }

    private fun saveReplay(scope: String, action: String, key: String, fingerprint: String, response: AuthResponse, nowMs: Long) {
        val plaintext = "${response.accessToken}\n${response.refreshToken}\n${response.expiresIn}"
        store.saveIdempotentResult(scope, action, key, fingerprint, tokens.seal(plaintext), nowMs)
    }

    private fun createAuthResponse(userId: String, nowMs: Long): AuthResponse {
        val refresh = tokens.randomRefreshToken()
        val sessionId = UUID.randomUUID().toString()
        store.insertSession(sessionId, userId, tokens.fingerprint(refresh), nowMs, nowMs + TokenService.REFRESH_TTL_MILLIS)
        return AuthResponse(
            accessToken = tokens.issueAccess(userId, sessionId, nowMs / 1000),
            refreshToken = refresh,
            expiresIn = TokenService.ACCESS_TTL_SECONDS,
        )
    }

    @Transactional
    fun register(request: RegisterRequest, idempotencyKey: String): AuthResponse {
        val now = clock.millis()
        val email = normalizedEmail(request.email)
        val scope = "email:${tokens.keyedFingerprint(email)}"
        val fingerprint = tokens.keyedFingerprint("register\u0000$email\u0000${request.password}")
        store.findUserByEmail(email)?.let { store.lockUser(it.id) }
        replay(scope, "register", idempotencyKey, fingerprint, now)?.let { return it }

        if (store.findUserByEmail(email) != null) {
            throw ApiException(ErrorCode.EMAIL_ALREADY_USED, "이미 가입된 이메일이에요.")
        }
        val passwordHash = checkNotNull(encoder.encode(request.password)) { "Password encoder returned null" }
        val user = StoredAuthUser(UUID.randomUUID().toString(), email, passwordHash, false)
        try {
            store.insertUser(user, now)
        } catch (_: DuplicateKeyException) {
            throw ApiException(ErrorCode.EMAIL_ALREADY_USED, "이미 가입된 이메일이에요.")
        }
        val response = createAuthResponse(user.id, now)
        saveReplay(scope, "register", idempotencyKey, fingerprint, response, now)
        return response
    }

    @Transactional
    fun login(request: LoginRequest, idempotencyKey: String): AuthResponse {
        val email = normalizedEmail(request.email)
        val scope = "email:${tokens.keyedFingerprint(email)}"
        val fingerprint = tokens.keyedFingerprint("login\u0000$email\u0000${request.password}")
        ensureKey(idempotencyKey)
        val user = store.findUserByEmail(email)?.let { store.lockUser(it.id) }
            ?: throw ApiException(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호를 확인해 주세요.")
        val lockedNow = clock.millis()
        replay(scope, "login", idempotencyKey, fingerprint, lockedNow)?.let { return it }
        if (!encoder.matches(request.password, user.passwordHash)) {
            throw ApiException(ErrorCode.INVALID_CREDENTIALS, "이메일 또는 비밀번호를 확인해 주세요.")
        }
        val response = createAuthResponse(user.id, lockedNow)
        saveReplay(scope, "login", idempotencyKey, fingerprint, response, lockedNow)
        return response
    }

    @Transactional
    fun refresh(request: RefreshTokenRequest, idempotencyKey: String): AuthResponse {
        val oldHash = tokens.fingerprint(request.refreshToken)
        val scope = "refresh:$oldHash"
        val fingerprint = tokens.keyedFingerprint("refresh\u0000$oldHash")
        ensureKey(idempotencyKey)
        val owner = store.findSessionByHash(oldHash)?.userId
            ?: throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "다시 로그인해 주세요.")
        store.lockUser(owner) ?: throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "다시 로그인해 주세요.")
        val lockedNow = clock.millis()
        replay(scope, "refresh", idempotencyKey, fingerprint, lockedNow)?.let { return it }
        val current = store.findSessionByHash(oldHash, lock = true)
            ?: throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "다시 로그인해 주세요.")
        if (current.revokedAtMillis != null || current.expiresAtMillis <= lockedNow ||
            current.userId != owner || !store.revokeSession(current.id, lockedNow)
        ) {
            throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "다시 로그인해 주세요.")
        }
        val response = createAuthResponse(current.userId, lockedNow)
        saveReplay(scope, "refresh", idempotencyKey, fingerprint, response, lockedNow)
        return response
    }

    @Transactional
    fun logout(user: CurrentUser, request: RefreshTokenRequest) {
        store.lockUser(user.id) ?: throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "유효하지 않은 세션이에요.")
        val session = store.findSessionByHash(tokens.fingerprint(request.refreshToken), lock = true)
            ?: throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "유효하지 않은 세션이에요.")
        if (session.userId != user.id) {
            throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "유효하지 않은 세션이에요.")
        }
        // Revoking the refresh session also makes its access JWT unusable in BearerTokenFilter.
        store.revokeSessionForUser(session.id, user.id, clock.millis())
    }

    fun requestPasswordReset(request: PasswordResetRequest): PasswordResetAccepted = reset.request(request)
    fun confirmPasswordReset(request: PasswordResetConfirmRequest): Unit = reset.confirm(request)
}
