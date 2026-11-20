package com.yangsenunbam.music

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class MusicService(private val catalog: MusicCatalog) {
    /** GET /music/search — KR 고정, cursor 없음. 결과 없음은 빈 items, 외부 장애는 503 MUSIC_SEARCH_UNAVAILABLE. */
    fun search(user: CurrentUser, q: String, limit: Int): ItemsResponse<TrackResponse> {
        val query = q.trim()
        if (query.isEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "입력값을 확인해 주세요.", mapOf("q" to "검색어를 입력해 주세요."))
        }
        return ItemsResponse(catalog.search(query, limit))
    }
}
