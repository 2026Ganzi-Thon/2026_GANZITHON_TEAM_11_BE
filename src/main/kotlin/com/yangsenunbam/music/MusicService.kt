package com.yangsenunbam.music

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class MusicService {
    fun search(user: CurrentUser, q: String, limit: Int): ItemsResponse<TrackResponse> = notImplemented()
}
