package com.yangsenunbam.flock

import com.yangsenunbam.bootstrap.KstNightTime
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Service
class FlockService(private val store: FlockStore, private val time: KstNightTime) {
    fun list(user: CurrentUser, cursor: String?, limit: Int): PageResponse<SheepResponse> {
        val after = cursor?.let(::decodeCursor)
        val rows = store.sheepPage(user.id, after, limit + 1)
        val hasMore = rows.size > limit
        val page = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore) page.last().let { CursorCodec.encode(it.nightDate.toString(), it.id) } else null
        return PageResponse(page, nextCursor)
    }

    fun detail(user: CurrentUser, sheepId: String): SheepDetailResponse =
        store.sheepDetail(user.id, sheepId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "양을 찾을 수 없어요.")

    fun stickers(user: CurrentUser): ItemsResponse<StickerResponse> = ItemsResponse(store.stickers())

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
