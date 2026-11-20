package com.yangsenunbam.bootstrap

import com.yangsenunbam.common.Phase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.stereotype.Component

data class NightMoment(val phase: Phase, val nightDate: LocalDate, val instant: Instant)

@Component
class KstNightTime(private val clock: Clock) {
    companion object { val KST: ZoneId = ZoneId.of("Asia/Seoul") }

    fun at(instant: Instant): NightMoment {
        val local = instant.atZone(KST)
        val hour = local.hour
        val phase = if (hour >= 21 || hour < 4) Phase.NIGHT else Phase.DAY
        val nightDate = if (hour >= 21) local.toLocalDate() else local.toLocalDate().minusDays(1)
        return NightMoment(phase, nightDate, instant)
    }

    fun now(): NightMoment = at(clock.instant())
}
