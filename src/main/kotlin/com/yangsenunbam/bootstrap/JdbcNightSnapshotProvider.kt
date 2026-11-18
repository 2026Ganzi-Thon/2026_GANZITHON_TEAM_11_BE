package com.yangsenunbam.bootstrap

import com.yangsenunbam.common.*
import com.yangsenunbam.night.NightStore
import com.yangsenunbam.session.MorningCandidatePort
import com.yangsenunbam.session.SessionService
import com.yangsenunbam.session.SessionStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate

/** Replaces EmptyNightSnapshotProvider; uses BE1 session records and a BE2 morning adapter. */
@Component
class JdbcNightSnapshotProvider(
    private val nights: NightStore,
    private val sessions: SessionStore,
    private val service: SessionService,
    private val morning: MorningCandidatePort,
    private val mapper: JsonMapper,
    private val clock: Clock,
    private val time: KstNightTime,
) : NightSnapshotProvider {
    @Transactional
    override fun forUser(userId: String, nightDate: LocalDate): NightSnapshot {
        nights.lockAccount(userId)
        sessions.expireForUser(userId, clock.millis())
        val night = nights.byDate(userId, nightDate) ?: return NightSnapshot()
        val latest = sessions.latestForNight(night.id)
        val current = sessions.activeForNight(night.id)
        val closed = latest?.status == SessionStatus.STOPPED && latest.closingViewedAtMs == null
        val isDay = time.now().phase == Phase.DAY
        // For a night with sessions, an unconfigured BE2 adapter fails closed rather than
        // silently showing HOME and skipping morning adoption.
        val needsAdoption = isDay && latest != null && morning.hasCandidate(userId, night.id)
        val support = if (night.supportTriggered) night.supportJson?.let {
            mapper.readValue(it, SupportResponse::class.java)
        } else null
        return NightSnapshot(
            nightId = night.id, activeSessionId = current?.id,
            lastSession = latest?.let(service::latestSummary),
            support = support, supportTriggered = night.supportTriggered,
            closingPending = closed, hasWorry = true, hasUnadoptedCandidate = needsAdoption,
        )
    }
}
