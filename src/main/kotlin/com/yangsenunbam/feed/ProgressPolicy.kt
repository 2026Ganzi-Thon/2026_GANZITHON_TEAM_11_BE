package com.yangsenunbam.feed

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.feed.model.*
import java.time.Instant

/** BE1 finish에서도 같은 정책을 사용해 종료와 마지막 진행을 함께 저장할 수 있다. */
object ProgressPolicy {
    fun apply(night: NightRecord, session: FeedSession, request: ProgressRequest, now: Instant): ProgressUpdate {
        val state = session.state
        if (request.writerEpoch != state.writerEpoch) {
            throw ApiException(ErrorCode.WRITER_REPLACED, "다른 기기에서 저장권을 갱신했습니다.")
        }
        if (request.position !in 1..request.furthestPosition || request.furthestPosition > state.sheepCount ||
            request.sequence < 1 || request.writerEpoch < 1) {
            throw ApiException(ErrorCode.VALIDATION_ERROR, "진행 위치와 저장 순서를 확인해 주세요.")
        }
        if (request.sequence < state.sequence) return ProgressUpdate(night, session)
        if (request.sequence == state.sequence) {
            if (session.lastProgress != request) {
                throw ApiException(ErrorCode.SEQUENCE_CONFLICT, "같은 저장 순서에 다른 진행 내용이 전달되었습니다.")
            }
            return ProgressUpdate(night, session)
        }

        val furthest = maxOf(state.furthestPosition, request.furthestPosition)
        val views = session.views.toMutableMap()
        var order = night.viewSequence
        // 새 최대 도달 구간과 현재 표시한 칸만 갱신한다. 과거 전체의 시각은 바꾸지 않는다.
        val newlyReached = session.items.filter { it.position > state.furthestPosition && it.position <= furthest }
        val displayed = session.items.single { it.position == request.position }
        (newlyReached.filter { it.position != request.position } + displayed).filterIsInstance<StoredCard>().forEach {
            views[it.cardId] = CardView(it.cardId, state.id, it.position, state.sheepCount, now, ++order)
        }
        return ProgressUpdate(
            night.copy(viewSequence = order),
            session.copy(
                state = state.copy(position = request.position, furthestPosition = furthest, sequence = request.sequence),
                lastProgress = request, views = views,
            ),
        )
    }
}

data class ProgressUpdate(val night: NightRecord, val session: FeedSession)
