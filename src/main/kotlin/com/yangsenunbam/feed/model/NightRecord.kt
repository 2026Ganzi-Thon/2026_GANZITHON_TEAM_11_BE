package com.yangsenunbam.feed.model

import com.yangsenunbam.common.*
import com.yangsenunbam.flock.SheepResponse
import com.yangsenunbam.morning.ReflectionResponse
import java.time.Instant
import java.time.LocalDate

/** BE1의 밤 기록과 BE2 상태를 저장소 어댑터에서 합쳐 읽는 모델. */
data class NightRecord(
    val id: String,
    val userId: String,
    val nightDate: LocalDate,
    val closesAt: Instant,
    val categoryId: CategoryId,
    val worryText: String?,
    val worrySavedAt: Instant?,
    val writingState: WritingState? = null,
    val viewSequence: Long = 0,
    val reactions: Map<String, NightReaction> = emptyMap(),
    val defaultFrozen: Boolean = false,
    val defaultCardId: String? = null,
    val adoption: AdoptionRecord? = null,
    val reflection: ReflectionResponse? = null,
)

data class NightReaction(val fluffSaved: Boolean = false, val songSavedAt: Instant? = null)

/** BE3 양 상세에서 사용하는 채택 당시의 열람 위치도 함께 보존한다. */
data class AdoptionRecord(val sheep: SheepResponse, val position: Int?, val sheepCount: Int)
