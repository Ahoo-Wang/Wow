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
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.converter.DeleteConditionGuard.guard
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

abstract class AbstractConditionConverter<T> : ConditionConverter<T> {
    override fun convert(condition: Condition): T {
        val convertedCondition = condition.guard()
        return internalConvert(convertedCondition)
    }

    @Suppress("CyclomaticComplexMethod")
    protected fun internalConvert(condition: Condition): T {
        return when (condition.operator) {
            Operator.AND -> and(condition)
            Operator.OR -> or(condition)
            Operator.NOR -> nor(condition)
            Operator.ID -> id(condition)
            Operator.IDS -> ids(condition)
            Operator.AGGREGATE_ID -> aggregateId(condition)
            Operator.AGGREGATE_IDS -> aggregateIds(condition)
            Operator.TENANT_ID -> tenantId(condition)
            Operator.OWNER_ID -> ownerId(condition)
            Operator.SPACE_ID -> spaceId(condition)
            Operator.ALL -> all(condition)
            Operator.EQ -> eq(condition)
            Operator.NE -> ne(condition)
            Operator.GT -> gt(condition)
            Operator.LT -> lt(condition)
            Operator.GTE -> gte(condition)
            Operator.LTE -> lte(condition)
            Operator.CONTAINS -> contains(condition)
            Operator.IN -> isIn(condition)
            Operator.NOT_IN -> notIn(condition)
            Operator.BETWEEN -> between(condition)
            Operator.ALL_IN -> allIn(condition)
            Operator.STARTS_WITH -> startsWith(condition)
            Operator.ENDS_WITH -> endsWith(condition)
            Operator.ELEM_MATCH -> elemMatch(condition)
            Operator.NULL -> isNull(condition)
            Operator.NOT_NULL -> notNull(condition)
            Operator.TRUE -> isTrue(condition)
            Operator.FALSE -> isFalse(condition)
            Operator.EXISTS -> exists(condition)
            Operator.DELETED -> deleted(condition)
            Operator.TODAY -> today(condition)
            Operator.BEFORE_TODAY -> beforeToday(condition)
            Operator.TOMORROW -> tomorrow(condition)
            Operator.THIS_WEEK -> thisWeek(condition)
            Operator.NEXT_WEEK -> nextWeek(condition)
            Operator.LAST_WEEK -> lastWeek(condition)
            Operator.THIS_MONTH -> thisMonth(condition)
            Operator.LAST_MONTH -> lastMonth(condition)
            Operator.RECENT_DAYS -> recentDays(condition)
            Operator.EARLIER_DAYS -> earlierDays(condition)
            Operator.RAW -> raw(condition)
        }
    }

    abstract fun and(condition: Condition): T
    abstract fun or(condition: Condition): T
    abstract fun nor(condition: Condition): T
    abstract fun id(condition: Condition): T
    abstract fun aggregateId(condition: Condition): T
    abstract fun aggregateIds(condition: Condition): T
    abstract fun ids(condition: Condition): T
    abstract fun tenantId(condition: Condition): T
    abstract fun ownerId(condition: Condition): T
    abstract fun spaceId(condition: Condition): T
    abstract fun all(condition: Condition): T
    abstract fun eq(condition: Condition): T
    abstract fun ne(condition: Condition): T
    abstract fun gt(condition: Condition): T
    abstract fun lt(condition: Condition): T
    abstract fun gte(condition: Condition): T
    abstract fun lte(condition: Condition): T
    abstract fun contains(condition: Condition): T
    abstract fun isIn(condition: Condition): T
    abstract fun notIn(condition: Condition): T
    abstract fun between(condition: Condition): T
    abstract fun allIn(condition: Condition): T
    abstract fun startsWith(condition: Condition): T
    abstract fun endsWith(condition: Condition): T
    abstract fun elemMatch(condition: Condition): T
    abstract fun isNull(condition: Condition): T
    abstract fun notNull(condition: Condition): T
    abstract fun isTrue(condition: Condition): T
    abstract fun isFalse(condition: Condition): T
    abstract fun exists(condition: Condition): T
    abstract fun deleted(condition: Condition): T
    abstract fun raw(condition: Condition): T

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

    protected fun today(condition: Condition): T {
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

    protected fun beforeToday(condition: Condition): T {
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

    protected fun tomorrow(condition: Condition): T {
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

    protected fun thisWeek(condition: Condition): T {
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

    protected fun nextWeek(condition: Condition): T {
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

    protected fun lastWeek(condition: Condition): T {
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

    protected fun thisMonth(condition: Condition): T {
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

    protected fun lastMonth(condition: Condition): T {
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

    protected fun recentDays(condition: Condition): T {
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

    protected fun earlierDays(condition: Condition): T {
        val now = now(condition)
        val days = condition.value as Number
        val endOfEarlierDays = now.minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val toDate: Any = toDate(endOfEarlierDays, condition.datePattern())
        val ltCondition = Condition.lt(condition.field, toDate)
        return lt(ltCondition)
    }

    protected fun timeRange(
        field: String,
        from: OffsetDateTime,
        to: OffsetDateTime,
        datePattern: DateTimeFormatter? = null
    ): T {
        val fromDate: Any = toDate(from, datePattern)
        val toDate: Any = toDate(to, datePattern)
        val betweenCondition = Condition.between(field, fromDate, toDate)
        return between(betweenCondition)
    }
}
