package com.yangsenunbam.night

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Called after the account/night locks, in the worry deletion transaction. */
interface WorryReflectionDeletionPort { fun deleteReflection(nightId: String) }

@Component
@Profile("mysql")
@Transactional(propagation = Propagation.MANDATORY)
class MysqlWorryReflectionDeletion(private val jdbc: JdbcTemplate) : WorryReflectionDeletionPort {
    override fun deleteReflection(nightId: String) {
        jdbc.update("UPDATE night_feed_state SET reflection_answer = NULL, reflection_message = NULL WHERE night_id = ?", nightId)
    }
}

/** The standalone H2 profile has no BE2 reflection storage. */
@Configuration(proxyBeanMethods = false)
@Profile("!mysql")
class StandaloneWorryReflectionDeletion {
    @Bean
    fun standaloneReflectionPort() = object : WorryReflectionDeletionPort {
        override fun deleteReflection(nightId: String) = Unit
    }
}
