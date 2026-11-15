package com.ganzithon.sheep.admin

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import com.ganzithon.sheep.card.SubmissionResponse
import org.springframework.stereotype.Service

// BE3
@Service
class ModerationService {
    fun pendingCards(user: CurrentUser, status: String, cursor: String?, limit: Int): PageResponse<SubmissionResponse> = notImplemented()

    fun moderate(user: CurrentUser, cardId: String, request: ModerationRequest): SubmissionResponse = notImplemented()
}
