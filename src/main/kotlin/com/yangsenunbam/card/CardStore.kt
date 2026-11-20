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

/** 제출한 카드 한 건. 삭제된 카드는 content=null, deletedAt!=null. */
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

/** 카드 내용 변경(제출·재제출). */
data class SubmissionContent(val kind: CardKind, val categoryId: CategoryId, val content: CardContentResponse, val manualTrack: Boolean)

/** 멱등 키로 저장한 이전 결과. */
data class CardReplay(val fingerprint: String, val response: SubmissionResponse)

/** 목록 cursor가 가리키는 마지막 항목의 정렬 키. */
data class SubmissionCursor(val atMs: Long, val id: String)

/**
 * BE3 카드 제출·검수 저장소. MySQL 구현은 [MysqlCardStore].
 * 승인 카드의 공개본은 BE2 feed_card_catalog에 같은 ID로 발행한다.
 */
interface CardStore {
    /** 같은 사용자의 카드 변경을 직렬화한다(BE1 auth_users 행 잠금). */
    fun lockUser(userId: String)

    fun replay(userId: String, operation: String, keyHash: String): CardReplay?
    fun saveReplay(userId: String, operation: String, keyHash: String, fingerprint: String, response: SubmissionResponse, expiresAt: Instant)

    fun insert(record: SubmissionRecord)

    /** 본인 카드. 남의 카드·없는 카드는 null. [lock]이면 행 잠금. */
    fun owned(userId: String, cardId: String, lock: Boolean = false): SubmissionRecord?

    /** 운영자용. [lock]이면 행 잠금. */
    fun byId(cardId: String, lock: Boolean = false): SubmissionRecord?

    fun resubmit(cardId: String, change: SubmissionContent, revision: Int, at: Instant)
    fun moderate(cardId: String, status: ModerationStatus, reason: String?, at: Instant)
    fun markDeleted(cardId: String, at: Instant)

    /** 본인 카드(삭제 제외) 생성일·id 내림차순. */
    fun ownedPage(userId: String, status: ModerationStatus?, after: SubmissionCursor?, limit: Int): List<SubmissionRecord>

    /** 검수 대기 카드 제출 시각·id 오름차순(먼저 들어온 카드부터). */
    fun pendingPage(after: SubmissionCursor?, limit: Int): List<SubmissionRecord>

    /** 카드별 현재 활성 털실 수. */
    fun yarnCounts(cardIds: Collection<String>): Map<String, Long>

    /** 승인 카드를 피드 공개 목록에 발행한다. */
    fun publish(card: CardResponse, authorUserId: String, at: Instant)

    /** 공개 목록에서 내리고 받은 털실을 지운다. 공개된 적 없으면 아무것도 하지 않는다. */
    fun unpublish(cardId: String, at: Instant)
}

@Configuration(proxyBeanMethods = false)
class CardConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(CardStore::class)
    fun unconfiguredCardStore(): CardStore = unconfiguredPort("BE3 카드 저장소 어댑터 연결이 필요합니다.")
}
