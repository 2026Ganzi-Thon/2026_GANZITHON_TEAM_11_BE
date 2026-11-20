package com.yangsenunbam.session

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.card.CardViewer
import com.yangsenunbam.card.TextCardContent
import com.yangsenunbam.common.*
import com.yangsenunbam.night.NightStore
import com.yangsenunbam.night.NightRecord
import com.yangsenunbam.night.WritingOfferResponse
import com.yangsenunbam.security.CurrentUser
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Service
@Profile("mysql | session-test")
class SessionService(
    private val nights: NightStore,
    private val sessions: SessionStore,
    private val time: KstNightTime,
    private val clock: Clock,
    private val feed: FeedPreparationPort,
    private val closing: ClosingCardPort,
    private val visibility: ClosingCardVisibilityPort,
    private val mapper: JsonMapper,
) {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }

    private fun checkKey(key: String) {
        if (key.isBlank() || key.length > 120) throw ApiException(ErrorCode.VALIDATION_ERROR,
            "Idempotency-Key는 1~120자여야 해요.")
    }

    private fun replay(userId: String, operation: String, key: String, requestHash: String): SessionRecord? {
        val saved = sessions.findReplay(userId, operation, key, clock.millis()) ?: return null
        if (saved.fingerprint != requestHash) throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
            "이미 다른 요청에 사용한 키예요.")
        return sessions.owned(userId, saved.sessionId)
    }

    private fun closedAt(night: NightRecord): Long = night.nightDate.plusDays(1).atTime(LocalTime.of(4, 0))
        .atZone(KstNightTime.KST).toInstant().toEpochMilli()

    @Transactional
    fun create(user: CurrentUser, nightId: String, request: CreateSessionRequest, idempotencyKey: String): SessionResponse {
        checkKey(idempotencyKey)
        if (request.sheepCount !in 3..30) throw ApiException(ErrorCode.VALIDATION_ERROR, "양은 3~30마리예요.")
        val requestHash = hash("$nightId|${request.sheepCount}")
        nights.lockAccount(user.id)
        replay(user.id, "session_create", idempotencyKey, requestHash)?.let { return it.response() }
        val night = nights.owned(user.id, nightId, true)
        val moment = time.now()
        if (moment.phase != Phase.NIGHT || night.nightDate != moment.nightDate) {
            throw ApiException(ErrorCode.NIGHT_CLOSED, "지금은 새로 양을 셀 수 없어요.")
        }
        if (night.supportTriggered && night.supportContinuedAtMs == null) {
            throw ApiException(ErrorCode.SUPPORT_REQUIRED, "도움 안내를 먼저 확인해 주세요.")
        }
        sessions.expireForUser(user.id, clock.millis())
        if (sessions.activeForNight(nightId) != null) {
            throw ApiException(ErrorCode.ACTIVE_SESSION_EXISTS, "진행 중인 양 세기가 있어요.")
        }
        val nowMs = clock.millis()
        if (nowMs >= closedAt(night)) throw ApiException(ErrorCode.NIGHT_CLOSED, "오늘 밤이 종료됐어요.")
        val sessionId = UUID.randomUUID().toString()
        sessions.create(user.id, night.id, request.sheepCount, 1, nowMs, closedAt(night), sessionId)
        // BE2 must create the persisted feed in the same Spring transaction, or throw 503 and roll back.
        val feedVersion = feed.prepare(user.id, night.id, sessionId, request.sheepCount)
        if (feedVersion != 1) {
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "피드 버전이 맞지 않아요.")
        }
        if (clock.millis() >= closedAt(night)) throw ApiException(ErrorCode.NIGHT_CLOSED, "밤이 종료됐어요.")
        sessions.saveReplay(user.id, "session_create", idempotencyKey, requestHash, sessionId, nowMs)
        return sessions.owned(user.id, sessionId).response()
    }

    private fun finishHash(sessionId: String, req: FinishRequest) = hash(
        "$sessionId|${req.reason.value}|${req.position}|${req.furthestPosition}|${req.sequence}|${req.writerEpoch}",
    )

    /** Reconstruct the saved selection, but always revalidate current visibility before exposing its body. */
    @Transactional
    fun persistedClosingCard(row: SessionRecord): CardResponse? {
        val raw = row.closingCardJson ?: return null
        val json = mapper.readTree(raw)
        fun rawString(field: String): String? = json.path(field).let { if (it.isNull || it.isMissingNode) null else it.asString() }
        val cardId = rawString("id") ?: throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "마무리 카드가 없어요.")
        val available = visibility.lockAndCheckAvailable(cardId)
        if (!available) return CardResponse(cardId, false, CardKind.TEXT, CardTone.WARM,
            rawString("category_id")?.let { c -> CategoryId.entries.firstOrNull { it.value == c } },
            AuthorType.EDITORIAL, null, null, CardViewer(false, false, false))
        val text = json.path("content").path("text").asString()
        if (rawString("kind") != CardKind.TEXT.value || rawString("tone") != CardTone.WARM.value || text.isBlank()) {
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "마무리 카드 데이터가 올바르지 않아요.")
        }
        return CardResponse(
            id = cardId,
            available = true, kind = CardKind.TEXT, tone = CardTone.WARM,
            categoryId = rawString("category_id")?.let { c -> CategoryId.entries.firstOrNull { it.value == c } },
            authorType = rawString("author_type")?.let { a -> AuthorType.entries.firstOrNull { it.value == a } }
                ?: AuthorType.EDITORIAL,
            authorLabel = rawString("author_label"), content = TextCardContent(text),
            viewer = CardViewer(false, false, false),
        )
    }

    private fun summary(row: SessionRecord, closingPending: Boolean): NightSummary? {
        val reason = row.finishReason ?: return null
        val finishedAt = row.finishedAtMs ?: return null
        val status = if (reason == FinishReason.COMPLETED) FinishedStatus.COMPLETED else FinishedStatus.STOPPED
        return NightSummary(row.furthestPosition, status, Instant.ofEpochMilli(finishedAt),
            row.entrustedCount, row.fluffCount, row.yarnSentCount,
            if (closingPending) persistedClosingCard(row) else null)
    }

    @Transactional
    fun latestSummary(row: SessionRecord): NightSummary? = summary(
        row, row.status == SessionStatus.STOPPED && row.closingViewedAtMs == null,
    )

    private fun finishResponse(row: SessionRecord, night: NightRecord): FinishResponse {
        val reason = row.finishReason ?: throw ApiException(ErrorCode.INTERNAL_ERROR, "종료 기록이 없어요.")
        val card = persistedClosingCard(row) ?: throw ApiException(ErrorCode.INTERNAL_ERROR, "마무리 카드가 없어요.")
        val status = if (reason == FinishReason.COMPLETED) FinishedStatus.COMPLETED else FinishedStatus.STOPPED
        val writing = night.writingState ?: WritingState.OFFERED
        return FinishResponse(row.id, status, reason, row.furthestPosition, card,
            WritingOfferResponse(writing), summary(row, false)!!)
    }

    @Transactional
    fun finish(user: CurrentUser, sessionId: String, request: FinishRequest, idempotencyKey: String): FinishResponse {
        checkKey(idempotencyKey)
        val fingerprint = finishHash(sessionId, request)
        nights.lockAccount(user.id)
        val replay = replay(user.id, "session_finish", idempotencyKey, fingerprint)
        if (replay != null) return finishResponse(replay, nights.owned(user.id, replay.nightId))

        val row = sessions.owned(user.id, sessionId, true)
        val night = nights.owned(user.id, row.nightId, true)
        if (row.finishReason != null) {
            val same = row.finishReason == request.reason && row.finishPosition == request.position &&
                row.furthestPosition == request.furthestPosition && row.finishSequence == request.sequence &&
                row.finishWriterEpoch == request.writerEpoch
            if (!same) throw ApiException(ErrorCode.FINISH_CONFLICT, "이미 다른 내용으로 종료된 세션이에요.")
            sessions.saveReplay(user.id, "session_finish", idempotencyKey, fingerprint, row.id, clock.millis())
            return finishResponse(row, night)
        }
        if (row.status != SessionStatus.ACTIVE || clock.millis() >= row.closesAtMs ||
            time.now().phase != Phase.NIGHT || time.now().nightDate != night.nightDate) {
            throw ApiException(ErrorCode.SESSION_CLOSED, "종료된 세션이에요.")
        }
        if (request.writerEpoch != row.writerEpoch) throw ApiException(ErrorCode.WRITER_REPLACED, "새 저장권으로 다시 시작해 주세요.")
        if (request.position !in 1..row.sheepCount || request.furthestPosition !in request.position..row.sheepCount ||
            request.furthestPosition < row.furthestPosition) {
            throw ApiException(ErrorCode.INVALID_FINISH_POSITION, "종료 위치를 확인해 주세요.")
        }
        if (request.sequence <= row.sequence) throw ApiException(ErrorCode.SEQUENCE_CONFLICT, "이미 반영된 진행 번호예요.")
        when (request.reason) {
            FinishReason.COMPLETED -> if (request.position != row.sheepCount) throw ApiException(
                ErrorCode.INVALID_FINISH_POSITION, "마지막 칸까지 도달해야 완료할 수 있어요.")
            FinishReason.STOP_PAUSE -> if (!feed.canFinishStopPause(row.id, request.position)) throw ApiException(
                ErrorCode.INVALID_FINISH_POSITION, "그만 세기 쉼표에서만 종료할 수 있어요.")
            FinishReason.LEAVE -> Unit
        }
        val chosen = closing.choose(user.id, night.id, row.id, request.reason)
        if (!chosen.available || chosen.kind != CardKind.TEXT || chosen.tone != CardTone.WARM ||
            chosen.content !is TextCardContent) {
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "마무리 카드가 준비되지 않았어요.")
        }
        val fluffCount = feed.countSavedFluff(night.id, row.id, request.furthestPosition)
        val now = clock.millis()
        if (now >= row.closesAtMs) throw ApiException(ErrorCode.SESSION_CLOSED, "밤이 종료됐어요.")
        val status = if (request.reason == FinishReason.COMPLETED) FinishedStatus.COMPLETED else FinishedStatus.STOPPED
        if (sessions.finish(row, request.reason, request.position, request.furthestPosition,
                request.sequence, request.writerEpoch, now, mapper.writeValueAsString(chosen), status, fluffCount) != 1) {
            throw ApiException(ErrorCode.FINISH_CONFLICT, "다른 종료 요청이 먼저 처리됐어요.")
        }
        if (night.writingState == null) nights.writingState(night.id, WritingState.OFFERED)
        sessions.saveReplay(user.id, "session_finish", idempotencyKey, fingerprint, row.id, now)
        return finishResponse(sessions.owned(user.id, row.id), nights.owned(user.id, night.id))
    }
}
