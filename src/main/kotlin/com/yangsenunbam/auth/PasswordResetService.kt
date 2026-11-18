package com.yangsenunbam.auth

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.security.TokenService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import java.net.URI
import java.time.Clock
import java.util.Locale
import java.util.UUID

interface ResetMailDelivery { fun deliver(recipient: String, resetToken: String) }

/** Configure MAIL_HOST/credentials, RESET_URL and RESET_SENDER before enabling password reset. */
@Component
class SpringResetMailDelivery(
    private val sender: ObjectProvider<JavaMailSender>,
    @Value("\${app.password-reset.url:}") private val resetUrl: String,
    @Value("\${app.password-reset.sender:}") private val from: String,
) : ResetMailDelivery {
    override fun deliver(recipient: String, resetToken: String) {
        val uri = runCatching { URI(resetUrl) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || from.isBlank()) {
            throw ApiException(ErrorCode.RESET_DELIVERY_UNAVAILABLE, "비밀번호 재설정 메일이 아직 준비되지 않았어요.")
        }
        val smtp = sender.getIfAvailable() ?:
            throw ApiException(ErrorCode.RESET_DELIVERY_UNAVAILABLE, "메일 전송 설정이 필요해요.")
        val separator = if (resetUrl.contains('?')) '&' else '?'
        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(recipient)
            subject = "[양 세는 밤] 비밀번호 재설정"
            text = "30분 안에 다음 링크에서 비밀번호를 변경해 주세요.\n$resetUrl${separator}token=$resetToken"
        }
        try { smtp.send(message) } catch (_: Exception) {
            // Don't log the reset link/token or recipient.
            throw ApiException(ErrorCode.RESET_DELIVERY_UNAVAILABLE, "비밀번호 재설정 메일을 전송하지 못했어요.")
        }
    }
}

@Service
class PasswordResetService(
    private val store: AuthStore,
    private val tokens: TokenService,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
) {
    @Transactional
    fun request(request: PasswordResetRequest): PasswordResetAccepted {
        val email = request.email.trim().lowercase(Locale.ROOT)
        val account = store.findUserByEmail(email)?.let { store.lockUser(it.id) }
            ?: return PasswordResetAccepted(true)
        // Prevent rapid repeats for the same account. IP-level throttling belongs to the API gateway.
        if (store.hasRecentReset(account.id, clock.millis() - 60_000)) return PasswordResetAccepted(true)
        val token = tokens.randomRefreshToken()
        val resetId = UUID.randomUUID().toString()
        val at = clock.millis()
        store.insertPasswordReset(resetId, account.id,
            tokens.fingerprint(token), at, at + 30 * 60_000L)
        // Queue encrypted mail for after-commit dispatch. Always return 202, avoiding
        // email-enumeration via SMTP availability. The outbox retries delivery.
        store.enqueueResetMail(resetId, tokens.seal(token), at)
        return PasswordResetAccepted(true)
    }

    @Transactional
    fun confirm(request: PasswordResetConfirmRequest) {
        val hash = tokens.fingerprint(request.token)
        val owner = store.passwordResetOwner(hash)
            ?: throw ApiException(ErrorCode.INVALID_RESET_TOKEN, "재설정 링크가 만료되었거나 사용됐어요.")
        store.lockUser(owner) ?: throw ApiException(ErrorCode.INVALID_RESET_TOKEN, "재설정 링크가 만료되었거나 사용됐어요.")
        val now = clock.millis()
        val userId = store.consumePasswordReset(hash, owner, now)
            ?: throw ApiException(ErrorCode.INVALID_RESET_TOKEN, "재설정 링크가 만료되었거나 사용됐어요.")
        val passwordHash = checkNotNull(encoder.encode(request.newPassword)) { "Password encoder returned null" }
        store.changePasswordAndRevoke(userId, passwordHash, now)
    }
}

/** Discovery never holds child locks; one account transaction per delivery. */
@Component
class PasswordResetOutboxWorker(
    private val store: AuthStore,
    private val delivery: PasswordResetOutboxDelivery,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${app.password-reset.dispatch-interval-ms:60000}")
    fun dispatch() {
        store.pendingResetMail(clock.millis(), 20).forEach { delivery.deliver(it) }
    }
}

@Component
class PasswordResetOutboxDelivery(
    private val store: AuthStore,
    private val tokens: TokenService,
    private val mail: ResetMailDelivery,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deliver(candidate: AuthStore.MailCandidate) {
        val account = store.lockUser(candidate.userId) ?: return
        val item = store.lockResetMail(candidate.id, account.id, account.email, clock.millis()) ?: return
        try {
            mail.deliver(item.email, tokens.unseal(item.encryptedToken))
        } catch (_: Exception) {
            // Never log recipient, reset token or SMTP credentials.
            store.retryResetMail(item.id, clock.millis() + 60_000)
            return
        }
        store.markResetMailSent(item.id, clock.millis())
    }
}
