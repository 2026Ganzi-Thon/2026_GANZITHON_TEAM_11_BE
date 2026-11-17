package com.yangsenunbam.fortune

import com.yangsenunbam.common.Zodiac
import java.time.LocalDate

/** BE1 생년월일 정보와 검수된 일일 운세 저장소를 연결한다. */
interface FortunePort {
    fun zodiac(userId: String): Zodiac?

    /** 날짜·별자리 고유 제약 아래 최초 결과를 영속화한다. 실패한 create는 저장하지 않는다. */
    fun getOrCreate(date: LocalDate, zodiac: Zodiac, create: () -> FortuneResponse): FortuneResponse

    fun approvedDaily(date: LocalDate, zodiac: Zodiac): FortuneResponse?

    /** 검수된 문구를 해당 날짜로 구성한다. 외부 생성 모델을 요청마다 호출하지 않는다. */
    fun approvedFallback(date: LocalDate, zodiac: Zodiac): FortuneResponse?
}
