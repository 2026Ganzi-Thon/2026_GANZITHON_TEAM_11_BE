package com.yangsenunbam.session

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Configuration
@EnableScheduling
class SessionExpirySchedulingConfig

@Component
class SessionExpiryScheduler(private val sessions: SessionStore, private val clock: Clock) {
    // Core read/write operations also check expiry: scheduler delay never keeps a session writable.
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    fun closeAtMorning() { sessions.expire(clock.millis()) }
}
