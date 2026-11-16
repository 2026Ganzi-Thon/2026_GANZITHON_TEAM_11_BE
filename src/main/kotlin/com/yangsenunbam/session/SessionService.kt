package com.yangsenunbam.session

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE1
@Service
class SessionService {
    fun create(user: CurrentUser, nightId: String, request: CreateSessionRequest, idempotencyKey: String): SessionResponse = notImplemented()

    fun finish(user: CurrentUser, sessionId: String, request: FinishRequest, idempotencyKey: String): FinishResponse = notImplemented()
}
