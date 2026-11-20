package com.yangsenunbam.yarn

import com.yangsenunbam.common.unconfiguredPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.LocalDate

/** 작성자가 받은 털실 집계. */
data class YarnTally(val yarnCount: Long, val senderCount: Long)

/** 일일 요약의 ID와 확인 시각. */
data class YarnSummaryRow(val id: String, val seenAt: Instant?)

/**
 * BE3 털실 저장소. 현재 활성 털실은 BE2의 feed_card_yarn, 최초 전송·취소 시각은 yarn_events(V5)에 둔다.
 * MySQL 구현은 [MysqlYarnStore].
 */
interface YarnStore {
    /** 공개 목록의 카드를 공유 잠금으로 읽는다. 없는 카드는 null, 삭제 카드는 false. */
    fun lockCardAvailable(cardId: String): Boolean?

    /** 사용자 피드(본 세션의 칸 또는 마무리 카드)에 배정된 카드인지. */
    fun hasFeedAccess(userId: String, cardId: String): Boolean

    fun send(userId: String, cardId: String, at: Instant)
    fun cancel(userId: String, cardId: String, at: Instant)

    /** [authorId]의 실사용자 카드가 [start, end)에 처음 받고 [end] 시점에 유지된 털실(본인 반응·삭제 카드 제외). */
    fun tally(authorId: String, start: Instant, end: Instant): YarnTally

    fun summary(userId: String, date: LocalDate): YarnSummaryRow?

    /** 없으면 만들고, 동시에 만들어졌으면 먼저 만든 행을 돌려준다. */
    fun createSummary(userId: String, date: LocalDate): YarnSummaryRow

    /** 본인 요약이 없으면 false. 이미 확인했으면 시각을 바꾸지 않는다. */
    fun markSeen(userId: String, summaryId: String, at: Instant): Boolean
}

@Configuration(proxyBeanMethods = false)
class YarnConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(YarnStore::class)
    fun unconfiguredYarnStore(): YarnStore = unconfiguredPort("BE3 털실 저장소 어댑터 연결이 필요합니다.")
}
