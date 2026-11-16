package com.yangsenunbam.fortune

import com.yangsenunbam.common.SheepType
import com.yangsenunbam.common.Zodiac
import java.time.LocalDate

data class FortuneResponse(
    val date: LocalDate, val zodiac: Zodiac, val rank: Int?, val sheepType: SheepType,
    val message: String, val luckyColor: String, val luckyItem: String, val smallAction: String,
)
