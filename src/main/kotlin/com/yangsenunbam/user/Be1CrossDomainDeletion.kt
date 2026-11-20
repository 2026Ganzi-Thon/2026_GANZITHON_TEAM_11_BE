package com.yangsenunbam.user

import com.yangsenunbam.common.ApiException
import com.yangsenunbam.common.ErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The shared MySQL adapter deletes BE2/BE3 owned online data in the SAME
 * transaction as BE1 account deletion. Extend it when new ownership tables are added.
 * Do not return success until dependent data is gone.
 * External provider cleanup should be durable/outbox and auditable before release.
 */
interface CrossDomainAccountDeletionPort { fun deleteOwnedData(userId: String) }

@Configuration
class CrossDomainAccountDeletionConfig {
    @Bean
    @ConditionalOnMissingBean(CrossDomainAccountDeletionPort::class)
    fun missingCrossDomainDeletion(): CrossDomainAccountDeletionPort = object : CrossDomainAccountDeletionPort {
        override fun deleteOwnedData(userId: String): Unit = throw ApiException(
            ErrorCode.DATA_DELETION_UNAVAILABLE, "다른 파트의 데이터 삭제 연동이 필요해요.")
    }
}
