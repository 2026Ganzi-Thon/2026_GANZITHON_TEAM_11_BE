package com.yangsenunbam.bootstrap

import com.yangsenunbam.common.*
import com.yangsenunbam.security.CurrentUser
import com.yangsenunbam.session.NightSummary
import com.yangsenunbam.user.*
import org.springframework.stereotype.Service
import java.time.LocalDate

/** This interface must be implemented against BE1 night + BE2 session tables in phases 3/4. */
data class NightSnapshot(
    val nightId: String? = null,
    val activeSessionId: String? = null,
    val lastSession: NightSummary? = null,
    val support: SupportResponse? = null,
    val supportTriggered: Boolean = false,
    val closingPending: Boolean = false,
    val hasWorry: Boolean = false,
    val hasUnadoptedCandidate: Boolean = false,
)
interface NightSnapshotProvider { fun forUser(userId: String, nightDate: LocalDate): NightSnapshot }

/** Only valid while night/session APIs have not yet been implemented. Must be replaced before release. */
class EmptyNightSnapshotProvider : NightSnapshotProvider {
    override fun forUser(userId: String, nightDate: LocalDate) = NightSnapshot()
}

@Service
class BootstrapService(
    private val users: UserService,
    private val legal: LegalPolicy,
    private val clock: KstNightTime,
    private val nights: NightSnapshotProvider,
) {
    fun bootstrap(user: CurrentUser): BootstrapResponse {
        // Missing approved policy configuration must never produce fake consent requirements.
        legal.requireDocuments()
        val moment = clock.now()
        val profile = users.profile(user.id)
        val snapshot = if (profile.complete(legal)) nights.forUser(user.id, moment.nightDate) else NightSnapshot()

        val next = when {
            profile.termsVersion != legal.termsVersion || profile.privacyVersion != legal.privacyVersion -> NextScreen.ONBOARDING_CONSENT
            profile.birthDate == null -> NextScreen.ONBOARDING_BIRTH_DATE
            moment.phase == Phase.DAY && !profile.dayIntroAcknowledged -> NextScreen.DAY_INTRO
            moment.phase == Phase.DAY && snapshot.hasUnadoptedCandidate -> NextScreen.MORNING_ADOPTION
            moment.phase == Phase.DAY -> NextScreen.HOME
            snapshot.supportTriggered -> NextScreen.NIGHT_SUPPORT
            snapshot.activeSessionId != null -> NextScreen.NIGHT_FEED
            snapshot.closingPending -> NextScreen.NIGHT_CLOSING
            snapshot.lastSession != null -> NextScreen.NIGHT_RETURN
            snapshot.hasWorry -> NextScreen.NIGHT_SHEEP_COUNT
            else -> NextScreen.NIGHT_WORRY
        }
        // If the night snapshot flags a crisis, a support payload must also be supplied by the real adapter.
        if (next == NextScreen.NIGHT_SUPPORT && snapshot.support == null) {
            throw ApiException(ErrorCode.INTERNAL_ERROR, "지원 안내 정보가 준비되지 않았습니다.")
        }
        return BootstrapResponse(
            serverTime = moment.instant, timezone = "Asia/Seoul", phase = moment.phase,
            nightDate = moment.nightDate, nextScreen = next,
            user = users.user(user.id), nightId = snapshot.nightId,
            activeSessionId = snapshot.activeSessionId,
            lastSession = if (next == NextScreen.NIGHT_CLOSING || next == NextScreen.NIGHT_RETURN) snapshot.lastSession else null,
            support = if (next == NextScreen.NIGHT_SUPPORT) snapshot.support else null,
            reminder = profile.reminder(),
            requiredConsents = RequiredConsents(legal.termsVersion, legal.privacyVersion),
        )
    }
}
