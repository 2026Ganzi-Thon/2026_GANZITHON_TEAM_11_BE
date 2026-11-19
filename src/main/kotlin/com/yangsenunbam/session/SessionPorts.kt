package com.yangsenunbam.session

import com.yangsenunbam.card.CardResponse
import com.yangsenunbam.common.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean

/** BE2 owns feed data used by the BE1 session lifecycle in the same transaction. */
interface FeedPreparationPort {
    fun prepare(userId: String, nightId: String, sessionId: String, sheepCount: Int): Int
    fun canFinishStopPause(sessionId: String, position: Int): Boolean
    fun countSavedFluff(nightId: String, sessionId: String, furthestPosition: Int): Int
}

/** BE2 + BE3 provide an approved closing text card; no placeholder is emitted in production. */
interface ClosingCardPort {
    fun choose(userId: String, nightId: String, sessionId: String, reason: FinishReason): CardResponse
}

/** BE3 adapter must use a current locking read in the caller's transaction against the
 * same card row/tombstone locked by deletion. Missing/deleted/unapproved => false;
 * lookup failure => throw 503, NEVER fall back to the saved content. No cache/snapshot read.
 * Lock order: account -> session/night -> card; deletion must not acquire reader accounts.
 */
interface ClosingCardVisibilityPort { fun lockAndCheckAvailable(cardId: String): Boolean }

/** BE2 provides the existence of adoptable-but-not-adopted candidates for bootstrap daytime routing. */
interface MorningCandidatePort { fun hasCandidate(userId: String, nightId: String): Boolean }

@Configuration
class MissingIntegrationPorts {
    @Bean
    @ConditionalOnMissingBean(ClosingCardVisibilityPort::class)
    fun missingVisibility() = object : ClosingCardVisibilityPort {
        override fun lockAndCheckAvailable(cardId: String): Boolean =
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "마무리 카드 공개 상태 연동이 필요해요.")
    }
    @Bean
    @ConditionalOnMissingBean(FeedPreparationPort::class)
    fun missingFeed() = object : FeedPreparationPort {
        override fun prepare(userId: String, nightId: String, sessionId: String, sheepCount: Int): Int =
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "BE2 피드 생성 연동이 필요해요.")
        override fun canFinishStopPause(sessionId: String, position: Int): Boolean =
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "BE2 쉼표 검증 연동이 필요해요.")
        override fun countSavedFluff(nightId: String, sessionId: String, furthestPosition: Int): Int =
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "BE2 솜털 집계 연동이 필요해요.")
    }
    @Bean
    @ConditionalOnMissingBean(ClosingCardPort::class)
    fun missingClosing() = object : ClosingCardPort {
        override fun choose(userId: String, nightId: String, sessionId: String, reason: FinishReason): CardResponse =
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "검수된 마무리 카드 연동이 필요해요.")
    }
    @Bean
    @ConditionalOnMissingBean(MorningCandidatePort::class)
    fun missingMorning() = object : MorningCandidatePort {
        override fun hasCandidate(userId: String, nightId: String): Boolean =
            throw ApiException(ErrorCode.FEED_BUILD_UNAVAILABLE, "BE2 아침 후보 연동이 필요해요.")
    }
}
