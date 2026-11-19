package com.yangsenunbam.flock

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeParseException

// BE3
@Service
class FlockService(private val store: FlockStore) {
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

    fun saveStickers(user: CurrentUser, sheepId: String, request: SaveStickersRequest): SheepResponse = notImplemented()

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
