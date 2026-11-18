package com.yangsenunbam.night

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

@Service
class NightService(
    private val store: NightStore,
    private val time: KstNightTime,
    private val clock: Clock,
    private val safety: WorrySafetyGateway,
    private val mapper: JsonMapper,
) {
    private val labels = mapOf(
        CategoryId.ROMANCE to "연애", CategoryId.CAREER to "진로", CategoryId.STUDY to "공부",
        CategoryId.HEALTH to "건강", CategoryId.RELATIONSHIPS to "인간관계", CategoryId.FAMILY to "가족",
        CategoryId.OTHER to "기타", CategoryId.NONE to "고민 없음",
    )

    fun categories(user: CurrentUser) = ItemsResponse(CategoryId.entries.map {
        CategoryResponse(it, labels.getValue(it), if (it == CategoryId.NONE) "none" else null)
    })

    @Transactional
    fun recordWorry(user: CurrentUser, request: WorryRequest): NightResponse {
        val moment = time.now()
        if (moment.phase != Phase.NIGHT) throw ApiException(ErrorCode.NIGHT_CLOSED, "밤 9시부터 양을 셀 수 있어요.")
        val worry = request.worryText?.trim()
        if ((request.categoryId == CategoryId.NONE && worry != null) ||
            (request.categoryId != CategoryId.NONE && (worry.isNullOrEmpty() || worry.codePointCount(0, worry.length) > 500))) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "고민 내용을 확인해 주세요.")
        }
        // Replays must not depend on the safety service being available again.
        store.byDate(user.id, moment.nightDate)?.let { previous ->
            if (!previous.worryDeleted && previous.category == request.categoryId && previous.worryText == worry) {
                return response(previous, false)
            }
            throw ApiException(ErrorCode.WORRY_ALREADY_RECORDED, "오늘 밤 고민은 이미 기록했어요.")
        }
        // Perform external review before acquiring the per-user lock; re-check after locking.
        val verdict = if (worry == null) WorryVerdict(false) else safety.check(worry)
        store.lockAccount(user.id)
        // Locking read sees the winner even under MySQL REPEATABLE READ.
        store.byDate(user.id, moment.nightDate, lock = true)?.let { previous ->
            if (!previous.worryDeleted && previous.category == request.categoryId && previous.worryText == worry) {
                return response(previous, false)
            }
            throw ApiException(ErrorCode.WORRY_ALREADY_RECORDED, "오늘 밤 고민은 이미 기록했어요.")
        }
        // The final persisted commit must still be inside the same KST night window.
        if (time.now().phase != Phase.NIGHT || time.now().nightDate != moment.nightDate) {
            throw ApiException(ErrorCode.NIGHT_CLOSED, "밤이 종료됐어요.")
        }
        val record = NightRecord(
            UUID.randomUUID().toString(), user.id, moment.nightDate, request.categoryId,
            worry, clock.millis(), false, verdict.needsSupport,
            verdict.support?.let(mapper::writeValueAsString), null, null,
        )
        store.create(record)
        return response(record, false)
    }

    private fun support(record: NightRecord): SupportResponse? = record.supportJson?.let {
        mapper.readValue(it, SupportResponse::class.java)
    }

    private fun response(record: NightRecord, closed: Boolean): NightResponse = NightResponse(
        record.id, record.nightDate, record.category,
        if (closed) NightStatus.CLOSED else if (record.supportTriggered && record.supportContinuedAtMs == null)
            NightStatus.NEEDS_SUPPORT else NightStatus.READY,
        if (record.supportTriggered) support(record) else null,
    )

    @Transactional
    fun continueAfterSupport(user: CurrentUser, nightId: String): NightResponse {
        store.lockAccount(user.id)
        val record = store.owned(user.id, nightId, true)
        val moment = time.now()
        if (moment.phase != Phase.NIGHT || record.nightDate != moment.nightDate) {
            throw ApiException(ErrorCode.NIGHT_CLOSED, "종료된 밤이에요.")
        }
        if (record.supportTriggered && record.supportContinuedAtMs == null) {
            store.continueAfterSupport(record.id, clock.millis())
        }
        return response(store.owned(user.id, nightId), false)
    }

    @Transactional
    fun saveWritingOffer(user: CurrentUser, nightId: String, request: WritingOfferRequest): WritingOfferResponse {
        store.lockAccount(user.id)
        val record = store.owned(user.id, nightId, true)
        val existing = record.writingState ?: throw ApiException(ErrorCode.OFFER_NOT_AVAILABLE, "작성 제안이 없어요.")
        val now = time.now()
        val actual = if (now.phase == Phase.DAY || record.nightDate != now.nightDate) {
            if (existing == WritingState.OFFERED || existing == WritingState.WRITING) WritingState.DEFERRED else existing
        } else existing
        val target = when (request.choice) {
            WritingChoice.NOW -> WritingState.WRITING
            WritingChoice.MORNING -> WritingState.DEFERRED
        }
        if (actual == WritingState.FULFILLED || (actual == WritingState.DEFERRED && target != actual) ||
            (actual == WritingState.WRITING && target == WritingState.WRITING && record.writingState != WritingState.WRITING)) {
            throw ApiException(ErrorCode.OFFER_ALREADY_DECIDED, "이미 결정된 작성 제안이에요.")
        }
        if (now.phase == Phase.DAY && target == WritingState.WRITING) {
            throw ApiException(ErrorCode.OFFER_ALREADY_DECIDED, "밤 작성 제안이 종료됐어요.")
        }
        if (existing != target) store.writingState(record.id, target)
        return WritingOfferResponse(target)
    }

    /** BE3 calls in the same transaction after successful card submission (including pending moderation) for the source night. */
    @Transactional
    fun markWritingFulfilled(userId: String, nightId: String) {
        store.lockAccount(userId)
        val record = store.owned(userId, nightId, true)
        if (record.writingState == null) throw ApiException(ErrorCode.OFFER_NOT_AVAILABLE, "작성 제안이 없어요.")
        store.writingState(record.id, WritingState.FULFILLED)
    }

    @Transactional
    fun deleteWorry(user: CurrentUser, nightId: String) {
        store.lockAccount(user.id)
        val record = store.owned(user.id, nightId, true)
        // BE1 currently stores no worry embeddings/summaries. If one is introduced, it must be
        // removed transactionally through an agreed deletion port before invoking this update.
        if (!record.worryDeleted) store.deleteWorry(record.id)
    }
}
