package com.yangsenunbam.flock

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeParseException

// BE3
@Service
class FlockService(private val store: FlockStore, private val time: KstNightTime) {
    /** GET /sheep — 본인 양떼, night_date·id 내림차순, cursor 페이지. limit 범위는 컨트롤러에서 검증한다. */
    fun list(user: CurrentUser, cursor: String?, limit: Int): PageResponse<SheepResponse> {
        val after = cursor?.let(::decodeCursor)
        val rows = store.sheepPage(user.id, after, limit + 1)
        val hasMore = rows.size > limit
        val page = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore) page.last().let { CursorCodec.encode(it.nightDate.toString(), it.id) } else null
        return PageResponse(page, nextCursor)
    }

    /** GET /sheep/{sheep_id} — 소유자만. 남의 양·없는 양은 404. 삭제 카드여도 200. */
    fun detail(user: CurrentUser, sheepId: String): SheepDetailResponse =
        store.sheepDetail(user.id, sheepId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "양을 찾을 수 없어요.")

    /** GET /stickers — 기본 스티커 전체, 페이지 없음. */
    fun stickers(user: CurrentUser): ItemsResponse<StickerResponse> = ItemsResponse(store.stickers())

    /**
     * PUT /sheep/{sheep_id}/stickers — 낮에만, placements 전체 교체 후 version 1 증가.
     * 남의 양 404, 밤 409 DAY_ONLY, if_version 불일치 409 VERSION_CONFLICT, 도감에 없는 sticker_id 422.
     */
    @Transactional
    fun saveStickers(user: CurrentUser, sheepId: String, request: SaveStickersRequest): SheepResponse {
        val sheep = store.lockSheep(user.id, sheepId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "양을 찾을 수 없어요.")
        if (time.now().phase != Phase.DAY) throw ApiException(ErrorCode.DAY_ONLY, "양 꾸미기는 낮에만 할 수 있어요.")
        if (sheep.version != request.ifVersion) {
            throw ApiException(ErrorCode.VERSION_CONFLICT, "다른 곳에서 먼저 꾸몄어요. 최신 배치를 불러와 주세요.")
        }
        val unknown = store.unknownStickers(request.placements.map { it.stickerId }.toSet())
        if (unknown.isNotEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "입력값을 확인해 주세요.",
                mapOf("placements" to "없는 스티커예요: ${unknown.sorted().joinToString(", ")}"))
        }
        val version = sheep.version + 1
        store.savePlacements(sheepId, request.placements, version)
        return sheep.copy(placements = request.placements, version = version)
    }

    private fun decodeCursor(cursor: String): SheepCursor {
        val (nightDate, id) = CursorCodec.decode(cursor, 2)
        val date = try {
            LocalDate.parse(nightDate)
        } catch (e: DateTimeParseException) {
            throw CursorCodec.invalid()
        }
        return SheepCursor(date, id)
    }
}
