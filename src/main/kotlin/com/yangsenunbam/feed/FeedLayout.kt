package com.yangsenunbam.feed

import com.yangsenunbam.common.*

data class FeedSlot(val position: Int, val tone: CardTone?, val song: Boolean = false, val pause: PauseKind? = null)

/** API v0.6 §13의 위치표. 팀 합의 전인 배치 규칙 변경은 이 정책에서만 처리한다. */
object FeedLayout {
    private val songPositions = mapOf(
        3 to emptyList(), 4 to emptyList(), 5 to listOf(3), 6 to listOf(3),
        7 to listOf(5), 8 to listOf(5), 9 to listOf(6), 10 to listOf(6),
        11 to listOf(8), 12 to listOf(8), 13 to listOf(8), 14 to listOf(8),
        15 to listOf(8, 12), 16 to listOf(8, 12), 17 to listOf(8, 15), 18 to listOf(8, 15),
        19 to listOf(8, 16), 20 to listOf(8, 17), 21 to listOf(9, 18), 22 to listOf(9, 19),
        23 to listOf(10, 20), 24 to listOf(10, 22), 25 to listOf(10, 22), 26 to listOf(11, 23),
        27 to listOf(11, 24), 28 to listOf(12, 25), 29 to listOf(12, 26), 30 to listOf(12, 27),
    )

    fun slots(count: Int, category: CategoryId): List<FeedSlot> {
        require(count in 3..30) { "양 개수는 3~30입니다." }
        val noWorry = category == CategoryId.NONE
        val songs = when {
            noWorry && count in 7..12 -> listOf((count + 1) / 2)
            noWorry && count == 15 -> listOf(6, 12)
            else -> songPositions.getValue(count)
        }
        var ordinary = 0
        return (1..count).map { position ->
            when {
                count >= 7 && !noWorry && position == minOf(6, (count + 1) / 2) -> FeedSlot(position, null, pause = PauseKind.ENTRUST_WORRY)
                count >= 16 && position == 14 -> FeedSlot(position, null, pause = PauseKind.STOP_OFFER)
                position == count || position in setOf(7, 13, 21, 28) -> FeedSlot(position, CardTone.WARM)
                position in songs -> FeedSlot(position, null, song = true)
                else -> FeedSlot(position, if (noWorry || ordinary++ % 3 == 2) CardTone.LIGHT else CardTone.EMPATHY)
            }
        }
    }
}
