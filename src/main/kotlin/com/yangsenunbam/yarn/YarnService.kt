package com.yangsenunbam.yarn

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

// BE3
@Service
class YarnService(private val store: YarnStore, private val clock: Clock) {
    /**
     * PUT /cards/{id}/yarn — 사용자·카드당 하나. 피드에 배정된 카드만 가능하며 다른 사람의 피드 카드는 404.
     * 삭제 카드에 새로 보내면 409 CARD_UNAVAILABLE, 취소(false)는 언제나 목표 상태로 응답한다.
     */
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

    /**
     * GET /me/yarn-summary — 직전 04시~이번 04시에 받은 털실(04시 이전 집계분)만 제공한다.
     * 0개이거나 이미 확인했으면 summary=null. 삭제 카드의 털실은 조회 때마다 제외한다.
     */
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

    /** PUT /me/yarn-summary/{id}/seen — 본인 요약만, 반복 204. */
    @Transactional
    fun markSeen(user: CurrentUser, summaryId: String) {
        if (!store.markSeen(user.id, summaryId, clock.instant())) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "털실 요약을 찾을 수 없어요.")
        }
    }

    /** 04시가 지난 가장 최근 날짜(KST). 00~04시에는 전날 요약을 보여준다. */
    private fun summaryDate(): LocalDate {
        val now = clock.instant().atZone(KstNightTime.KST)
        return if (now.toLocalTime() >= CUTOFF) now.toLocalDate() else now.toLocalDate().minusDays(1)
    }

    companion object {
        private val CUTOFF: LocalTime = LocalTime.of(4, 0)
    }
}
