package com.yangsenunbam.user

import com.yangsenunbam.auth.AuthStore
import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** BE1 phase 2: account info, consents, date of birth, day intro and reminders. */
@Service
class UserService(
    private val auth: AuthStore,
    private val store: OnboardingStore,
    private val legal: LegalPolicy,
    private val stats: UserStatsProvider,
    private val clock: Clock,
) {
    fun profile(userId: String): OnboardingProfile = store.find(userId)

    fun user(userId: String): UserResponse {
        val account = auth.findUserById(userId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다.")
        val profile = store.find(userId)
        return UserResponse(account.id, account.email, profile.birthDate, profile.editsRemaining(), stats.forUser(userId))
    }
    fun me(user: CurrentUser) = MeResponse(user(user.id), profile(user.id).reminder())
    fun legalDocuments() = legal.requireDocuments()

    @Transactional
    fun saveConsents(user: CurrentUser, request: ConsentsRequest) {
        legal.requireDocuments() // Missing approved policy must prevent consent acceptance.
        if (!request.termsAccepted || !request.privacyAccepted) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "필수 약관 동의가 필요합니다.")
        }
        if (request.termsVersion != legal.termsVersion || request.privacyVersion != legal.privacyVersion) {
            throw ApiException(ErrorCode.CONSENT_VERSION_CHANGED, "최신 약관을 다시 확인해 주세요.")
        }
        store.lock(user.id)
        store.saveConsents(user.id, request.termsVersion, request.privacyVersion, clock.millis())
        store.synchronizeAuthGate(user.id, legal)
    }

    @Transactional
    fun acknowledgeDayIntro(user: CurrentUser, request: DayIntroRequest) {
        if (!request.acknowledged) throw ApiException(ErrorCode.VALIDATION_ERROR, "안내 확인이 필요합니다.")
        store.lock(user.id)
        store.acknowledgeDayIntro(user.id)
    }

    @Transactional
    fun saveBirthDate(user: CurrentUser, request: BirthDateRequest): BirthDateResponse {
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
        val date = request.birthDate
        if (date.isAfter(today)) throw ApiException(ErrorCode.VALIDATION_ERROR, "미래 날짜는 입력할 수 없습니다.")
        // A-09 is a proposal in API v0.6, subject to team/legal approval.
        if (Period.between(date, today).years < 14) {
            throw ApiException(ErrorCode.AGE_NOT_ALLOWED, "현재 연령으로는 가입할 수 없습니다.")
        }
        val stored = store.lock(user.id)
        if (stored.birthDate != null && stored.birthDate != date && stored.birthDateEditsUsed >= 1) {
            throw ApiException(ErrorCode.BIRTH_DATE_EDIT_LIMIT, "생년월일 변경 횟수를 모두 사용했습니다.")
        }
        val edits = stored.birthDateEditsUsed + if (stored.birthDate != null && stored.birthDate != date) 1 else 0
        if (stored.birthDate != date) store.saveBirthDate(user.id, date, edits)
        store.synchronizeAuthGate(user.id, legal)
        return BirthDateResponse(date, if (edits >= 1) 0 else 1, zodiac(date))
    }

    @Transactional
    fun saveReminder(user: CurrentUser, request: ReminderRequest): ReminderResponse {
        val valid = request.localTime?.matches(Regex("(?:[01][0-9]|2[0-3]):(?:00|15|30|45)")) ?: false
        if ((request.enabled && !valid) || (!request.enabled && request.localTime != null)) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "알림 시간을 15분 단위로 설정해 주세요.")
        }
        store.lock(user.id)
        store.saveReminder(user.id, request.enabled, request.localTime)
        return ReminderResponse(request.enabled, request.localTime)
    }

    fun worries(user: CurrentUser, cursor: String?, limit: Int): PageResponse<WorryListItem> = notImplemented()
    fun deleteAccount(user: CurrentUser): Unit = notImplemented()
}

/** Phase 3/4 needs to replace this adapter with real night/session aggregates. */
interface UserStatsProvider { fun forUser(userId: String): StatsResponse }

@org.springframework.stereotype.Component
class EmptyStatsProvider : UserStatsProvider {
    override fun forUser(userId: String) = StatsResponse(0, 0, 0)
}

fun zodiac(date: LocalDate): Zodiac = when (date.monthValue * 100 + date.dayOfMonth) {
    in 321..419 -> Zodiac.ARIES
    in 420..520 -> Zodiac.TAURUS
    in 521..620 -> Zodiac.GEMINI
    in 621..722 -> Zodiac.CANCER
    in 723..822 -> Zodiac.LEO
    in 823..922 -> Zodiac.VIRGO
    in 923..1022 -> Zodiac.LIBRA
    in 1023..1121 -> Zodiac.SCORPIO
    in 1122..1221 -> Zodiac.SAGITTARIUS
    in 1222..1231, in 101..119 -> Zodiac.CAPRICORN
    in 120..218 -> Zodiac.AQUARIUS
    else -> Zodiac.PISCES
}
