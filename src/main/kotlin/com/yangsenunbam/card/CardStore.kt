package com.yangsenunbam.card

import com.yangsenunbam.common.CardKind
import com.yangsenunbam.common.CategoryId
import com.yangsenunbam.common.ModerationStatus
import com.yangsenunbam.common.unconfiguredPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Instant

data class SubmissionRecord(
    val id: String,
    val userId: String,
    val kind: CardKind,
    val categoryId: CategoryId,
    val content: CardContentResponse?,
    val manualTrack: Boolean,
    val status: ModerationStatus,
    val rejectionReason: String?,
    val revision: Int,
    val sourceNightId: String?,
    val createdAt: Instant,
    val submittedAt: Instant,
    val deletedAt: Instant?,
)

data class SubmissionContent(val kind: CardKind, val categoryId: CategoryId, val content: CardContentResponse, val manualTrack: Boolean)

data class CardReplay(val fingerprint: String, val response: SubmissionResponse)

data class SubmissionCursor(val atMs: Long, val id: String)

interface CardStore {
    fun lockUser(userId: String)

    fun replay(userId: String, operation: String, keyHash: String): CardReplay?
    fun saveReplay(userId: String, operation: String, keyHash: String, fingerprint: String, response: SubmissionResponse, expiresAt: Instant)

    fun insert(record: SubmissionRecord)

    fun owned(userId: String, cardId: String, lock: Boolean = false): SubmissionRecord?

    fun byId(cardId: String, lock: Boolean = false): SubmissionRecord?

    fun resubmit(cardId: String, change: SubmissionContent, revision: Int, at: Instant)
    fun moderate(cardId: String, status: ModerationStatus, reason: String?, at: Instant)
    fun markDeleted(cardId: String, at: Instant)

    fun ownedPage(userId: String, status: ModerationStatus?, after: SubmissionCursor?, limit: Int): List<SubmissionRecord>

    fun pendingPage(after: SubmissionCursor?, limit: Int): List<SubmissionRecord>

    fun yarnCounts(cardIds: Collection<String>): Map<String, Long>

    fun publish(card: CardResponse, authorUserId: String, at: Instant)

    fun unpublish(cardId: String, at: Instant)
}

@Configuration(proxyBeanMethods = false)
class CardConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(CardStore::class)
    fun unconfiguredCardStore(): CardStore = unconfiguredPort("BE3 카드 저장소 어댑터 연결이 필요합니다.")
}
