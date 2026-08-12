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

package me.ahoo.wow.query.expression

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import java.math.BigDecimal
import java.math.BigInteger
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object RelativeTimeNormalizer {
    @Suppress("CyclomaticComplexMethod")
    fun lower(condition: Condition, frozenInstant: Instant, invocationZoneId: ZoneId): PortableExpression =
        try {
            if (condition.options.containsKey(Condition.DATE_PATTERN_OPTION_KEY)) {
                invalidQuery()
            }
            val zoneId = resolveZoneId(condition, invocationZoneId)
            val today = frozenInstant.atZone(zoneId).toLocalDate()
            when (condition.operator) {
                Operator.TODAY -> range(condition.field, today, today.plusDays(1), zoneId)
                Operator.BEFORE_TODAY -> predicate(
                    condition.field,
                    PortableOperator.LT,
                    today.atTime(parseLocalTime(condition.value)).atZone(zoneId).toInstant()
                )

                Operator.TOMORROW -> range(condition.field, today.plusDays(1), today.plusDays(2), zoneId)
                Operator.THIS_WEEK -> {
                    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    range(condition.field, start, start.plusWeeks(1), zoneId)
                }

                Operator.NEXT_WEEK -> {
                    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
                    range(condition.field, start, start.plusWeeks(1), zoneId)
                }

                Operator.LAST_WEEK -> {
                    val end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    range(condition.field, end.minusWeeks(1), end, zoneId)
                }

                Operator.THIS_MONTH -> {
                    val start = today.withDayOfMonth(1)
                    range(condition.field, start, start.plusMonths(1), zoneId)
                }

                Operator.LAST_MONTH -> {
                    val end = today.withDayOfMonth(1)
                    range(condition.field, end.minusMonths(1), end, zoneId)
                }

                Operator.RECENT_DAYS -> {
                    val days = positiveDays(condition.value)
                    range(condition.field, today.minusDays(Math.subtractExact(days, 1)), today.plusDays(1), zoneId)
                }

                Operator.EARLIER_DAYS -> {
                    val days = positiveDays(condition.value)
                    predicate(
                        condition.field,
                        PortableOperator.LT,
                        today.minusDays(Math.subtractExact(days, 1)).atStartOfDay(zoneId).toInstant()
                    )
                }

                else -> invalidQuery()
            }
        } catch (error: me.ahoo.wow.api.query.error.QueryException) {
            throw error
        } catch (_: RuntimeException) {
            invalidQuery()
        }

    private fun resolveZoneId(condition: Condition, invocationZoneId: ZoneId): ZoneId =
        when (val value = condition.options[Condition.ZONE_ID_OPTION_KEY]) {
            null -> invocationZoneId
            is ZoneId -> value
            is String -> ZoneId.of(value)
            else -> invalidQuery()
        }

    private fun parseLocalTime(value: Any): LocalTime =
        when (value) {
            is LocalTime -> value
            is String -> LocalTime.parse(value)
            is Number -> LocalTime.ofSecondOfDay(exactLong(value))
            else -> invalidQuery()
        }

    private fun positiveDays(value: Any): Long = exactLong(value as? Number ?: invalidQuery()).also {
        if (it <= 0) {
            invalidQuery()
        }
    }

    private fun exactLong(value: Number): Long =
        when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is BigInteger -> value.longValueExact()
            is BigDecimal -> value.longValueExact()
            is Float -> BigDecimal.valueOf(value.toDouble()).longValueExact()
            is Double -> BigDecimal.valueOf(value).longValueExact()
            else -> invalidQuery()
        }

    private fun range(field: String, start: LocalDate, end: LocalDate, zoneId: ZoneId): PortableExpression =
        ExpressionNormalizer.logical(
            LogicalOperator.AND,
            listOf(
                predicate(field, PortableOperator.GTE, start.atStartOfDay(zoneId).toInstant()),
                predicate(field, PortableOperator.LT, end.atStartOfDay(zoneId).toInstant())
            )
        ) as PortableExpression

    private fun predicate(field: String, operator: PortableOperator, value: Instant): PredicateExpression =
        PredicateExpression(LogicalField(field), operator, listOf(QueryValue.InstantValue(value)))
}
