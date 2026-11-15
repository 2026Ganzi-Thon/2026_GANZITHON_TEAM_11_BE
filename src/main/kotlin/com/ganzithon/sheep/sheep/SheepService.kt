package com.ganzithon.sheep.sheep

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class SheepService {
    fun list(user: CurrentUser, cursor: String?, limit: Int): PageResponse<SheepResponse> = notImplemented()

    fun detail(user: CurrentUser, sheepId: String): SheepDetailResponse = notImplemented()

    fun stickers(user: CurrentUser): ItemsResponse<StickerResponse> = notImplemented()

    fun saveStickers(user: CurrentUser, sheepId: String, request: SaveStickersRequest): SheepResponse = notImplemented()
}
