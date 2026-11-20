package com.yangsenunbam.flock

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.LocalDate

interface FlockStore {
    fun sheepPage(userId: String, after: SheepCursor?, limit: Int): List<SheepResponse>

    fun sheepDetail(userId: String, sheepId: String): SheepDetailResponse?

    fun stickers(): List<StickerResponse>

    fun lockSheep(userId: String, sheepId: String): SheepResponse?

    fun unknownStickers(stickerIds: Set<String>): Set<String>

    fun savePlacements(sheepId: String, placements: List<Placement>, version: Int)
}

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
        override fun lockSheep(userId: String, sheepId: String): SheepResponse? = unconfigured()
        override fun unknownStickers(stickerIds: Set<String>): Set<String> = unconfigured()
        override fun savePlacements(sheepId: String, placements: List<Placement>, version: Int): Unit = unconfigured()

        private fun unconfigured(): Nothing =
            throw ApiException(ErrorCode.NOT_IMPLEMENTED, "BE3 양떼 저장소 어댑터 연결이 필요합니다.")
    }
}
