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

package me.ahoo.wow.query.converter

import me.ahoo.wow.api.query.Condition
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

abstract class AbstractConditionConverter<T> : ConditionConverter<T> {

    private fun now(condition: Condition): OffsetDateTime {
        val zoneId = condition.zoneId() ?: ZoneId.systemDefault()
        return OffsetDateTime.now(zoneId)
    }

    private fun toDate(time: OffsetDateTime, datePattern: DateTimeFormatter?): Any {
        return if (datePattern != null) {
            time.format(datePattern)
        } else {
            time.toInstant().toEpochMilli()
        }
    }

    override fun today(condition: Condition): T {
        val now = now(condition)
        val startOfDay = now.with(LocalTime.MIN)
        val endOfDay = now.with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfDay,
            to = endOfDay,
            datePattern = condition.datePattern()
        )
    }

    override fun beforeToday(condition: Condition): T {
        val time = when (val conditionValue = condition.value) {
            is Number -> LocalTime.ofSecondOfDay(conditionValue.toLong())
            is String -> LocalTime.parse(conditionValue)
            is LocalTime -> conditionValue
            else -> {
                throw IllegalArgumentException("Unsupported condition value type:${conditionValue::class.java}")
            }
        }
        val now = now(condition).with(time)
        val ltDate = toDate(now, condition.datePattern())
        val ltCondition = Condition.lt(condition.field, ltDate)
        return lt(ltCondition)
    }

    override fun tomorrow(condition: Condition): T {
        val now = now(condition)
        val startOfTomorrow = now.plusDays(1).with(LocalTime.MIN)
        val endOfTomorrow = now.plusDays(1).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfTomorrow,
            to = endOfTomorrow,
            datePattern = condition.datePattern()
        )
    }

    override fun thisWeek(condition: Condition): T {
        val now = now(condition)
        val startOfWeek =
            now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN)
        val endOfWeek = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfWeek,
            to = endOfWeek,
            datePattern = condition.datePattern()
        )
    }

    override fun nextWeek(condition: Condition): T {
        val now = now(condition)
        val startOfNextWeek = now.plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfNextWeek = now.plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfNextWeek,
            to = endOfNextWeek,
            datePattern = condition.datePattern()
        )
    }

    override fun lastWeek(condition: Condition): T {
        val now = now(condition)
        val startOfLastWeek = now.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfLastWeek = now.minusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfLastWeek,
            to = endOfLastWeek,
            datePattern = condition.datePattern()
        )
    }

    override fun thisMonth(condition: Condition): T {
        val now = now(condition)
        val startOfMonth = now.withDayOfMonth(1).with(LocalTime.MIN)
        val endOfMonth = now.with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfMonth,
            to = endOfMonth,
            datePattern = condition.datePattern()
        )
    }

    override fun lastMonth(condition: Condition): T {
        val now = now(condition)
        val startOfLastMonth = now.minusMonths(1).withDayOfMonth(1).with(LocalTime.MIN)
        val endOfLastMonth = now.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfLastMonth,
            to = endOfLastMonth,
            datePattern = condition.datePattern()
        )
    }

    override fun recentDays(condition: Condition): T {
        val now = now(condition)
        val days = condition.value as Number
        val startOfRecentDays = now.minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val endOfRecentDays = now.with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfRecentDays,
            to = endOfRecentDays,
            datePattern = condition.datePattern()
        )
    }

    override fun earlierDays(condition: Condition): T {
        val now = now(condition)
        val days = condition.value as Number
        val endOfEarlierDays = now.minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val toDate: Any = toDate(endOfEarlierDays, condition.datePattern())
        val ltCondition = Condition.lt(condition.field, toDate)
        return lt(ltCondition)
    }

    fun timeRange(field: String, from: OffsetDateTime, to: OffsetDateTime, datePattern: DateTimeFormatter? = null): T {
        val fromDate: Any = toDate(from, datePattern)
        val toDate: Any = toDate(to, datePattern)
        val betweenCondition = Condition.between(field, fromDate, toDate)
        return between(betweenCondition)
    }
}
