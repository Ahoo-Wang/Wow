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
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

interface ConditionConverter<T> {
    @Suppress("CyclomaticComplexMethod")
    fun convert(condition: Condition): T {
        return when (condition.operator) {
            Operator.AND -> and(condition)
            Operator.OR -> or(condition)
            Operator.NOR -> nor(condition)
            Operator.ID -> id(condition)
            Operator.IDS -> ids(condition)
            Operator.TENANT_ID -> tenantId(condition)
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
            Operator.DELETED -> deleted(condition)
            Operator.TODAY -> today(condition)
            Operator.TOMORROW -> tomorrow(condition)
            Operator.THIS_WEEK -> thisWeek(condition)
            Operator.NEXT_WEEK -> nextWeek(condition)
            Operator.LAST_WEEK -> lastWeek(condition)
            Operator.THIS_MONTH -> thisMonth(condition)
            Operator.LAST_MONTH -> lastMonth(condition)
            Operator.RECENT_DAYS -> recentDays(condition)
            Operator.RAW -> raw(condition)
        }
    }

    fun and(condition: Condition): T
    fun or(condition: Condition): T
    fun nor(condition: Condition): T
    fun id(condition: Condition): T
    fun ids(condition: Condition): T
    fun tenantId(condition: Condition): T
    fun all(condition: Condition): T
    fun eq(condition: Condition): T
    fun ne(condition: Condition): T
    fun gt(condition: Condition): T
    fun lt(condition: Condition): T
    fun gte(condition: Condition): T
    fun lte(condition: Condition): T
    fun contains(condition: Condition): T
    fun isIn(condition: Condition): T
    fun notIn(condition: Condition): T
    fun between(condition: Condition): T
    fun allIn(condition: Condition): T
    fun startsWith(condition: Condition): T
    fun endsWith(condition: Condition): T
    fun elemMatch(condition: Condition): T
    fun isNull(condition: Condition): T
    fun notNull(condition: Condition): T
    fun isTrue(condition: Condition): T
    fun isFalse(condition: Condition): T
    fun deleted(condition: Condition): T
    fun today(condition: Condition): T {
        val startOfDay = LocalDateTime.now().with(LocalTime.MIN)
        val endOfDay = LocalDateTime.now().with(LocalTime.MAX)
        return timeRange(condition.field, startOfDay, endOfDay)
    }

    fun tomorrow(condition: Condition): T {
        val startOfTomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.MIN)
        val endOfTomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfTomorrow,
            endOfTomorrow
        )
    }

    fun thisWeek(condition: Condition): T {
        val startOfWeek =
            LocalDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN)
        val endOfWeek = LocalDateTime.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(condition.field, startOfWeek, endOfWeek)
    }

    fun nextWeek(condition: Condition): T {
        val startOfNextWeek = LocalDateTime.now().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfNextWeek =
            LocalDateTime.now().plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfNextWeek,
            endOfNextWeek
        )
    }

    fun lastWeek(condition: Condition): T {
        val startOfLastWeek = LocalDateTime.now().minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfLastWeek =
            LocalDateTime.now().minusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfLastWeek,
            endOfLastWeek
        )
    }

    fun thisMonth(condition: Condition): T {
        val startOfMonth = LocalDateTime.now().withDayOfMonth(1).with(LocalTime.MIN)
        val endOfMonth = LocalDateTime.now().with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(condition.field, startOfMonth, endOfMonth)
    }

    fun lastMonth(condition: Condition): T {
        val startOfLastMonth = LocalDateTime.now().minusMonths(1).withDayOfMonth(1).with(LocalTime.MIN)
        val endOfLastMonth =
            LocalDateTime.now().minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfLastMonth,
            endOfLastMonth
        )
    }

    fun recentDays(condition: Condition): T {
        val days = condition.value as Number
        val startOfRecentDays = LocalDateTime.now().minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val endOfRecentDays = LocalDateTime.now().with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfRecentDays,
            endOfRecentDays
        )
    }

    fun timeRange(field: String, from: LocalDateTime, to: LocalDateTime): T

    fun raw(condition: Condition): T
}
