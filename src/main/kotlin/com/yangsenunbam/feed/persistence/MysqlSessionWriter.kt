package com.yangsenunbam.feed.persistence

import com.yangsenunbam.common.SessionStatus
import com.yangsenunbam.feed.FeedBuilder
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.feed.model.FeedSession
import com.yangsenunbam.feed.port.ownedNight
import com.yangsenunbam.session.SessionResponse
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** BE1이 지원 상태/요청 멱등성을 검증한 후 호출하는 세션·피드 원자적 생성 지점. */
@Service
@Profile("mysql")
class MysqlSessionWriter(private val store: MysqlNightStore, private val builder: FeedBuilder, private val time: NightTime) {
    fun create(userId: String, nightId: String, sessionId: String, count: Int): SessionResponse = store.withUser(userId) { tx ->
        val night = tx.ownedNight(userId, nightId)
        time.guardOpen(tx, night.closesAt)
        val items = builder.build(userId, count, night.categoryId)
        val state = SessionResponse(sessionId, nightId, SessionStatus.ACTIVE, count, 1, 0, 0, 0, 1, time.now(), night.closesAt)
        store.createSession(FeedSession(state, userId, items))
        state
    }
}
