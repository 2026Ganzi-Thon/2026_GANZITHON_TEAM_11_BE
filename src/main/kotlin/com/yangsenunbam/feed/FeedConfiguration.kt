package com.yangsenunbam.feed

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import com.yangsenunbam.feed.port.NightStore
import com.yangsenunbam.feed.port.NightTransaction
import com.yangsenunbam.feed.port.FeedCardSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
class FeedConfiguration {
    @Bean
    fun feedBuilder(source: FeedCardSource): FeedBuilder = FeedBuilder(source)

    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(FeedCardSource::class)
    fun unconfiguredFeedCardSource(): FeedCardSource = FeedCardSource {
        throw ApiException(ErrorCode.NOT_IMPLEMENTED, "검수된 카드 공급 어댑터 연결이 필요합니다.")
    }

    @Bean
    @Profile("!mysql")
    @ConditionalOnMissingBean(NightStore::class)
    fun unconfiguredNightStore(): NightStore = object : NightStore {
        override fun <T> withUser(userId: String, block: (NightTransaction) -> T): T =
            throw ApiException(ErrorCode.NOT_IMPLEMENTED, "BE2 저장소 어댑터 연결이 필요합니다.")
    }
}
