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
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.RelativeTimeOperation
import java.math.BigDecimal
import java.math.BigInteger
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object RelativeTimeNormalizer {
    fun lower(condition: Condition, frozenInstant: Instant, invocationZoneId: ZoneId): PortableExpression =
        RelativeTimeExpressionNormalizer.lower(
            RelativeTimeExpressionNormalizer.defer(condition),
            frozenInstant,
            invocationZoneId
        )
}

internal object RelativeTimeExpressionNormalizer {
    @JvmSynthetic
    fun defer(condition: Condition): RelativeTimeExpression = try {
        if (condition.options.containsKey(Condition.DATE_PATTERN_OPTION_KEY)) {
            invalidQuery()
        }
        val operation = condition.operator.toRelativeOperation()
        val operands = when (operation) {
            RelativeTimeOperation.BEFORE_TODAY -> listOf(
                QueryValue.IntegerValue(parseLocalTime(condition.value).toSecondOfDay().toLong())
            )

            RelativeTimeOperation.RECENT_DAYS,
            RelativeTimeOperation.EARLIER_DAYS -> listOf(QueryValue.IntegerValue(positiveDays(condition.value)))

            RelativeTimeOperation.TODAY,
            RelativeTimeOperation.TOMORROW,
            RelativeTimeOperation.THIS_WEEK,
            RelativeTimeOperation.NEXT_WEEK,
            RelativeTimeOperation.LAST_WEEK,
            RelativeTimeOperation.THIS_MONTH,
            RelativeTimeOperation.LAST_MONTH -> emptyList()
        }
        RelativeTimeExpression(
            condition.field,
            operation,
            operands,
            resolveZoneId(condition)?.id
        )
    } catch (error: me.ahoo.wow.api.query.error.QueryException) {
        throw error
    } catch (_: RuntimeException) {
        invalidQuery()
    }

    @Suppress("CyclomaticComplexMethod")
    @JvmSynthetic
    fun lower(
        expression: RelativeTimeExpression,
        frozenInstant: Instant,
        invocationZoneId: ZoneId
    ): PortableExpression =
        try {
            validate(expression)
            val zoneId = expression.zoneId?.let(ZoneId::of) ?: invocationZoneId
            val today = frozenInstant.atZone(zoneId).toLocalDate()
            when (expression.operation) {
                RelativeTimeOperation.TODAY -> range(expression.field, today, today.plusDays(1), zoneId)
                RelativeTimeOperation.BEFORE_TODAY -> predicate(
                    expression.field,
                    PortableOperator.LT,
                    today.atTime(operandSeconds(expression)).atZone(zoneId).toInstant()
                )

                RelativeTimeOperation.TOMORROW ->
                    range(expression.field, today.plusDays(1), today.plusDays(2), zoneId)

                RelativeTimeOperation.THIS_WEEK -> {
                    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    range(expression.field, start, start.plusWeeks(1), zoneId)
                }

                RelativeTimeOperation.NEXT_WEEK -> {
                    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
                    range(expression.field, start, start.plusWeeks(1), zoneId)
                }

                RelativeTimeOperation.LAST_WEEK -> {
                    val end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    range(expression.field, end.minusWeeks(1), end, zoneId)
                }

                RelativeTimeOperation.THIS_MONTH -> {
                    val start = today.withDayOfMonth(1)
                    range(expression.field, start, start.plusMonths(1), zoneId)
                }

                RelativeTimeOperation.LAST_MONTH -> {
                    val end = today.withDayOfMonth(1)
                    range(expression.field, end.minusMonths(1), end, zoneId)
                }

                RelativeTimeOperation.RECENT_DAYS -> {
                    val days = operandDays(expression)
                    range(
                        expression.field,
                        today.minusDays(Math.subtractExact(days, 1)),
                        today.plusDays(1),
                        zoneId
                    )
                }

                RelativeTimeOperation.EARLIER_DAYS -> {
                    val days = operandDays(expression)
                    predicate(
                        expression.field,
                        PortableOperator.LT,
                        today.minusDays(Math.subtractExact(days, 1)).atStartOfDay(zoneId).toInstant()
                    )
                }
            }
        } catch (error: me.ahoo.wow.api.query.error.QueryException) {
            throw error
        } catch (_: RuntimeException) {
            invalidQuery()
        }

    @JvmSynthetic
    fun validate(expression: RelativeTimeExpression) {
        expression.zoneId?.let(ZoneId::of)
        when (expression.operation) {
            RelativeTimeOperation.BEFORE_TODAY -> operandSeconds(expression)
            RelativeTimeOperation.RECENT_DAYS,
            RelativeTimeOperation.EARLIER_DAYS -> operandDays(expression)

            RelativeTimeOperation.TODAY,
            RelativeTimeOperation.TOMORROW,
            RelativeTimeOperation.THIS_WEEK,
            RelativeTimeOperation.NEXT_WEEK,
            RelativeTimeOperation.LAST_WEEK,
            RelativeTimeOperation.THIS_MONTH,
            RelativeTimeOperation.LAST_MONTH -> if (expression.operands.isNotEmpty()) invalidQuery()
        }
    }

    private fun resolveZoneId(condition: Condition): ZoneId? =
        when (val value = condition.options[Condition.ZONE_ID_OPTION_KEY]) {
            null -> null
            is ZoneId -> value
            is String -> ZoneId.of(value)
            else -> invalidQuery()
        }

    private fun Operator.toRelativeOperation(): RelativeTimeOperation = when (this) {
        Operator.TODAY -> RelativeTimeOperation.TODAY
        Operator.BEFORE_TODAY -> RelativeTimeOperation.BEFORE_TODAY
        Operator.TOMORROW -> RelativeTimeOperation.TOMORROW
        Operator.THIS_WEEK -> RelativeTimeOperation.THIS_WEEK
        Operator.NEXT_WEEK -> RelativeTimeOperation.NEXT_WEEK
        Operator.LAST_WEEK -> RelativeTimeOperation.LAST_WEEK
        Operator.THIS_MONTH -> RelativeTimeOperation.THIS_MONTH
        Operator.LAST_MONTH -> RelativeTimeOperation.LAST_MONTH
        Operator.RECENT_DAYS -> RelativeTimeOperation.RECENT_DAYS
        Operator.EARLIER_DAYS -> RelativeTimeOperation.EARLIER_DAYS
        else -> invalidQuery()
    }

    private fun operandSeconds(expression: RelativeTimeExpression): LocalTime {
        val seconds = (expression.operands.singleOrNull() as? QueryValue.IntegerValue)?.value ?: invalidQuery()
        return LocalTime.ofSecondOfDay(seconds)
    }

    private fun operandDays(expression: RelativeTimeExpression): Long {
        val days = (expression.operands.singleOrNull() as? QueryValue.IntegerValue)?.value ?: invalidQuery()
        if (days <= 0) invalidQuery()
        return days
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
