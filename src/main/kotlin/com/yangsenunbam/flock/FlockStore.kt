package com.yangsenunbam.flock

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.LocalDate

/** BE3 양떼·스티커 조회 저장소. MySQL 구현은 [MysqlFlockStore]. */
interface FlockStore {
    /** 본인 양을 night_date·id 내림차순으로 [after] 다음부터 최대 [limit]개. */
    fun sheepPage(userId: String, after: SheepCursor?, limit: Int): List<SheepResponse>

    /** 본인 소유가 아니거나 없으면 null. */
    fun sheepDetail(userId: String, sheepId: String): SheepDetailResponse?

    /** 스티커 도감 전체, 정렬 순서대로. */
    fun stickers(): List<StickerResponse>
}

/** 양떼 목록 cursor가 가리키는 마지막 항목의 정렬 키. */
data class SheepCursor(val nightDate: LocalDate, val id: String)

@Configuration(proxyBeanMethods = false)
class FlockConfiguration {
    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(FlockStore::class)
    fun unconfiguredFlockStore(): FlockStore = object : FlockStore {
        override fun sheepPage(userId: String, after: SheepCursor?, limit: Int): List<SheepResponse> = unconfigured()
        override fun sheepDetail(userId: String, sheepId: String): SheepDetailResponse? = unconfigured()
        override fun stickers(): List<StickerResponse> = unconfigured()

        private fun unconfigured(): Nothing =
            throw ApiException(ErrorCode.NOT_IMPLEMENTED, "BE3 양떼 저장소 어댑터 연결이 필요합니다.")
    }
}
