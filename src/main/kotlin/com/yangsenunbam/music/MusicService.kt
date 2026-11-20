package com.yangsenunbam.music

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

@Service
class MusicService(private val catalog: MusicCatalog) {
    fun search(user: CurrentUser, q: String, limit: Int): ItemsResponse<TrackResponse> {
        val query = q.trim()
        if (query.isEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "입력값을 확인해 주세요.", mapOf("q" to "검색어를 입력해 주세요."))
        }
        return ItemsResponse(catalog.search(query, limit))
    }
}
