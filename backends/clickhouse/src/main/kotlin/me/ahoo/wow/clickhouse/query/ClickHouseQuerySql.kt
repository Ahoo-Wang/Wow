/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.schema.QueryViolation
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * One statement and its server-side parameters. Every caller value is bound as a typed parameter (`{p0:String}`);
 * the SQL text holds only identifiers from the compiled schema, function names and finite numeric constants.
 */
internal class ClickHouseStatement(val sql: String, val parameters: Map<String, Any> = emptyMap()) {
    override fun toString(): String = sql
}

/** The parameters of one statement, named `p0`, `p1`, … in binding order. */
internal class SqlParameters {
    private val values = linkedMapOf<String, Any>()

    fun bind(value: Any, type: String): String {
        val name = "p${values.size}"
        values[name] = value
        return "{$name:$type}"
    }

    fun statement(sql: String): ClickHouseStatement = ClickHouseStatement(sql, values.toMap())
}

/** A finite double as SQL text; the query protocol already rejects NaN and the infinities. */
internal fun Double.sqlLiteral(): String {
    check(isFinite()) { "SQL constant must be finite." }
    return "toFloat64(${toBigDecimal().toPlainString()})"
}

/**
 * [value] ordered in [direction] the way the core orders values: a `NULL` first ascending and last descending, as
 * MongoDB, the canonical storage, does.
 */
internal fun ordered(value: String, direction: Sort.Direction): String =
    if (direction == Sort.Direction.ASC) "$value ASC NULLS FIRST" else "$value DESC NULLS LAST"

/**
 * A time zone the query protocol already validated as a `ZoneId`, as a quoted SQL string in the IANA name ClickHouse
 * loads: a region as is, a zero offset (`Z`) as `UTC`, and a whole-hour offset as `Etc/GMT∓h`, whose POSIX sign is
 * inverted. An offset of a fraction of an hour has no such name.
 */
internal fun String.sqlTimeZone(): String {
    val zone = ZoneId.of(this).normalized()
    val name = if (zone is ZoneOffset) zone.ianaName() else zone.id
    return "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/**
 * The earliest offset change that decides whether a zone's hours and minutes are native truncations
 * ([shiftsByWhole]): 1970. Almost every zone's switch from local mean time moved its offset by minutes and seconds, many
 * after 1900 (Asia/Shanghai's on 1901-01-01). Counting those changes would send nearly every zone to the longer
 * expression, so they are left out. The limit: HOUR and MINUTE buckets of instants before 1970, on the day a zone's
 * offset changed by less than the unit, may start where ClickHouse's truncation puts them, not where the bucket rule
 * does.
 */
private val WHOLE_SHIFTS_SINCE: Instant = Instant.EPOCH

/** Each zone's answer to [shiftsByWhole], by zone and unit: read from its rules once, not per query. */
private val wholeShifts = ConcurrentHashMap<Pair<String, Duration>, Boolean>()

/**
 * Whether every change of the offset of the zone this names, since [WHOLE_SHIFTS_SINCE], moves it by a whole number of
 * [unit]s. ClickHouse's `toStartOfHour` and `toStartOfMinute` truncate by the offset the day starts with. They start a
 * bucket where the bucket rule does (`DateBuckets`) only when an offset change within a day moves the wall clock by
 * whole units, as daylight saving's hour does. A zone whose offset moves by half an hour (Australia/Lord_Howe) needs the
 * longer expression ([ClickHouseAggregationCompiler]). A fixed offset never changes.
 */
internal fun String.shiftsByWhole(unit: Duration): Boolean {
    val zone = ZoneId.of(this).normalized()
    if (zone is ZoneOffset) return true
    return wholeShifts.computeIfAbsent(zone.id to unit) {
        val rules = zone.rules
        val changes = rules.transitions.filter { !it.instant.isBefore(WHOLE_SHIFTS_SINCE) }.map { it.duration } +
            rules.transitionRules.map { Duration.ofSeconds((it.offsetAfter.totalSeconds - it.offsetBefore.totalSeconds).toLong()) }
        changes.all { it.seconds % unit.seconds == 0L }
    }
}

private const val SECONDS_PER_HOUR = 3600
private const val MAX_ETC_EAST = 14
private const val MAX_ETC_WEST = 12

private fun ZoneOffset.ianaName(): String {
    val hours = totalSeconds / SECONDS_PER_HOUR
    val whole = totalSeconds % SECONDS_PER_HOUR == 0 && hours in -MAX_ETC_WEST..MAX_ETC_EAST
    if (!whole) throw QueryViolation.StorageUnsupported("the time zone offset $id").rejection()
    return when {
        hours == 0 -> "UTC"
        hours > 0 -> "Etc/GMT-$hours"
        else -> "Etc/GMT+${abs(hours)}"
    }
}
