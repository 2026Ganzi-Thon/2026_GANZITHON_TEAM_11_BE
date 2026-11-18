package com.yangsenunbam.fortune.persistence

import com.yangsenunbam.common.*
import com.yangsenunbam.feed.persistence.first
import com.yangsenunbam.feed.persistence.wire
import com.yangsenunbam.fortune.FortunePort
import com.yangsenunbam.fortune.FortuneResponse
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

@Repository
@Profile("mysql")
class MysqlFortunePort(private val jdbc: JdbcTemplate, manager: PlatformTransactionManager, private val mapper: JsonMapper) : FortunePort {
    private val transaction = TransactionTemplate(manager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout = 15
    }

    override fun zodiac(userId: String): Zodiac? = jdbc.first(
        "SELECT birth_date FROM user_onboarding WHERE user_id = ?", userId,
    ) { it.getDate("birth_date")?.toLocalDate() }?.let { zodiacFor(it) }

    override fun getOrCreate(date: LocalDate, zodiac: Zodiac, create: () -> FortuneResponse): FortuneResponse = transaction.execute {
        // 존재하지 않는 결과 행 대신 미리 만든 별자리 행을 잠가 최초 조회 경쟁도 직렬화한다.
        jdbc.queryForObject("SELECT zodiac FROM fortune_zodiac_locks WHERE zodiac = ? FOR UPDATE", String::class.java, zodiac.value)
        read("fortune_results", date, zodiac) ?: create().also { result ->
            require(result.date == date && result.zodiac == zodiac)
            jdbc.update("INSERT INTO fortune_results(fortune_date, zodiac, response_json) VALUES (?, ?, ?)",
                date, zodiac.value, mapper.writeValueAsString(result))
        }
    }

    override fun approvedDaily(date: LocalDate, zodiac: Zodiac) = read("fortune_approved_daily", date, zodiac)

    override fun approvedFallback(date: LocalDate, zodiac: Zodiac): FortuneResponse? = jdbc.first(
        "SELECT * FROM fortune_approved_templates WHERE zodiac = ?", zodiac.value,
    ) { FortuneResponse(date, zodiac, null, wire<SheepType>(it.getString("sheep_type")), it.getString("message"),
        it.getString("lucky_color"), it.getString("lucky_item"), it.getString("small_action")) }

    private fun read(table: String, date: LocalDate, zodiac: Zodiac) = jdbc.first(
        "SELECT response_json FROM $table WHERE fortune_date = ? AND zodiac = ?", date, zodiac.value,
    ) { mapper.readValue(it.getString("response_json"), FortuneResponse::class.java) }

    // BE1 UserService의 생년월일 경계와 동일하다. 회원/생년월일을 별도 복제하지 않는다.
    private fun zodiacFor(date: LocalDate): Zodiac = when (date.monthValue * 100 + date.dayOfMonth) {
        in 321..419 -> Zodiac.ARIES
        in 420..520 -> Zodiac.TAURUS
        in 521..620 -> Zodiac.GEMINI
        in 621..722 -> Zodiac.CANCER
        in 723..822 -> Zodiac.LEO
        in 823..922 -> Zodiac.VIRGO
        in 923..1022 -> Zodiac.LIBRA
        in 1023..1121 -> Zodiac.SCORPIO
        in 1122..1221 -> Zodiac.SAGITTARIUS
        in 1222..1231, in 101..119 -> Zodiac.CAPRICORN
        in 120..218 -> Zodiac.AQUARIUS
        else -> Zodiac.PISCES
    }
}
