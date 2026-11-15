package com.ganzithon.sheep.yarn

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import org.springframework.stereotype.Service

// BE3
@Service
class YarnService {
    fun save(user: CurrentUser, cardId: String, request: YarnRequest): YarnResponse = notImplemented()

    fun summary(user: CurrentUser): YarnSummaryResponse = notImplemented()

    fun markSeen(user: CurrentUser, summaryId: String): Unit = notImplemented()
}
