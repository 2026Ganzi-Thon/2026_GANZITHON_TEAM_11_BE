package com.yangsenunbam.auth

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/** BE1-owned storage. No other domain should write these tables directly. */
data class StoredAuthUser(
    val id: String,
    val email: String,
    val passwordHash: String,
    val onboardingComplete: Boolean,
)

data class StoredRefreshSession(
    val id: String,
    val userId: String,
    val tokenHash: String,
    val expiresAtMillis: Long,
    val revokedAtMillis: Long?,
)

data class StoredIdempotency(
    val requestFingerprint: String,
    val encryptedResponse: String,
    val expiresAtMillis: Long,
)

@Repository
class AuthStore(private val jdbc: JdbcTemplate) {
    private val userMapper = RowMapper<StoredAuthUser> { rs, _ ->
        StoredAuthUser(
            id = rs.getString("id"),
            email = rs.getString("email"),
            passwordHash = rs.getString("password_hash"),
            onboardingComplete = rs.getBoolean("onboarding_complete"),
        )
    }

    private val sessionMapper = RowMapper<StoredRefreshSession> { rs, _ ->
        val revoked = rs.getLong("revoked_at_ms")
        // JDBC wasNull() must be called immediately after reading the nullable column.
        val revokedIsNull = rs.wasNull()
        StoredRefreshSession(
            id = rs.getString("id"),
            userId = rs.getString("user_id"),
            tokenHash = rs.getString("token_hash"),
            expiresAtMillis = rs.getLong("expires_at_ms"),
            revokedAtMillis = if (revokedIsNull) null else revoked,
        )
    }

    fun findUserByEmail(email: String): StoredAuthUser? = jdbc.query(
        "SELECT id, email, password_hash, onboarding_complete FROM auth_users WHERE email = ?",
        userMapper, email,
    ).firstOrNull()

    fun findUserById(id: String): StoredAuthUser? = jdbc.query(
        "SELECT id, email, password_hash, onboarding_complete FROM auth_users WHERE id = ?",
        userMapper, id,
    ).firstOrNull()

    @Throws(DuplicateKeyException::class)
    fun insertUser(user: StoredAuthUser, createdAtMs: Long) {
        jdbc.update(
            "INSERT INTO auth_users(id, email, password_hash, onboarding_complete, created_at_ms) VALUES (?, ?, ?, ?, ?)",
            user.id, user.email, user.passwordHash, user.onboardingComplete, createdAtMs,
        )
    }

    fun insertSession(id: String, userId: String, tokenHash: String, createdAt: Long, expiresAt: Long) {
        jdbc.update(
            "INSERT INTO auth_refresh_sessions(id, user_id, token_hash, created_at_ms, expires_at_ms, revoked_at_ms) " +
                "VALUES (?, ?, ?, ?, ?, NULL)",
            id, userId, tokenHash, createdAt, expiresAt,
        )
    }

    fun findSessionByHash(hash: String): StoredRefreshSession? = jdbc.query(
        "SELECT id, user_id, token_hash, expires_at_ms, revoked_at_ms " +
            "FROM auth_refresh_sessions WHERE token_hash = ?",
        sessionMapper, hash,
    ).firstOrNull()

    fun activeSession(sessionId: String, userId: String, nowMs: Long): Boolean =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_refresh_sessions " +
                "WHERE id = ? AND user_id = ? AND revoked_at_ms IS NULL AND expires_at_ms > ?",
            Int::class.java, sessionId, userId, nowMs,
        ) == 1

    fun revokeSession(id: String, nowMs: Long): Boolean = jdbc.update(
        "UPDATE auth_refresh_sessions SET revoked_at_ms = ? " +
            "WHERE id = ? AND revoked_at_ms IS NULL AND expires_at_ms > ?",
        nowMs, id, nowMs,
    ) == 1

    fun revokeSessionForUser(id: String, userId: String, nowMs: Long): Int = jdbc.update(
        "UPDATE auth_refresh_sessions SET revoked_at_ms = ? " +
            "WHERE id = ? AND user_id = ? AND revoked_at_ms IS NULL",
        nowMs, id, userId,
    )

    fun removeExpiredIdempotency(scope: String, operation: String, key: String, nowMs: Long) {
        jdbc.update(
            "DELETE FROM auth_idempotency WHERE actor_scope = ? AND operation_name = ? AND idem_key = ? AND expires_at_ms <= ?",
            scope, operation, key, nowMs,
        )
    }

    fun idempotentResult(scope: String, operation: String, key: String): StoredIdempotency? = jdbc.query(
        "SELECT request_fingerprint, encrypted_response, expires_at_ms FROM auth_idempotency " +
            "WHERE actor_scope = ? AND operation_name = ? AND idem_key = ?",
        RowMapper { rs, _ ->
            StoredIdempotency(
                rs.getString("request_fingerprint"),
                rs.getString("encrypted_response"),
                rs.getLong("expires_at_ms"),
            )
        }, scope, operation, key,
    ).firstOrNull()

    fun saveIdempotentResult(
        scope: String, operation: String, key: String,
        fingerprint: String, encryptedResponse: String, createdAtMs: Long,
    ) {
        jdbc.update(
            "INSERT INTO auth_idempotency(actor_scope, operation_name, idem_key, request_fingerprint, " +
                "encrypted_response, created_at_ms, expires_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
            scope, operation, key, fingerprint, encryptedResponse, createdAtMs,
            createdAtMs + 86_400_000,
        )
    }
}
