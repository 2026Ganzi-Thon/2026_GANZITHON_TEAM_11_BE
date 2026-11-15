package com.ganzithon.sheep.music

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class MusicService {
    fun search(user: CurrentUser, q: String, limit: Int): ItemsResponse<TrackResponse> = notImplemented()
}
