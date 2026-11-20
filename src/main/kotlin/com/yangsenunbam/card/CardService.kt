package com.yangsenunbam.card

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.*
import com.yangsenunbam.music.MusicCatalog
import com.yangsenunbam.music.Tracks
import com.yangsenunbam.night.NightService
import com.yangsenunbam.night.NightStore
import com.yangsenunbam.night.WorrySafetyGateway
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

object UserCardPolicy {
    const val AUTHOR_LABEL = "먼저 지나간 양"

    val TEXT_TONE = CardTone.EMPATHY

    val IDEMPOTENCY_TTL: Duration = Duration.ofHours(24)
}

fun SubmissionRecord.toResponse(yarnCount: Long): SubmissionResponse = SubmissionResponse(
    id, kind, categoryId, requireNotNull(content) { "deleted submission $id has no content" },
    status, rejectionReason, yarnCount, createdAt, revision,
)

@Service
class CardService(
    private val store: CardStore,
    private val music: MusicCatalog,
    private val safety: WorrySafetyGateway,
    private val nights: NightStore,
    private val nightService: NightService,
    private val time: KstNightTime,
    private val clock: Clock,
    private val mapper: JsonMapper,
) {
    @Transactional
    fun submit(user: CurrentUser, request: SubmitCardRequest, idempotencyKey: String): SubmissionResponse {
        val draft = Draft.of(request.kind, request.categoryId, request.content)
        val sourceNightId = request.sourceNightId?.trim()
        val fingerprint = fingerprint(SUBMIT, null, draft, sourceNightId)
        val keyHash = sha256(idempotencyKey)
        store.replay(user.id, SUBMIT, keyHash)?.let { return it.resolve(fingerprint) }

        val change = prepare(draft)
        store.lockUser(user.id)
        store.replay(user.id, SUBMIT, keyHash)?.let { return it.resolve(fingerprint) }

        val night = sourceNightId?.let { nights.owned(user.id, it, lock = true) }
        val now = time.now()
        if (now.phase == Phase.NIGHT &&
            (night == null || night.nightDate != now.nightDate || night.writingState != WritingState.WRITING)) {
            throw ApiException(ErrorCode.WRITING_ENTRY_REQUIRED, "밤에는 작성 제안에서 '지금 쓸래요'를 고른 뒤 쓸 수 있어요.")
        }
        val at = clock.instant()
        val record = SubmissionRecord(
            "c_${UUID.randomUUID()}", user.id, change.kind, change.categoryId, change.content, change.manualTrack,
            ModerationStatus.PENDING, null, 1, night?.id, at, at, null,
        )
        store.insert(record)
        if (night != null && night.writingState != null && night.writingState != WritingState.FULFILLED) {
            nightService.markWritingFulfilled(user.id, night.id)
        }
        val response = record.toResponse(0)
        store.saveReplay(user.id, SUBMIT, keyHash, fingerprint, response, at.plus(UserCardPolicy.IDEMPOTENCY_TTL))
        return response
    }

    @Transactional(readOnly = true)
    fun myCards(user: CurrentUser, status: ModerationStatus?, cursor: String?, limit: Int): PageResponse<SubmissionResponse> {
        val after = cursor?.let(::decodeCursor)
        val rows = store.ownedPage(user.id, status, after, limit + 1)
        return page(rows, limit) { it.createdAt.toEpochMilli() }
    }

    @Transactional
    fun delete(user: CurrentUser, cardId: String) {
        store.lockUser(user.id)
        val record = store.owned(user.id, cardId, lock = true)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "카드를 찾을 수 없어요.")
        if (record.deletedAt != null) return
        val at = clock.instant()
        store.markDeleted(cardId, at)
        if (record.status == ModerationStatus.APPROVED) store.unpublish(cardId, at)
    }

    @Transactional
    fun resubmit(user: CurrentUser, cardId: String, request: ResubmitCardRequest, idempotencyKey: String): SubmissionResponse {
        val draft = Draft.of(request.kind, request.categoryId, request.content)
        val fingerprint = fingerprint(RESUBMIT, cardId, draft, null)
        val keyHash = sha256(idempotencyKey)
        store.replay(user.id, RESUBMIT, keyHash)?.let { return it.resolve(fingerprint) }

        current(user, cardId)
        val change = prepare(draft)
        store.lockUser(user.id)
        store.replay(user.id, RESUBMIT, keyHash)?.let { return it.resolve(fingerprint) }
        val record = current(user, cardId, lock = true)

        val at = clock.instant()
        store.resubmit(cardId, change, record.revision + 1, at)
        val response = record.copy(
            kind = change.kind, categoryId = change.categoryId, content = change.content, manualTrack = change.manualTrack,
            status = ModerationStatus.PENDING, rejectionReason = null, revision = record.revision + 1,
        ).toResponse(0)
        store.saveReplay(user.id, RESUBMIT, keyHash, fingerprint, response, at.plus(UserCardPolicy.IDEMPOTENCY_TTL))
        return response
    }

    private fun current(user: CurrentUser, cardId: String, lock: Boolean = false): SubmissionRecord {
        val record = store.owned(user.id, cardId, lock)?.takeIf { it.deletedAt == null }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "카드를 찾을 수 없어요.")
        if (record.status != ModerationStatus.REJECTED) {
            throw ApiException(ErrorCode.CARD_NOT_REJECTED, "반려된 카드만 다시 제출할 수 있어요.")
        }
        return record
    }

    private fun prepare(draft: Draft): SubmissionContent {
        val content = when (draft.kind) {
            CardKind.TEXT -> TextCardContent(draft.text!!)
            CardKind.SONG -> {
                val track = draft.trackId?.let {
                    music.find(it) ?: throw ApiException(ErrorCode.TRACK_NOT_FOUND, "곡을 찾을 수 없어요. 다시 검색해 주세요.",
                        mapOf("content.track_id" to "검색 결과의 곡을 선택해 주세요."))
                } ?: Tracks.manual("manual_${UUID.randomUUID()}", draft.manualTitle!!, draft.manualArtist!!)
                SongCardContent(track, draft.recommendation)
            }
        }
        for (text in listOfNotNull(draft.text, draft.recommendation, draft.manualTitle, draft.manualArtist)) {
            val verdict = safety.check(text)
            if (verdict.needsSupport) {
                throw ApiException(ErrorCode.SUPPORT_RECOMMENDED, "지금은 도움을 먼저 받아 보면 좋겠어요.", support = verdict.support)
            }
        }
        return SubmissionContent(draft.kind, draft.categoryId, content, draft.trackId == null && draft.kind == CardKind.SONG)
    }

    private fun CardReplay.resolve(fingerprint: String): SubmissionResponse {
        if (this.fingerprint != fingerprint) {
            throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 요청 키로 다른 내용을 보냈어요.")
        }
        return response
    }

    private fun fingerprint(operation: String, cardId: String?, draft: Draft, sourceNightId: String?): String =
        sha256(mapper.writeValueAsString(linkedMapOf(
            "operation" to operation, "card_id" to cardId, "kind" to draft.kind.value, "category_id" to draft.categoryId.value,
            "text" to draft.text, "track_id" to draft.trackId, "manual_title" to draft.manualTitle,
            "manual_artist" to draft.manualArtist, "recommendation" to draft.recommendation, "source_night_id" to sourceNightId,
        )))

    private data class Draft(
        val kind: CardKind, val categoryId: CategoryId, val text: String?, val trackId: String?,
        val manualTitle: String?, val manualArtist: String?, val recommendation: String?,
    ) {
        companion object {
            fun of(kind: CardKind, categoryId: CategoryId, content: CardContentRequest): Draft = when {
                kind == CardKind.TEXT && content is TextContentRequest ->
                    Draft(kind, categoryId, content.text.trim(), null, null, null, null)
                kind == CardKind.SONG && content is SongContentRequest -> Draft(
                    kind, categoryId, null, content.trackId?.trim(), content.manualTrack?.title?.trim(),
                    content.manualTrack?.artist?.trim(), content.recommendation.trim(),
                )
                else -> throw ApiException(ErrorCode.VALIDATION_ERROR, "입력값을 확인해 주세요.",
                    mapOf("content" to "카드 종류와 내용 형식이 맞지 않아요."))
            }
        }
    }

    private fun page(rows: List<SubmissionRecord>, limit: Int, key: (SubmissionRecord) -> Long): PageResponse<SubmissionResponse> {
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val counts = store.yarnCounts(items.map { it.id })
        val next = if (hasMore) items.last().let { CursorCodec.encode(key(it).toString(), it.id) } else null
        return PageResponse(items.map { it.toResponse(counts[it.id] ?: 0) }, next)
    }

    companion object {
        private const val SUBMIT = "submit"
        private const val RESUBMIT = "resubmit"

        fun decodeCursor(cursor: String): SubmissionCursor {
            val (atMs, id) = CursorCodec.decode(cursor, 2)
            return SubmissionCursor(atMs.toLongOrNull() ?: throw CursorCodec.invalid(), id)
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
