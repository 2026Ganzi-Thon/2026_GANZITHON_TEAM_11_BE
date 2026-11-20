package com.yangsenunbam.yarn

import com.yangsenunbam.common.unconfiguredPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.LocalDate

data class YarnTally(val yarnCount: Long, val senderCount: Long)

data class YarnSummaryRow(val id: String, val seenAt: Instant?)

interface YarnStore {
    fun lockCardAvailable(cardId: String): Boolean?

    fun hasFeedAccess(userId: String, cardId: String): Boolean

    fun send(userId: String, cardId: String, at: Instant)
    fun cancel(userId: String, cardId: String, at: Instant)

    fun tally(authorId: String, start: Instant, end: Instant): YarnTally

    fun summary(userId: String, date: LocalDate): YarnSummaryRow?

    fun createSummary(userId: String, date: LocalDate): YarnSummaryRow

    fun markSeen(userId: String, summaryId: String, at: Instant): Boolean
}

@Configuration(proxyBeanMethods = false)
class YarnConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(YarnStore::class)
    fun unconfiguredYarnStore(): YarnStore = unconfiguredPort("BE3 털실 저장소 어댑터 연결이 필요합니다.")
}
