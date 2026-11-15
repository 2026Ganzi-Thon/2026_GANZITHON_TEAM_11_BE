package com.ganzithon.sheep.fortune

import com.ganzithon.sheep.common.SheepType
import com.ganzithon.sheep.common.Zodiac
import java.time.LocalDate

data class FortuneResponse(
    val date: LocalDate, val zodiac: Zodiac, val rank: Int?, val sheepType: SheepType,
    val message: String, val luckyColor: String, val luckyItem: String, val smallAction: String,
)
