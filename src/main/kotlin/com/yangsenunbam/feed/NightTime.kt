package com.yangsenunbam.feed

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.feed.port.NightTransaction
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class NightTime(private val clock: Clock) {
    fun now(): Instant = clock.instant()

    fun fortuneDate(): LocalDate = now().atZone(SEOUL).minusHours(4).toLocalDate()

    fun previousNightDate(): LocalDate = now().atZone(SEOUL).toLocalDate().minusDays(1)

    fun requireDay() {
        if (now().atZone(SEOUL).hour !in 4..20) {
            throw ApiException(ErrorCode.MORNING_NOT_OPEN, "아침 기능은 04시부터 21시 전까지 이용할 수 있습니다.")
        }
    }

    fun requireOpen(closesAt: Instant, code: ErrorCode = ErrorCode.NIGHT_CLOSED) {
        if (!now().isBefore(closesAt)) throw ApiException(code, "밤 기록이 마감되었습니다.")
    }

    fun guardOpen(tx: NightTransaction, closesAt: Instant, code: ErrorCode = ErrorCode.NIGHT_CLOSED) {
        requireOpen(closesAt, code)
        tx.beforeCommit { requireOpen(closesAt, code) }
    }

    fun guardDay(tx: NightTransaction) {
        requireDay()
        tx.beforeCommit { requireDay() }
    }

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
