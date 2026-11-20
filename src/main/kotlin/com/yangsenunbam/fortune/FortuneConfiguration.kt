package com.yangsenunbam.fortune

import com.yangsenunbam.common.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.LocalDate

@Configuration(proxyBeanMethods = false)
class FortuneConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(FortunePort::class)
    fun unconfiguredFortunePort(): FortunePort = object : FortunePort {
        private fun missing(): Nothing = throw ApiException(ErrorCode.NOT_IMPLEMENTED, "별자리·운세 저장소 어댑터 연결이 필요합니다.")
        override fun zodiac(userId: String): Zodiac? = missing()
        override fun getOrCreate(date: LocalDate, zodiac: Zodiac, create: () -> FortuneResponse): FortuneResponse = missing()
        override fun approvedDaily(date: LocalDate, zodiac: Zodiac): FortuneResponse? = missing()
        override fun approvedFallback(date: LocalDate, zodiac: Zodiac): FortuneResponse? = missing()
    }
}
