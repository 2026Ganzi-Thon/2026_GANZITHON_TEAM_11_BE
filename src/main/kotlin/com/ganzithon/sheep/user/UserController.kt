package com.ganzithon.sheep.user

import com.ganzithon.sheep.common.*
import com.ganzithon.sheep.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class UserController(private val service: UserService) {
    @GetMapping("/me")
    @NoRequestBody
    fun me(
        @AuthenticationPrincipal user: CurrentUser
    ): MeResponse = service.me(user)

    @GetMapping("/legal-documents")
    @NoRequestBody
    fun legalDocuments(

    ): ItemsResponse<LegalDocumentResponse> = service.legalDocuments()

    @PutMapping("/me/consents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun saveConsents(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: ConsentsRequest
    ): Unit = service.saveConsents(user, request)

    @PutMapping("/me/day-intro")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun acknowledgeDayIntro(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: DayIntroRequest
    ): Unit = service.acknowledgeDayIntro(user, request)

    @PutMapping("/me/birth-date")
    fun saveBirthDate(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: BirthDateRequest
    ): BirthDateResponse = service.saveBirthDate(user, request)

    @PutMapping("/me/reminder")
    fun saveReminder(
        @AuthenticationPrincipal user: CurrentUser,
        @Valid @RequestBody request: ReminderRequest
    ): ReminderResponse = service.saveReminder(user, request)

    @GetMapping("/me/worries")
    @NoRequestBody
    fun worries(
        @AuthenticationPrincipal user: CurrentUser,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int
    ): PageResponse<WorryListItem> = service.worries(user, cursor, limit)

    @DeleteMapping("/me")
    @NoRequestBody
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAccount(
        @AuthenticationPrincipal user: CurrentUser
    ): Unit = service.deleteAccount(user)
}
