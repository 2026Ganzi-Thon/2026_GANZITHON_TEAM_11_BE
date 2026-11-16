package com.yangsenunbam.bootstrap

import com.yangsenunbam.common.*
import com.yangsenunbam.session.NightSummary
import com.yangsenunbam.user.ReminderResponse
import com.yangsenunbam.user.UserResponse
import java.time.Instant
import java.time.LocalDate

data class BootstrapResponse(
    val serverTime: Instant,
    val timezone: String,
    val phase: Phase,
    val nightDate: LocalDate,
    val nextScreen: NextScreen,
    val user: UserResponse,
    val nightId: String?,
    val activeSessionId: String?,
    val lastSession: NightSummary?,
    val support: SupportResponse?,
    val reminder: ReminderResponse,
    val requiredConsents: RequiredConsents,
)
data class RequiredConsents(val termsVersion: String, val privacyVersion: String)
