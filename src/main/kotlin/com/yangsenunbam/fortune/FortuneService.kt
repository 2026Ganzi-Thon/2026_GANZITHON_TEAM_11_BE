package com.yangsenunbam.fortune

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

@Service
class FortuneService(private val port: FortunePort, private val time: NightTime) {
    fun today(user: CurrentUser): FortuneResponse {
        val zodiac = port.zodiac(user.id) ?: throw ApiException(ErrorCode.ONBOARDING_REQUIRED, "생년월일 등록이 필요합니다.")
        val date = time.fortuneDate()
        return port.getOrCreate(date, zodiac) {
            val result = port.approvedDaily(date, zodiac) ?: port.approvedFallback(date, zodiac)
                ?: throw ApiException(ErrorCode.FORTUNE_UNAVAILABLE, "검수된 운세를 준비하지 못했습니다.")
            if (result.date != date || result.zodiac != zodiac) {
                throw ApiException(ErrorCode.FORTUNE_UNAVAILABLE, "해당 날짜와 별자리의 운세를 준비하지 못했습니다.")
            }
            result.copy(rank = result.rank?.takeIf { it in 1..3 })
        }.let { it.copy(rank = it.rank?.takeIf { rank -> rank in 1..3 }) }
    }
}
