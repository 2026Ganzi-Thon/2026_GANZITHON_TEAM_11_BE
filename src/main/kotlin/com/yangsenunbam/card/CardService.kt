package com.yangsenunbam.card

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class CardService {
    fun submit(user: CurrentUser, request: SubmitCardRequest, idempotencyKey: String): SubmissionResponse = notImplemented()

    fun myCards(user: CurrentUser, status: ModerationStatus?, cursor: String?, limit: Int): PageResponse<SubmissionResponse> = notImplemented()

    fun delete(user: CurrentUser, cardId: String): Unit = notImplemented()

    fun resubmit(user: CurrentUser, cardId: String, request: ResubmitCardRequest, idempotencyKey: String): SubmissionResponse = notImplemented()
}
