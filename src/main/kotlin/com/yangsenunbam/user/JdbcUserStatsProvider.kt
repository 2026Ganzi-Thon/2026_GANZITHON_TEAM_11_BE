package com.yangsenunbam.user

import com.yangsenunbam.night.NightStore
import com.yangsenunbam.session.SessionStore
import org.springframework.stereotype.Component

@Component
class JdbcUserStatsProvider(private val nights: NightStore, private val sessions: SessionStore) : UserStatsProvider {
    override fun forUser(userId: String) = StatsResponse(
        nightCount = sessions.countDistinctNights(userId),
        countedSheepTotal = sessions.sheepSum(userId),
        worryRecordCount = nights.countWorries(userId),
    )
}
