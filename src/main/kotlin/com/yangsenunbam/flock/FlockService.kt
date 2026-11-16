package com.yangsenunbam.flock

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class FlockService {
    fun list(user: CurrentUser, cursor: String?, limit: Int): PageResponse<SheepResponse> = notImplemented()

    fun detail(user: CurrentUser, sheepId: String): SheepDetailResponse = notImplemented()

    fun stickers(user: CurrentUser): ItemsResponse<StickerResponse> = notImplemented()

    fun saveStickers(user: CurrentUser, sheepId: String, request: SaveStickersRequest): SheepResponse = notImplemented()
}
