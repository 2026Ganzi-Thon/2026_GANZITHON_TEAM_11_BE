package com.yangsenunbam.feed.persistence

import com.yangsenunbam.common.WireValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.Instant

internal fun <T> JdbcTemplate.first(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    query(sql, RowMapper { rs, _ -> map(rs) }, *args).firstOrNull()

internal fun ResultSet.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))
internal fun ResultSet.optionalInstant(column: String): Instant? = getObject(column)?.let { instant(column) }
internal fun ResultSet.optionalInt(column: String): Int? = getObject(column)?.let { getInt(column) }
internal inline fun <reified T> wire(value: String): T where T : Enum<T>, T : WireValue =
    enumValues<T>().single { it.value == value }
