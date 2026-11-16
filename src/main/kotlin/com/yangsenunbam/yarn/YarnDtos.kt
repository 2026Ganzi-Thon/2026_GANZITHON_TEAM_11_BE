package com.yangsenunbam.yarn

import java.time.LocalDate

data class YarnRequest(val sent: Boolean)
data class YarnResponse(val cardId: String, val sent: Boolean)
data class YarnSummaryResponse(val summary: YarnSummary?)
data class YarnSummary(val id: String, val date: LocalDate, val yarnCount: Long, val senderCount: Long)
