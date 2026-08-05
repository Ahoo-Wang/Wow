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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object ConditionValidator {
    fun validate(condition: Condition) {
        validateNode(condition)
        condition.children.forEach(::validate)
    }

    fun validateNode(condition: Condition) {
        validateValue(condition)
        validateOptions(condition)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun validateValue(condition: Condition) {
        when (condition.operator) {
            Operator.AND,
            Operator.OR,
            Operator.NOR ->
                require(condition.children.isNotEmpty()) {
                    "${condition.operator} operator requires at least one child condition."
                }

            Operator.ID,
            Operator.AGGREGATE_ID,
            Operator.TENANT_ID,
            Operator.OWNER_ID,
            Operator.SPACE_ID -> requireString(condition)

            Operator.IDS,
            Operator.AGGREGATE_IDS -> requireStringIterable(condition)

            Operator.CONTAINS,
            Operator.STARTS_WITH,
            Operator.ENDS_WITH,
            Operator.MATCH -> requireString(condition)

            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN ->
                require(condition.value is Iterable<*>) {
                    "${condition.operator} operator requires value to be an Iterable."
                }

            Operator.BETWEEN -> {
                val values = (condition.value as? Iterable<*>)?.toList()
                require(values?.size == 2) {
                    "BETWEEN operator requires value to be an Iterable with exactly 2 elements."
                }
            }

            Operator.ELEM_MATCH ->
                require(condition.children.size == 1) {
                    "ELEM_MATCH operator requires exactly one child condition."
                }

            Operator.EXISTS ->
                require(condition.value is Boolean) {
                    "EXISTS operator requires value to be a Boolean."
                }

            Operator.DELETED -> validateDeletionState(condition)

            Operator.BEFORE_TODAY -> validateBeforeToday(condition)

            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS -> validatePositiveWholeNumber(condition)

            Operator.ALL,
            Operator.EQ,
            Operator.NE,
            Operator.GT,
            Operator.LT,
            Operator.GTE,
            Operator.LTE,
            Operator.NULL,
            Operator.NOT_NULL,
            Operator.TRUE,
            Operator.FALSE,
            Operator.TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            Operator.RAW -> Unit
        }
    }

    private fun requireString(condition: Condition) {
        require(condition.value is String) {
            "${condition.operator} operator requires value to be a String."
        }
    }

    private fun requireStringIterable(condition: Condition) {
        val values = condition.value as? Iterable<*>
        require(values != null && values.all { it is String }) {
            "${condition.operator} operator requires value to be an Iterable of String values."
        }
    }

    private fun validateDeletionState(condition: Condition) {
        try {
            condition.deletionState()
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "DELETED operator requires value to be a Boolean, String, or DeletionState.",
                error,
            )
        }
    }

    private fun validateBeforeToday(condition: Condition) {
        val valid =
            when (val value = condition.value) {
                is Number -> value.toLong() in 0..86399
                is String -> runCatching { LocalTime.parse(value) }.isSuccess
                is LocalTime -> true
                else -> false
            }
        require(valid) {
            "BEFORE_TODAY operator requires value to be a valid second-of-day Number, ISO LocalTime String, or LocalTime."
        }
    }

    private fun validatePositiveWholeNumber(condition: Condition) {
        val number = condition.value as? Number
        val value = number?.toDouble()
        require(value != null && value.isFinite() && value > 0 && value % 1.0 == 0.0) {
            "${condition.operator} operator requires value to be a positive whole number."
        }
    }

    private fun validateOptions(condition: Condition) {
        condition.options[Condition.IGNORE_CASE_OPTION_KEY]?.let { ignoreCase ->
            require(ignoreCase is Boolean) {
                "${condition.operator} operator requires option '${Condition.IGNORE_CASE_OPTION_KEY}' to be a Boolean."
            }
        }
        condition.options[Condition.ZONE_ID_OPTION_KEY]?.let { zoneId ->
            require(zoneId is String || zoneId is ZoneId) {
                "${condition.operator} operator requires option '${Condition.ZONE_ID_OPTION_KEY}' to be a String or ZoneId."
            }
        }
        condition.options[Condition.DATE_PATTERN_OPTION_KEY]?.let { datePattern ->
            require(datePattern is String || datePattern is DateTimeFormatter) {
                "${condition.operator} operator requires option '${Condition.DATE_PATTERN_OPTION_KEY}' to be a String or DateTimeFormatter."
            }
        }
    }
}
