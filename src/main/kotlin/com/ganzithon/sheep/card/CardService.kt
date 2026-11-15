package com.ganzithon.sheep.card

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class CardService {
    fun submit(user: CurrentUser, request: SubmitCardRequest, idempotencyKey: String): SubmissionResponse = notImplemented()

    fun myCards(user: CurrentUser, status: ModerationStatus?, cursor: String?, limit: Int): PageResponse<SubmissionResponse> = notImplemented()

    fun delete(user: CurrentUser, cardId: String): Unit = notImplemented()

    fun resubmit(user: CurrentUser, cardId: String, request: ResubmitCardRequest, idempotencyKey: String): SubmissionResponse = notImplemented()
}
