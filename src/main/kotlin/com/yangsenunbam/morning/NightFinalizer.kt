package com.yangsenunbam.morning

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.NightCards
import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.feed.model.CardView
import com.yangsenunbam.feed.model.NightRecord
import com.yangsenunbam.feed.port.NightTransaction
import org.springframework.stereotype.Component

/** 마감 배치와 조회가 같은 트랜잭션 안에서 호출하는 멱등 처리. */
@Component
class NightFinalizer(private val time: NightTime) {
    fun finalize(tx: NightTransaction, night: NightRecord): NightRecord {
        if (time.now().isBefore(night.closesAt)) {
            throw ApiException(ErrorCode.MORNING_NOT_OPEN, "아직 마감되지 않은 밤입니다.")
        }
        val sessions = tx.sessions(night.id)
        sessions.filter { it.state.status == SessionStatus.ACTIVE }.forEach {
            tx.saveSession(it.copy(state = it.state.copy(status = SessionStatus.EXPIRED)))
        }
        val defaultCardId = if (night.defaultFrozen) night.defaultCardId else {
            val available = NightCards.views(sessions)
                .sortedWith(compareByDescending<CardView> { it.viewedAt }
                    .thenByDescending { it.order }.thenBy { it.cardId })
                .map { tx.cardAt(it.cardId, night.closesAt.minusNanos(1)) }
                .filter { it.available }
            (available.firstOrNull { it.tone == CardTone.WARM } ?: available.firstOrNull())?.id
        }
        val writingState = when (night.writingState) {
            WritingState.OFFERED, WritingState.WRITING -> WritingState.DEFERRED
            else -> night.writingState
        }
        return night.copy(defaultFrozen = true, defaultCardId = defaultCardId, writingState = writingState).also {
            if (it != night) tx.saveNight(it)
        }
    }
}
