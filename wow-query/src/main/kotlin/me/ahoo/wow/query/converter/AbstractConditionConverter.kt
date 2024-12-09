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
import java.time.temporal.TemporalAdjusters

abstract class AbstractConditionConverter<T> : ConditionConverter<T> {

    private fun now(condition: Condition): OffsetDateTime {
        val zoneId = condition.zoneId() ?: ZoneId.systemDefault()
        return OffsetDateTime.now(zoneId)
    }

    override fun today(condition: Condition): T {
        val now = now(condition)
        val startOfDay = now.with(LocalTime.MIN)
        val endOfDay = now.with(LocalTime.MAX)
        return timeRange(condition.field, startOfDay, endOfDay)
    }

    override fun beforeToday(condition: Condition): T {
        val ltDateTime = now(condition).with(condition.valueAs()).toInstant().toEpochMilli()
        val ltCondition = Condition.lt(condition.field, ltDateTime)
        return lt(ltCondition)
    }

    override fun tomorrow(condition: Condition): T {
        val now = now(condition)
        val startOfTomorrow = now.plusDays(1).with(LocalTime.MIN)
        val endOfTomorrow = now.plusDays(1).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfTomorrow,
            endOfTomorrow
        )
    }

    override fun thisWeek(condition: Condition): T {
        val now = now(condition)
        val startOfWeek =
            now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN)
        val endOfWeek = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(condition.field, startOfWeek, endOfWeek)
    }

    override fun nextWeek(condition: Condition): T {
        val now = now(condition)
        val startOfNextWeek = now.plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfNextWeek = now.plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfNextWeek,
            endOfNextWeek
        )
    }

    override fun lastWeek(condition: Condition): T {
        val now = now(condition)
        val startOfLastWeek = now.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfLastWeek = now.minusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfLastWeek,
            endOfLastWeek
        )
    }

    override fun thisMonth(condition: Condition): T {
        val now = now(condition)
        val startOfMonth = now.withDayOfMonth(1).with(LocalTime.MIN)
        val endOfMonth = now.with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(condition.field, startOfMonth, endOfMonth)
    }

    override fun lastMonth(condition: Condition): T {
        val now = now(condition)
        val startOfLastMonth = now.minusMonths(1).withDayOfMonth(1).with(LocalTime.MIN)
        val endOfLastMonth = now.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(
            field = condition.field,
            from = startOfLastMonth,
            to = endOfLastMonth
        )
    }

    override fun recentDays(condition: Condition): T {
        val now = now(condition)
        val days = condition.value as Number
        val startOfRecentDays = now.minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val endOfRecentDays = now.with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfRecentDays,
            endOfRecentDays
        )
    }

    fun timeRange(field: String, from: OffsetDateTime, to: OffsetDateTime): T {
        val fromEpoch = from.toInstant().toEpochMilli()
        val toEpoch = to.toInstant().toEpochMilli()
        val betweenCondition = Condition.between(field, fromEpoch, toEpoch)
        return between(betweenCondition)
    }
}
