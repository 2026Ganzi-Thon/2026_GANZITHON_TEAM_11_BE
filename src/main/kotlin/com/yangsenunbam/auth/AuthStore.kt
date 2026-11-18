package com.yangsenunbam.auth

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

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

    /** All existing-account mutations acquire this lock BEFORE any child-row lock/write.
     * Owner discovery may be nonlocking; authorization must be re-read with a locking read.
     * Never acquire another account lock while holding this one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockUser(id: String): StoredAuthUser? = jdbc.query(
        "SELECT id, email, password_hash, onboarding_complete FROM auth_users WHERE id = ? FOR UPDATE",
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

    fun findSessionByHash(hash: String, lock: Boolean = false): StoredRefreshSession? = jdbc.query(
        "SELECT id, user_id, token_hash, expires_at_ms, revoked_at_ms " +
            "FROM auth_refresh_sessions WHERE token_hash = ?" + if (lock) " FOR UPDATE" else "",
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

    data class PendingMail(val id: String, val email: String, val encryptedToken: String)

    fun enqueueResetMail(resetId: String, encryptedToken: String, nowMs: Long) {
        jdbc.update("INSERT INTO auth_reset_outbox(reset_id,encrypted_token,next_attempt_at_ms,attempt_count,sent_at_ms) " +
            "VALUES(?,?,?,0,NULL)", resetId, encryptedToken, nowMs)
    }

    data class MailCandidate(val id: String, val userId: String)

    // Discovery only: no locks across users. Each candidate gets its own transaction.
    fun pendingResetMail(nowMs: Long, limit: Int): List<MailCandidate> = jdbc.query(
        "SELECT o.reset_id, r.user_id FROM auth_reset_outbox o JOIN auth_password_resets r ON r.id=o.reset_id " +
            "WHERE o.sent_at_ms IS NULL AND o.next_attempt_at_ms<=? AND r.used_at_ms IS NULL AND r.expires_at_ms>? " +
            "ORDER BY o.next_attempt_at_ms, o.reset_id LIMIT ?",
        RowMapper { rs, _ -> MailCandidate(rs.getString("reset_id"), rs.getString("user_id")) }, nowMs, nowMs, limit,
    )

    // Caller already owns account lock. Explicit reset -> outbox locking reads avoid JOIN lock order ambiguity.
    fun lockResetMail(id: String, userId: String, email: String, nowMs: Long): PendingMail? {
        val valid = jdbc.query("SELECT id FROM auth_password_resets WHERE id=? AND user_id=? " +
            "AND used_at_ms IS NULL AND expires_at_ms>? FOR UPDATE", { rs, _ -> rs.getString(1) }, id, userId, nowMs)
        if (valid.isEmpty()) return null
        return jdbc.query("SELECT reset_id, encrypted_token FROM auth_reset_outbox WHERE reset_id=? " +
            "AND sent_at_ms IS NULL AND next_attempt_at_ms<=? FOR UPDATE",
            RowMapper { rs, _ -> PendingMail(rs.getString(1), email, rs.getString(2)) }, id, nowMs).firstOrNull()
    }

    fun markResetMailSent(id: String, nowMs: Long) = jdbc.update(
        "UPDATE auth_reset_outbox SET sent_at_ms = ? WHERE reset_id = ?", nowMs, id,
    )

    fun retryResetMail(id: String, nextMs: Long) = jdbc.update(
        "UPDATE auth_reset_outbox SET next_attempt_at_ms = ?, attempt_count = attempt_count + 1 WHERE reset_id = ?",
        nextMs, id,
    )

    fun hasRecentReset(userId: String, sinceMs: Long): Boolean = jdbc.query(
        "SELECT id FROM auth_password_resets WHERE user_id = ? AND created_at_ms >= ? FOR UPDATE",
        { rs, _ -> rs.getString(1) }, userId, sinceMs,
    ).isNotEmpty()

    // Nonlocking owner discovery only; consumePasswordReset revalidates under the account lock.
    fun passwordResetOwner(tokenHash: String): String? = jdbc.query(
        "SELECT user_id FROM auth_password_resets WHERE token_hash=?", { rs, _ -> rs.getString(1) }, tokenHash,
    ).firstOrNull()

    fun insertPasswordReset(id: String, userId: String, tokenHash: String, at: Long, expires: Long) {
        jdbc.update("INSERT INTO auth_password_resets(id,user_id,token_hash,created_at_ms,expires_at_ms,used_at_ms) " +
            "VALUES(?,?,?,?,?,NULL)", id, userId, tokenHash, at, expires)
    }

    fun consumePasswordReset(tokenHash: String, userId: String, nowMs: Long): String? {
        val rows = jdbc.query("SELECT user_id FROM auth_password_resets WHERE token_hash = ? AND user_id = ? AND " +
            "used_at_ms IS NULL AND expires_at_ms > ? FOR UPDATE",
            { rs, _ -> rs.getString("user_id") }, tokenHash, userId, nowMs)
        if (rows.isEmpty()) return null
        val used = jdbc.update("UPDATE auth_password_resets SET used_at_ms = ? WHERE token_hash = ? " +
            "AND used_at_ms IS NULL AND expires_at_ms > ?", nowMs, tokenHash, nowMs)
        return if (used == 1) userId else null
    }

    fun changePasswordAndRevoke(userId: String, passwordHash: String, nowMs: Long) {
        jdbc.update("UPDATE auth_users SET password_hash = ? WHERE id = ?", passwordHash, userId)
        jdbc.update("UPDATE auth_refresh_sessions SET revoked_at_ms = ? " +
            "WHERE user_id = ? AND revoked_at_ms IS NULL", nowMs, userId)
        jdbc.update("UPDATE auth_password_resets SET used_at_ms = ? " +
            "WHERE user_id = ? AND used_at_ms IS NULL", nowMs, userId)
    }

    fun deleteAccountOwnedData(userId: String, emailActorScope: String) {
        jdbc.update("DELETE FROM auth_idempotency WHERE actor_scope = ?", emailActorScope)
        jdbc.update("DELETE FROM auth_idempotency WHERE actor_scope IN " +
            "(SELECT CONCAT('refresh:', token_hash) FROM auth_refresh_sessions WHERE user_id = ?)", userId)
        jdbc.update("DELETE FROM auth_reset_outbox WHERE reset_id IN " +
            "(SELECT id FROM auth_password_resets WHERE user_id = ?)", userId)
        jdbc.update("DELETE FROM auth_password_resets WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM auth_refresh_sessions WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM user_onboarding WHERE user_id = ?", userId)
        jdbc.update("DELETE FROM auth_users WHERE id = ?", userId)
    }

    fun removeExpiredIdempotency(scope: String, operation: String, key: String, nowMs: Long) {
        jdbc.update(
            "DELETE FROM auth_idempotency WHERE actor_scope = ? AND operation_name = ? AND idem_key = ? AND expires_at_ms <= ?",
            scope, operation, key, nowMs,
        )
    }

    fun idempotentResult(scope: String, operation: String, key: String): StoredIdempotency? = jdbc.query(
        "SELECT request_fingerprint, encrypted_response, expires_at_ms FROM auth_idempotency " +
            "WHERE actor_scope = ? AND operation_name = ? AND idem_key = ? FOR UPDATE",
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
