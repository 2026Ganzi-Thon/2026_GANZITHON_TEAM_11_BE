package com.yangsenunbam.admin

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.card.SubmissionResponse
import org.springframework.stereotype.Service

// BE3
@Service
class ModerationService {
    fun pendingCards(user: CurrentUser, status: String, cursor: String?, limit: Int): PageResponse<SubmissionResponse> = notImplemented()

    fun moderate(user: CurrentUser, cardId: String, request: ModerationRequest): SubmissionResponse = notImplemented()
}
