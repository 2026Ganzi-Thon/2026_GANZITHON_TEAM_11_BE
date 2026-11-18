package com.yangsenunbam.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Small HS256 issuer and verifier. Requires a deployment-supplied secret of >= 32 UTF-8 bytes. */
@Component
class TokenService(
    @Value("\${app.auth.hmac-secret:}") private val configuredSecret: String,
) {
    companion object {
        const val ACCESS_TTL_SECONDS = 900L
        const val REFRESH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    data class AccessClaims(val userId: String, val sessionId: String)

    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()
    private val header = base64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray(UTF_8))
    private val payloadPattern = Regex("""\{"sub":"([0-9a-f-]{36})","sid":"([0-9a-f-]{36})","iat":([0-9]+),"exp":([0-9]+)\}""")

    private fun keyBytes(): ByteArray {
        val value = configuredSecret.toByteArray(UTF_8)
        require(value.size >= 32) { "Set AUTH_HMAC_SECRET with at least 32 UTF-8 bytes before using auth endpoints" }
        return value
    }

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }

    fun keyedFingerprint(value: String): String = sign(value).joinToString("") { "%02x".format(it) }

    private fun sign(payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(UTF_8))
    }

    fun issueAccess(userId: String, sessionId: String, issuedAtSeconds: Long): String {
        val payload = """{"sub":"$userId","sid":"$sessionId","iat":$issuedAtSeconds,"exp":${issuedAtSeconds + ACCESS_TTL_SECONDS}}"""
        val encoded = base64.encodeToString(payload.toByteArray(UTF_8))
        val unsigned = "$header.$encoded"
        return "$unsigned.${base64.encodeToString(sign(unsigned))}"
    }

    fun verifyAccess(jwt: String, nowSeconds: Long): AccessClaims? = runCatching {
        val parts = jwt.split('.')
        if (parts.size != 3 || parts[0] != header) return null
        val givenSignature = decoder.decode(parts[2])
        if (!MessageDigest.isEqual(givenSignature, sign("${parts[0]}.${parts[1]}"))) return null
        val payload = String(decoder.decode(parts[1]), UTF_8)
        val match = payloadPattern.matchEntire(payload) ?: return null
        val issuedAt = match.groupValues[3].toLong()
        val expiresAt = match.groupValues[4].toLong()
        if (issuedAt > nowSeconds + 60 || expiresAt <= nowSeconds || expiresAt - issuedAt != ACCESS_TTL_SECONDS) return null
        AccessClaims(match.groupValues[1], match.groupValues[2])
    }.getOrNull()

    fun randomRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64.encodeToString(bytes)
    }

    /** Encrypt replayable auth responses so refresh tokens are not stored in plaintext. */
    fun seal(plaintext: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = MessageDigest.getInstance("SHA-256")
            .digest(keyBytes() + ":auth-idempotency:v1".toByteArray(UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val bytes = iv + cipher.doFinal(plaintext.toByteArray(UTF_8))
        return base64.encodeToString(bytes)
    }

    fun unseal(encrypted: String): String {
        val bytes = decoder.decode(encrypted)
        require(bytes.size >= 29) { "Invalid stored auth response" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = MessageDigest.getInstance("SHA-256")
            .digest(keyBytes() + ":auth-idempotency:v1".toByteArray(UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), UTF_8)
    }
}
