package com.yangsenunbam.morning

import com.yangsenunbam.feed.NightCards
import com.yangsenunbam.feed.port.NightStore
import com.yangsenunbam.feed.port.ownedNight
import com.yangsenunbam.session.MorningCandidatePort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("mysql")
class MysqlMorningCandidateAdapter(private val store: NightStore, private val finalizer: NightFinalizer) : MorningCandidatePort {
    override fun hasCandidate(userId: String, nightId: String): Boolean = store.withUser(userId) { tx ->
        val night = finalizer.finalize(tx, tx.ownedNight(userId, nightId))
        night.adoption == null && NightCards.views(tx.sessions(nightId)).any { tx.card(it.cardId).available }
    }
}
