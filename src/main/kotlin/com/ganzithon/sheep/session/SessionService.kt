package com.ganzithon.sheep.session

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE1
@Service
class SessionService {
    fun create(user: CurrentUser, nightId: String, request: CreateSessionRequest, idempotencyKey: String): SessionResponse = notImplemented()

    fun finish(user: CurrentUser, sessionId: String, request: FinishRequest, idempotencyKey: String): FinishResponse = notImplemented()
}
