package com.yangsenunbam.be2

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.fortune.*
import com.yangsenunbam.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate

class FortuneServiceTest {
    private val clock = MutableClock()
    private val time = NightTime(clock)
    private val port = MemoryFortunePort()
    private val service = FortuneService(port, time)
    private val user = CurrentUser("user-1", true)

    @ParameterizedTest
    @CsvSource("2026-11-10T18:59:59Z,2026-11-10,false", "2026-11-10T19:00:00Z,2026-11-11,true",
        "2026-11-11T11:59:59Z,2026-11-11,true", "2026-11-11T12:00:00Z,2026-11-11,false")
    fun `KST day and fortune dates use the exact boundaries`(instant: String, date: String, day: Boolean) {
        clock.current = Instant.parse(instant)
        assertThat(time.fortuneDate()).isEqualTo(LocalDate.parse(date))
        if (day) time.requireDay() else fails(ErrorCode.MORNING_NOT_OPEN) { time.requireDay() }
    }

    @Test
    fun `approved fallback is persisted once per date and zodiac across users`() {
        port.fallback = fortune(rank = 5)
        val first = service.today(user)
        port.fallback = null
        assertThat(first.rank).isNull()
        assertThat(service.today(CurrentUser("user-2", true))).isEqualTo(first)
        assertThat(port.createCalls).isEqualTo(1)
        assertThat(first.zodiac).isEqualTo(Zodiac.GEMINI)
    }

    @Test
    fun `reviewed daily result takes precedence and top three rank is public`() {
        port.daily = fortune(2)
        port.fallback = fortune(4)
        assertThat(service.today(user).rank).isEqualTo(2)
    }

    @Test
    fun `missing or mismatched reviewed content fails without caching and missing birth date requires onboarding`() {
        fails(ErrorCode.FORTUNE_UNAVAILABLE) { service.today(user) }
        assertThat(port.saved).isEmpty()
        port.daily = fortune(1).copy(zodiac = Zodiac.LEO)
        fails(ErrorCode.FORTUNE_UNAVAILABLE) { service.today(user) }
        assertThat(port.saved).isEmpty()
        port.userZodiac = null
        fails(ErrorCode.ONBOARDING_REQUIRED) { service.today(user) }
    }

    private fun fortune(rank: Int) = FortuneResponse(time.fortuneDate(), Zodiac.GEMINI, rank, SheepType.RELAXED,
        "검수된 테스트 문구", "보라", "책", "천천히 걷기")
}
