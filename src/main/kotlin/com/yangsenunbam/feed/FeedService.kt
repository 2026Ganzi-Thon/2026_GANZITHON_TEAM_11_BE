package com.yangsenunbam.feed

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.session.*
import org.springframework.stereotype.Service

// BE2
@Service
class FeedService {
    fun feed(user: CurrentUser, sessionId: String): FeedResponse = notImplemented()

    fun resume(user: CurrentUser, sessionId: String, idempotencyKey: String): SessionResponse = notImplemented()

    fun saveProgress(user: CurrentUser, sessionId: String, request: ProgressRequest): ProgressResponse = notImplemented()

    fun handlePause(user: CurrentUser, sessionId: String, itemId: String, request: PauseRequest): PauseStateResponse = notImplemented()

    fun acknowledgeClosing(user: CurrentUser, sessionId: String, request: ClosingViewRequest): Unit = notImplemented()

    fun saveFluff(user: CurrentUser, nightId: String, cardId: String, request: SaveRequest): SaveResponse = notImplemented()

    fun saveSong(user: CurrentUser, nightId: String, cardId: String, request: SaveRequest): SaveResponse = notImplemented()
}
