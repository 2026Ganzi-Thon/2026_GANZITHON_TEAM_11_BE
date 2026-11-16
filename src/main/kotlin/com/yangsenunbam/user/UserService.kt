package com.yangsenunbam.user

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service

// BE1
@Service
class UserService {
    fun me(user: CurrentUser): MeResponse = notImplemented()

    fun legalDocuments(): ItemsResponse<LegalDocumentResponse> = notImplemented()

    fun saveConsents(user: CurrentUser, request: ConsentsRequest): Unit = notImplemented()

    fun acknowledgeDayIntro(user: CurrentUser, request: DayIntroRequest): Unit = notImplemented()

    fun saveBirthDate(user: CurrentUser, request: BirthDateRequest): BirthDateResponse = notImplemented()

    fun saveReminder(user: CurrentUser, request: ReminderRequest): ReminderResponse = notImplemented()

    fun worries(user: CurrentUser, cursor: String?, limit: Int): PageResponse<WorryListItem> = notImplemented()

    fun deleteAccount(user: CurrentUser): Unit = notImplemented()
}
