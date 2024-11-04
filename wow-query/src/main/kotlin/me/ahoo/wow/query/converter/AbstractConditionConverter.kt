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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

abstract class AbstractConditionConverter<T> : ConditionConverter<T> {
    override fun today(condition: Condition): T {
        val startOfDay = LocalDateTime.now().with(LocalTime.MIN)
        val endOfDay = LocalDateTime.now().with(LocalTime.MAX)
        return timeRange(condition.field, startOfDay, endOfDay)
    }

    override fun tomorrow(condition: Condition): T {
        val startOfTomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.MIN)
        val endOfTomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfTomorrow,
            endOfTomorrow
        )
    }

    override fun thisWeek(condition: Condition): T {
        val startOfWeek =
            LocalDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN)
        val endOfWeek = LocalDateTime.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return timeRange(condition.field, startOfWeek, endOfWeek)
    }

    override fun nextWeek(condition: Condition): T {
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

    override fun lastWeek(condition: Condition): T {
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

    override fun thisMonth(condition: Condition): T {
        val startOfMonth = LocalDateTime.now().withDayOfMonth(1).with(LocalTime.MIN)
        val endOfMonth = LocalDateTime.now().with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(condition.field, startOfMonth, endOfMonth)
    }

    override fun lastMonth(condition: Condition): T {
        val startOfLastMonth = LocalDateTime.now().minusMonths(1).withDayOfMonth(1).with(LocalTime.MIN)
        val endOfLastMonth =
            LocalDateTime.now().minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfLastMonth,
            endOfLastMonth
        )
    }

    override fun recentDays(condition: Condition): T {
        val days = condition.value as Number
        val startOfRecentDays = LocalDateTime.now().minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val endOfRecentDays = LocalDateTime.now().with(LocalTime.MAX)
        return timeRange(
            condition.field,
            startOfRecentDays,
            endOfRecentDays
        )
    }

    abstract fun timeRange(field: String, from: LocalDateTime, to: LocalDateTime): T
}
