package com.yangsenunbam.admin

import com.yangsenunbam.card.*
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ModerationService(private val store: CardStore, private val clock: Clock) {
    @Transactional(readOnly = true)
    fun pendingCards(user: CurrentUser, status: String, cursor: String?, limit: Int): PageResponse<SubmissionResponse> {
        val after = cursor?.let(CardService::decodeCursor)
        val rows = store.pendingPage(after, limit + 1)
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val counts = store.yarnCounts(items.map { it.id })
        val next = if (hasMore) items.last().let { CursorCodec.encode(it.submittedAt.toEpochMilli().toString(), it.id) } else null
        return PageResponse(items.map { it.toResponse(counts[it.id] ?: 0) }, next)
    }

    @Transactional
    fun moderate(user: CurrentUser, cardId: String, request: ModerationRequest): SubmissionResponse {
        val record = store.byId(cardId, lock = true)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "카드를 찾을 수 없어요.")
        val reason = request.reason?.trim()
        val target = when (request.decision) {
            ModerationDecision.APPROVED -> ModerationStatus.APPROVED
            ModerationDecision.REJECTED -> ModerationStatus.REJECTED
        }
        if (record.deletedAt != null || record.revision != request.revision) throw conflict()
        if (record.status != ModerationStatus.PENDING) {
            if (record.status == target && record.rejectionReason == reason) return response(record)
            throw conflict()
        }

        val at = clock.instant()
        store.moderate(cardId, target, reason, at)
        if (target == ModerationStatus.APPROVED) {
            store.publish(CardResponse(
                record.id, true, record.kind,
                if (record.kind == CardKind.TEXT) UserCardPolicy.TEXT_TONE else null,
                record.categoryId, AuthorType.USER, UserCardPolicy.AUTHOR_LABEL, record.content,
                CardViewer(yarnSent = false, fluffSaved = false, songSaved = false),
            ), record.userId, at)
        }
        return response(record.copy(status = target, rejectionReason = reason))
    }

    private fun response(record: SubmissionRecord) =
        record.toResponse(store.yarnCounts(listOf(record.id))[record.id] ?: 0)

    private fun conflict() = ApiException(ErrorCode.MODERATION_CONFLICT, "카드 상태가 바뀌었어요. 목록을 새로 불러와 주세요.")
}
