package com.yangsenunbam.morning

import com.yangsenunbam.feed.NightTime
import com.yangsenunbam.feed.persistence.MysqlNightStore
import com.yangsenunbam.feed.port.ownedNight
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
@EnableScheduling
class NightClosingSchedule

/** 서버 재시작/작업 지연 시에도 지난 밤을 마감한다. 변경 API의 커밋 검증은 별도로 유지한다. */
@Component
@Profile("mysql")
class MysqlNightClosingJob(
    private val jdbc: JdbcTemplate, private val store: MysqlNightStore,
    private val finalizer: NightFinalizer, private val time: NightTime,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun closeOverdue() {
        val nights = jdbc.query("""SELECT n.id, n.user_id FROM be1_nights n
            LEFT JOIN night_feed_state f ON f.night_id = n.id
            WHERE n.night_date < ? AND COALESCE(f.default_frozen, FALSE) = FALSE ORDER BY n.night_date, n.id LIMIT 100""",
            RowMapper { rs, _ -> rs.getString("id") to rs.getString("user_id") }, time.fortuneDate())
        nights.forEach { (nightId, userId) ->
            try {
                store.withUser(userId) { tx -> finalizer.finalize(tx, tx.ownedNight(userId, nightId)) }
            } catch (ex: Exception) {
                // 다음 주기에 재시도한다. 고민/사용자/카드 내용은 로그에 남기지 않는다.
                logger.warn("Night closing will retry: {}", ex.javaClass.simpleName)
            }
        }
    }
}
