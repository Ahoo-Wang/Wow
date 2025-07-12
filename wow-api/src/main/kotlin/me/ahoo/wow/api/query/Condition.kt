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

package me.ahoo.wow.api.query

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface ICondition<C : ICondition<C>> {

    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val field: String

    @get:Schema(defaultValue = "ALL")
    val operator: Operator

    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val value: Any

    /**
     * When `operator` is `AND` or `OR` or `NOR`, `children` cannot be empty.
     */
    @get:Schema(defaultValue = "[]")
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val children: List<C>

    @get:Schema(defaultValue = "{}")
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val options: Map<String, Any>
}

data class Condition(
    override val field: String = EMPTY_VALUE,
    override val operator: Operator = Operator.ALL,
    override val value: Any = EMPTY_VALUE,
    /**
     * When `operator` is `AND` or `OR` or `NOR`, `children` cannot be empty.
     */
    override val children: List<Condition> = emptyList(),
    override val options: Map<String, Any> = emptyMap()
) : ICondition<Condition>, RewritableCondition<Condition> {
    fun <V> valueAs(): V {
        @Suppress("UNCHECKED_CAST")
        return value as V
    }

    override fun withCondition(newCondition: Condition): Condition {
        return newCondition
    }

    override fun appendCondition(append: Condition): Condition {
        if (this.operator == Operator.ALL) {
            return append
        }
        return and(this, append)
    }

    fun deletionState(): DeletionState {
        require(operator == Operator.DELETED) {
            "Operator must be DELETED, but was $operator."
        }
        if (value is DeletionState) {
            return value
        }
        if (value is Boolean) {
            return if (value) DeletionState.DELETED else DeletionState.ACTIVE
        }
        require(value is String) {
            "Value must be String, Boolean, or DeletionState, but was ${value::class.simpleName}."
        }
        return DeletionState.valueOf(value.uppercase())
    }

    fun ignoreCase(): Boolean? {
        return options[IGNORE_CASE_OPTION_KEY] as? Boolean
    }

    fun zoneId(): ZoneId? {
        val zoneIdOptionValue = options[ZONE_ID_OPTION_KEY] ?: return null
        return when (zoneIdOptionValue) {
            is String -> ZoneId.of(zoneIdOptionValue)
            is ZoneId -> zoneIdOptionValue
            else -> null
        }
    }

    fun datePattern(): DateTimeFormatter? {
        val datePatternOptionValue = options[DATE_PATTERN_OPTION_KEY] ?: return null
        return when (datePatternOptionValue) {
            is String -> DateTimeFormatter.ofPattern(datePatternOptionValue)
            is DateTimeFormatter -> datePatternOptionValue
            else -> null
        }
    }

    companion object {
        const val EMPTY_VALUE = ""
        val ALL = Condition(field = EMPTY_VALUE, operator = Operator.ALL, value = EMPTY_VALUE)
        val ACTIVE = deleted(DeletionState.ACTIVE)
        const val IGNORE_CASE_OPTION_KEY = "ignoreCase"
        const val ZONE_ID_OPTION_KEY = "zoneId"
        const val DATE_PATTERN_OPTION_KEY = "datePattern"
        private val IGNORE_CASE_OPTIONS = mapOf(IGNORE_CASE_OPTION_KEY to true)
        private val IGNORE_CASE_FALSE_OPTIONS = mapOf(IGNORE_CASE_OPTION_KEY to false)
        fun ignoreCaseOptions(value: Boolean) = if (value) IGNORE_CASE_OPTIONS else IGNORE_CASE_FALSE_OPTIONS
        fun datePatternOptions(value: Any?): Map<String, Any> {
            if (value == null) {
                return emptyMap()
            }
            require(value is String || value is DateTimeFormatter) {
                "datePatternOptions value must be String or DateTimeFormatter"
            }
            return mapOf(DATE_PATTERN_OPTION_KEY to value)
        }

        fun and(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.AND, children = conditions.toList())
        fun and(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.AND, children = conditions)
        fun or(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.OR, children = conditions.toList())
        fun or(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.OR, children = conditions)
        fun nor(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.NOR, children = conditions.toList())
        fun nor(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.NOR, children = conditions)
        fun all() = ALL
        fun active() = ACTIVE
        fun eq(field: String, value: Any) = Condition(field, Operator.EQ, value)
        fun ne(field: String, value: Any) = Condition(field, Operator.NE, value)
        fun gt(field: String, value: Any) = Condition(field, Operator.GT, value)
        fun lt(field: String, value: Any) = Condition(field, Operator.LT, value)
        fun gte(field: String, value: Any) = Condition(field, Operator.GTE, value)
        fun lte(field: String, value: Any) = Condition(field, Operator.LTE, value)
        fun contains(field: String, value: String, ignoreCase: Boolean = false) =
            Condition(field, Operator.CONTAINS, value, options = ignoreCaseOptions(ignoreCase))

        fun startsWith(field: String, value: String, ignoreCase: Boolean = false) =
            Condition(field, Operator.STARTS_WITH, value, options = ignoreCaseOptions(ignoreCase))

        fun endsWith(field: String, value: String, ignoreCase: Boolean = false) =
            Condition(field, Operator.ENDS_WITH, value, options = ignoreCaseOptions(ignoreCase))

        fun isIn(field: String, value: List<Any>) = Condition(field, Operator.IN, value)
        fun notIn(field: String, value: List<Any>) = Condition(field, Operator.NOT_IN, value)
        fun <V> between(field: String, start: V, end: V) = Condition(field, Operator.BETWEEN, listOf(start, end))
        fun all(field: String, value: List<Any>) = Condition(field, Operator.ALL_IN, value)
        fun elemMatch(field: String, value: Condition) = Condition(field, Operator.ELEM_MATCH, children = listOf(value))
        fun isNull(field: String) = Condition(field, Operator.NULL)
        fun notNull(field: String) = Condition(field, Operator.NOT_NULL)
        fun isTrue(field: String) = Condition(field, Operator.TRUE)
        fun isFalse(field: String) = Condition(field, Operator.FALSE)
        fun exists(field: String, exists: Boolean = true) = Condition(field, Operator.EXISTS, exists)
        fun id(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.ID, value = value)
        fun ids(value: List<String>) = Condition(field = EMPTY_VALUE, operator = Operator.IDS, value = value)
        fun ids(vararg value: String) = ids(value.asList())
        fun aggregateId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.AGGREGATE_ID, value = value)
        fun aggregateIds(value: List<String>) =
            Condition(field = EMPTY_VALUE, operator = Operator.AGGREGATE_IDS, value = value)

        fun aggregateIds(vararg value: String) = aggregateIds(value.asList())
        fun tenantId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.TENANT_ID, value = value)
        fun ownerId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.OWNER_ID, value = value)
        fun deleted(value: Boolean): Condition {
            val deletionState = if (value) {
                DeletionState.DELETED
            } else {
                DeletionState.ACTIVE
            }
            return deleted(deletionState)
        }

        fun deleted(value: DeletionState) = Condition(field = EMPTY_VALUE, operator = Operator.DELETED, value = value)
        fun today(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.TODAY, options = datePatternOptions(datePattern))

        fun beforeToday(field: String, time: Any, datePattern: Any? = null) =
            Condition(
                field = field,
                operator = Operator.BEFORE_TODAY,
                value = time,
                options = datePatternOptions(datePattern)
            )

        fun tomorrow(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.TOMORROW, options = datePatternOptions(datePattern))

        fun thisWeek(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.THIS_WEEK, options = datePatternOptions(datePattern))

        fun nextWeek(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.NEXT_WEEK, options = datePatternOptions(datePattern))

        fun lastWeek(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.LAST_WEEK, options = datePatternOptions(datePattern))

        fun thisMonth(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.THIS_MONTH, options = datePatternOptions(datePattern))

        fun lastMonth(field: String, datePattern: Any? = null) =
            Condition(field = field, operator = Operator.LAST_MONTH, options = datePatternOptions(datePattern))

        fun recentDays(field: String, days: Int, datePattern: Any? = null) =
            Condition(
                field = field,
                operator = Operator.RECENT_DAYS,
                value = days,
                options = datePatternOptions(datePattern)
            )

        fun earlierDays(field: String, days: Int, datePattern: Any? = null) =
            Condition(
                field = field,
                operator = Operator.EARLIER_DAYS,
                value = days,
                options = datePatternOptions(datePattern)
            )

        fun raw(value: Any) = Condition(field = EMPTY_VALUE, operator = Operator.RAW, value = value)
    }
}
