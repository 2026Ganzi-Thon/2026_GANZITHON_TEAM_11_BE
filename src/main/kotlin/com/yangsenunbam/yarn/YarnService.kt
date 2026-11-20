package com.yangsenunbam.yarn

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

@Service
class YarnService(private val store: YarnStore, private val clock: Clock) {
    @Transactional
    fun save(user: CurrentUser, cardId: String, request: YarnRequest): YarnResponse {
        val available = store.lockCardAvailable(cardId)
        if (available == null || !store.hasFeedAccess(user.id, cardId)) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "카드를 찾을 수 없어요.")
        }
        val at = clock.instant()
        if (request.sent) {
            if (!available) throw ApiException(ErrorCode.CARD_UNAVAILABLE, "삭제된 카드예요.")
            store.send(user.id, cardId, at)
        } else {
            store.cancel(user.id, cardId, at)
        }
        return YarnResponse(cardId, request.sent)
    }

    @Transactional
    fun summary(user: CurrentUser): YarnSummaryResponse {
        val date = summaryDate()
        val end = date.atTime(CUTOFF).atZone(KstNightTime.KST).toInstant()
        val existing = store.summary(user.id, date)
        if (existing?.seenAt != null) return YarnSummaryResponse(null)
        val tally = store.tally(user.id, end.minus(java.time.Duration.ofDays(1)), end)
        if (tally.yarnCount == 0L) return YarnSummaryResponse(null)
        val row = existing ?: store.createSummary(user.id, date)
        if (row.seenAt != null) return YarnSummaryResponse(null)
        return YarnSummaryResponse(YarnSummary(row.id, date, tally.yarnCount, tally.senderCount))
    }

    @Transactional
    fun markSeen(user: CurrentUser, summaryId: String) {
        if (!store.markSeen(user.id, summaryId, clock.instant())) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "털실 요약을 찾을 수 없어요.")
        }
    }

    private fun summaryDate(): LocalDate {
        val now = clock.instant().atZone(KstNightTime.KST)
        return if (now.toLocalTime() >= CUTOFF) now.toLocalDate() else now.toLocalDate().minusDays(1)
    }

    companion object {
        private val CUTOFF: LocalTime = LocalTime.of(4, 0)
    }
}
