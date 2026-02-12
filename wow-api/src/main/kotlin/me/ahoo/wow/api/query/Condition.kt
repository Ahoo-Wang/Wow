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
import me.ahoo.wow.api.query.Condition.Companion.exists
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Marker interface for condition options.
 * Implementations can define additional configuration options for query conditions.
 */
interface ConditionOptions

/**
 * Represents a generic condition interface for building query criteria.
 *
 * This interface defines the structure for conditions that can be used in database queries,
 * supporting various operators and nested conditions for complex filtering logic.
 *
 * @param C The type of the condition implementation, enabling self-referential types for nested conditions.
 */
interface ICondition<C : ICondition<C>> {
    /**
     * The field name to apply the condition to.
     * This can be empty for certain operators like AND, OR, NOR that operate on child conditions.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val field: String

    /**
     * The operator to apply for this condition.
     * Defines how the field value should be compared against the provided value.
     */
    @get:Schema(defaultValue = "ALL")
    val operator: Operator

    /**
     * The value to compare against when using comparison operators.
     * The type and interpretation depend on the operator being used.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val value: Any

    /**
     * Child conditions for logical operators (AND, OR, NOR).
     * When using logical operators, this list cannot be empty and contains the nested conditions to combine.
     */
    @get:Schema(defaultValue = "[]")
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val children: List<C>

    /**
     * Additional options for customizing condition behavior.
     * Can include settings like case sensitivity, date patterns, timezone information, etc.
     */
    @get:Schema(defaultValue = "{}", implementation = ConditionOptions::class)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val options: Map<String, Any>
}

/**
 * Represents a concrete query condition with support for various comparison operators and logical combinations.
 *
 * This data class implements the [ICondition] interface and provides factory methods for creating
 * common condition types. It supports both simple field comparisons and complex nested conditions
 * using logical operators.
 *
 * @property field The field name to apply the condition to. Defaults to empty string for logical operators.
 * @property operator The comparison or logical operator to use. Defaults to [Operator.ALL].
 * @property value The value to compare against. Defaults to empty string.
 * @property children List of child conditions for logical operators (AND, OR, NOR). Must not be empty when using logical operators.
 * @property options Additional configuration options like case sensitivity, date patterns, etc.
 *
 * @sample
 * ```
 * // Simple equality condition
 * val nameCondition = Condition.eq("name", "John")
 *
 * // Logical AND condition
 * val complexCondition = Condition.and(
 *     Condition.eq("age", 25),
 *     Condition.gt("score", 80)
 * )
 *
 * // Case-insensitive string matching
 * val searchCondition = Condition.contains("description", "kotlin", ignoreCase = true)
 * ```
 */
data class Condition(
    override val field: String = EMPTY_VALUE,
    override val operator: Operator = Operator.ALL,
    override val value: Any = EMPTY_VALUE,
    /**
     * When `operator` is `AND` or `OR` or `NOR`, `children` cannot be empty.
     */
    override val children: List<Condition> = emptyList(),
    override val options: Map<String, Any> = emptyMap()
) : ICondition<Condition>,
    RewritableCondition<Condition> {
    /**
     * Casts the condition value to the specified type.
     *
     * This method provides type-safe access to the condition value by performing an unchecked cast.
     * Use with caution and ensure the value is actually of the expected type.
     *
     * @param V The target type to cast the value to.
     * @return The condition value cast to type V.
     * @throws ClassCastException if the value cannot be cast to the specified type.
     */
    fun <V> valueAs(): V {
        @Suppress("UNCHECKED_CAST")
        return value as V
    }

    /**
     * Creates a new condition with the specified condition.
     *
     * This method implements the [RewritableCondition] interface by returning the new condition directly,
     * as Condition is immutable and doesn't need to preserve existing state.
     *
     * @param newCondition The new condition to use.
     * @return The new condition.
     */
    override fun withCondition(newCondition: Condition): Condition = newCondition

    /**
     * Appends a condition to this condition using logical AND.
     *
     * If this condition is the ALL condition (matches everything), returns the appended condition.
     * Otherwise, creates a new AND condition combining this condition with the appended one.
     *
     * @param append The condition to append.
     * @return A new condition combining this and the appended condition with AND logic.
     */
    override fun appendCondition(append: Condition): Condition {
        if (this.operator == Operator.ALL) {
            return append
        }
        return and(this, append)
    }

    /**
     * Extracts the deletion state from this condition.
     *
     * This method is specifically for conditions using the [Operator.DELETED] operator.
     * It converts the condition value to a [DeletionState] enum, supporting various input types.
     *
     * @return The deletion state represented by this condition.
     * @throws IllegalArgumentException if the operator is not DELETED or if the value cannot be converted to a DeletionState.
     */
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

    /**
     * Gets the case sensitivity option for string operations.
     *
     * This option is used by operators like CONTAINS, STARTS_WITH, and ENDS_WITH to determine
     * whether string comparisons should be case-sensitive or case-insensitive.
     *
     * @return true if case-insensitive matching should be used, false for case-sensitive, null if not specified.
     */
    fun ignoreCase(): Boolean? = options[IGNORE_CASE_OPTION_KEY] as? Boolean

    /**
     * Gets the timezone option for date/time operations.
     *
     * This option specifies the timezone to use when interpreting date/time values in conditions.
     * If not specified, the system default timezone is used.
     *
     * @return The ZoneId for timezone operations, or null if not specified.
     */
    fun zoneId(): ZoneId? {
        val zoneIdOptionValue = options[ZONE_ID_OPTION_KEY] ?: return null
        return when (zoneIdOptionValue) {
            is String -> ZoneId.of(zoneIdOptionValue)
            is ZoneId -> zoneIdOptionValue
            else -> null
        }
    }

    /**
     * Gets the date pattern formatter for date parsing operations.
     *
     * This option specifies the pattern to use when parsing date strings in conditions.
     * If not specified, default date parsing behavior is used.
     *
     * @return The DateTimeFormatter for date pattern operations, or null if not specified.
     */
    fun datePattern(): DateTimeFormatter? {
        val datePatternOptionValue = options[DATE_PATTERN_OPTION_KEY] ?: return null
        return when (datePatternOptionValue) {
            is String -> DateTimeFormatter.ofPattern(datePatternOptionValue)
            is DateTimeFormatter -> datePatternOptionValue
            else -> null
        }
    }

    companion object {
        /**
         * The default empty value used for conditions that don't require a specific value.
         */
        const val EMPTY_VALUE = ""

        /**
         * A condition that matches all documents (no filtering).
         * This is the default condition when no specific filtering is needed.
         */
        val ALL = Condition(field = EMPTY_VALUE, operator = Operator.ALL, value = EMPTY_VALUE)

        /**
         * A condition that matches only active (non-deleted) documents.
         */
        val ACTIVE = deleted(DeletionState.ACTIVE)

        /**
         * The key used in options map to specify case sensitivity for string operations.
         */
        const val IGNORE_CASE_OPTION_KEY = "ignoreCase"

        /**
         * The key used in options map to specify timezone for date operations.
         */
        const val ZONE_ID_OPTION_KEY = "zoneId"

        /**
         * The key used in options map to specify date pattern for date parsing.
         */
        const val DATE_PATTERN_OPTION_KEY = "datePattern"
        private val IGNORE_CASE_OPTIONS = mapOf(IGNORE_CASE_OPTION_KEY to true)
        private val IGNORE_CASE_FALSE_OPTIONS = mapOf(IGNORE_CASE_OPTION_KEY to false)

        /**
         * Creates options map for case sensitivity configuration.
         *
         * @param value true for case-insensitive operations, false for case-sensitive.
         * @return A map containing the ignoreCase option.
         */
        fun ignoreCaseOptions(value: Boolean) = if (value) IGNORE_CASE_OPTIONS else IGNORE_CASE_FALSE_OPTIONS

        /**
         * Creates options map for date pattern configuration.
         *
         * @param value The date pattern as a String or DateTimeFormatter, or null for no pattern.
         * @return A map containing the datePattern option, or empty map if value is null.
         * @throws IllegalArgumentException if value is not String or DateTimeFormatter.
         */
        fun datePatternOptions(value: Any?): Map<String, Any> {
            if (value == null) {
                return emptyMap()
            }
            require(value is String || value is DateTimeFormatter) {
                "datePatternOptions value must be String or DateTimeFormatter"
            }
            return mapOf(DATE_PATTERN_OPTION_KEY to value)
        }

        /**
         * Creates an AND condition combining multiple conditions.
         * All specified conditions must be true for the combined condition to be true.
         *
         * @param conditions The conditions to combine with AND logic.
         * @return A new AND condition.
         */
        fun and(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.AND, children = conditions.toList())

        /**
         * Creates an AND condition combining a list of conditions.
         * All specified conditions must be true for the combined condition to be true.
         *
         * @param conditions The list of conditions to combine with AND logic.
         * @return A new AND condition.
         */
        fun and(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.AND, children = conditions)

        /**
         * Creates an OR condition combining multiple conditions.
         * At least one of the specified conditions must be true for the combined condition to be true.
         *
         * @param conditions The conditions to combine with OR logic.
         * @return A new OR condition.
         */
        fun or(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.OR, children = conditions.toList())

        /**
         * Creates an OR condition combining a list of conditions.
         * At least one of the specified conditions must be true for the combined condition to be true.
         *
         * @param conditions The list of conditions to combine with OR logic.
         * @return A new OR condition.
         */
        fun or(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.OR, children = conditions)

        /**
         * Creates a NOR condition combining multiple conditions.
         * None of the specified conditions must be true for the combined condition to be true.
         *
         * @param conditions The conditions to combine with NOR logic.
         * @return A new NOR condition.
         */
        fun nor(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.NOR, children = conditions.toList())

        /**
         * Creates a NOR condition combining a list of conditions.
         * None of the specified conditions must be true for the combined condition to be true.
         *
         * @param conditions The list of conditions to combine with NOR logic.
         * @return A new NOR condition.
         */
        fun nor(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.NOR, children = conditions)

        /**
         * Returns the ALL condition that matches everything.
         *
         * @return The ALL condition.
         */
        fun all() = ALL

        /**
         * Returns the ACTIVE condition that matches only non-deleted documents.
         *
         * @return The ACTIVE condition.
         */
        fun active() = ACTIVE

        /**
         * Creates an equality condition.
         * Matches documents where the specified field equals the given value.
         *
         * @param field The field name to check.
         * @param value The value to compare against.
         * @return A new equality condition.
         */
        fun eq(
            field: String,
            value: Any
        ) = Condition(field, Operator.EQ, value)

        /**
         * Creates a not-equal condition.
         * Matches documents where the specified field does not equal the given value.
         *
         * @param field The field name to check.
         * @param value The value to compare against.
         * @return A new not-equal condition.
         */
        fun ne(
            field: String,
            value: Any
        ) = Condition(field, Operator.NE, value)

        /**
         * Creates a greater-than condition.
         * Matches documents where the specified field is greater than the given value.
         *
         * @param field The field name to check.
         * @param value The value to compare against.
         * @return A new greater-than condition.
         */
        fun gt(
            field: String,
            value: Any
        ) = Condition(field, Operator.GT, value)

        /**
         * Creates a less-than condition.
         * Matches documents where the specified field is less than the given value.
         *
         * @param field The field name to check.
         * @param value The value to compare against.
         * @return A new less-than condition.
         */
        fun lt(
            field: String,
            value: Any
        ) = Condition(field, Operator.LT, value)

        /**
         * Creates a greater-than-or-equal condition.
         * Matches documents where the specified field is greater than or equal to the given value.
         *
         * @param field The field name to check.
         * @param value The value to compare against.
         * @return A new greater-than-or-equal condition.
         */
        fun gte(
            field: String,
            value: Any
        ) = Condition(field, Operator.GTE, value)

        /**
         * Creates a less-than-or-equal condition.
         * Matches documents where the specified field is less than or equal to the given value.
         *
         * @param field The field name to check.
         * @param value The value to compare against.
         * @return A new less-than-or-equal condition.
         */
        fun lte(
            field: String,
            value: Any
        ) = Condition(field, Operator.LTE, value)

        /**
         * Creates a contains condition for string fields.
         * Matches documents where the specified field contains the given substring.
         *
         * @param field The field name to check.
         * @param value The substring to search for.
         * @param ignoreCase Whether to perform case-insensitive matching. Defaults to false.
         * @return A new contains condition.
         */
        fun contains(
            field: String,
            value: String,
            ignoreCase: Boolean = false
        ) = Condition(field, Operator.CONTAINS, value, options = ignoreCaseOptions(ignoreCase))

        /**
         * Creates a starts-with condition for string fields.
         * Matches documents where the specified field starts with the given prefix.
         *
         * @param field The field name to check.
         * @param value The prefix to match.
         * @param ignoreCase Whether to perform case-insensitive matching. Defaults to false.
         * @return A new starts-with condition.
         */
        fun startsWith(
            field: String,
            value: String,
            ignoreCase: Boolean = false
        ) = Condition(field, Operator.STARTS_WITH, value, options = ignoreCaseOptions(ignoreCase))

        /**
         * Creates an ends-with condition for string fields.
         * Matches documents where the specified field ends with the given suffix.
         *
         * @param field The field name to check.
         * @param value The suffix to match.
         * @param ignoreCase Whether to perform case-insensitive matching. Defaults to false.
         * @return A new ends-with condition.
         */
        fun endsWith(
            field: String,
            value: String,
            ignoreCase: Boolean = false
        ) = Condition(field, Operator.ENDS_WITH, value, options = ignoreCaseOptions(ignoreCase))

        /**
         * Creates an in condition.
         * Matches documents where the specified field value is in the given list of values.
         *
         * @param field The field name to check.
         * @param value The list of values to match against.
         * @return A new in condition.
         */
        fun isIn(
            field: String,
            value: List<Any>
        ) = Condition(field, Operator.IN, value)

        /**
         * Creates a not-in condition.
         * Matches documents where the specified field value is not in the given list of values.
         *
         * @param field The field name to check.
         * @param value The list of values to exclude.
         * @return A new not-in condition.
         */
        fun notIn(
            field: String,
            value: List<Any>
        ) = Condition(field, Operator.NOT_IN, value)

        /**
         * Creates a between condition.
         * Matches documents where the specified field value is between the start and end values (inclusive).
         *
         * @param V The type of the boundary values.
         * @param field The field name to check.
         * @param start The lower boundary value.
         * @param end The upper boundary value.
         * @return A new between condition.
         */
        fun <V> between(
            field: String,
            start: V,
            end: V
        ) = Condition(field, Operator.BETWEEN, listOf(start, end))

        /**
         * Creates an all-in condition for array fields.
         * Matches documents where the specified array field contains all of the given values.
         *
         * @param field The array field name to check.
         * @param value The list of values that must all be present in the array.
         * @return A new all-in condition.
         */
        fun all(
            field: String,
            value: List<Any>
        ) = Condition(field, Operator.ALL_IN, value)

        /**
         * Creates an element match condition for array fields.
         * Matches documents where at least one element in the specified array field matches the given condition.
         *
         * @param field The array field name to check.
         * @param value The condition that at least one array element must match.
         * @return A new element match condition.
         */
        fun elemMatch(
            field: String,
            value: Condition
        ) = Condition(field, Operator.ELEM_MATCH, children = listOf(value))

        /**
         * Creates a null check condition.
         * Matches documents where the specified field is null.
         *
         * @param field The field name to check.
         * @return A new null condition.
         */
        fun isNull(field: String) = Condition(field, Operator.NULL)

        /**
         * Creates a not-null check condition.
         * Matches documents where the specified field is not null.
         *
         * @param field The field name to check.
         * @return A new not-null condition.
         */
        fun notNull(field: String) = Condition(field, Operator.NOT_NULL)

        /**
         * Creates a boolean true condition.
         * Matches documents where the specified field is true.
         *
         * @param field The field name to check.
         * @return A new true condition.
         */
        fun isTrue(field: String) = Condition(field, Operator.TRUE)

        /**
         * Creates a boolean false condition.
         * Matches documents where the specified field is false.
         *
         * @param field The field name to check.
         * @return A new false condition.
         */
        fun isFalse(field: String) = Condition(field, Operator.FALSE)

        /**
         * Creates an existence check condition.
         * Matches documents where the specified field exists (or doesn't exist based on the exists parameter).
         *
         * @param field The field name to check for existence.
         * @param exists Whether to check for existence (true) or non-existence (false). Defaults to true.
         * @return A new exists condition.
         */
        fun exists(
            field: String,
            exists: Boolean = true
        ) = Condition(field, Operator.EXISTS, exists)

        /**
         * Creates an ID condition.
         * Matches documents with the specified identifier.
         *
         * @param value The ID value to match.
         * @return A new ID condition.
         */
        fun id(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.ID, value = value)

        /**
         * Creates an IDs condition.
         * Matches documents with any of the specified identifiers.
         *
         * @param value The list of ID values to match.
         * @return A new IDs condition.
         */
        fun ids(value: List<String>) = Condition(field = EMPTY_VALUE, operator = Operator.IDS, value = value)

        /**
         * Creates an IDs condition with varargs.
         * Matches documents with any of the specified identifiers.
         *
         * @param value The ID values to match.
         * @return A new IDs condition.
         */
        fun ids(vararg value: String) = ids(value.asList())

        /**
         * Creates an aggregate ID condition.
         * Matches documents belonging to the specified aggregate.
         *
         * @param value The aggregate ID to match.
         * @return A new aggregate ID condition.
         */
        fun aggregateId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.AGGREGATE_ID, value = value)

        /**
         * Creates an aggregate IDs condition.
         * Matches documents belonging to any of the specified aggregates.
         *
         * @param value The list of aggregate IDs to match.
         * @return A new aggregate IDs condition.
         */
        fun aggregateIds(value: List<String>) =
            Condition(
                field = EMPTY_VALUE,
                operator = Operator.AGGREGATE_IDS,
                value = value,
            )

        /**
         * Creates an aggregate IDs condition with varargs.
         * Matches documents belonging to any of the specified aggregates.
         *
         * @param value The aggregate IDs to match.
         * @return A new aggregate IDs condition.
         */
        fun aggregateIds(vararg value: String) = aggregateIds(value.asList())

        /**
         * Creates a tenant ID condition.
         * Matches documents belonging to the specified tenant.
         *
         * @param value The tenant ID to match.
         * @return A new tenant ID condition.
         */
        fun tenantId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.TENANT_ID, value = value)

        /**
         * Creates an owner ID condition.
         * Matches documents owned by the specified owner.
         *
         * @param value The owner ID to match.
         * @return A new owner ID condition.
         */
        fun ownerId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.OWNER_ID, value = value)

        fun spaceId(value: String) = Condition(field = EMPTY_VALUE, operator = Operator.SPACE_ID, value = value)

        /**
         * Creates a deletion state condition using a boolean value.
         * Matches documents based on their deletion state.
         *
         * @param value true for deleted documents, false for active documents.
         * @return A new deletion state condition.
         */
        fun deleted(value: Boolean): Condition {
            val deletionState =
                if (value) {
                    DeletionState.DELETED
                } else {
                    DeletionState.ACTIVE
                }
            return deleted(deletionState)
        }

        /**
         * Creates a deletion state condition using a DeletionState enum.
         * Matches documents based on their deletion state.
         *
         * @param value The deletion state to match.
         * @return A new deletion state condition.
         */
        fun deleted(value: DeletionState) = Condition(field = EMPTY_VALUE, operator = Operator.DELETED, value = value)

        /**
         * Creates a today condition for date fields.
         * Matches documents where the specified date field falls within today's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new today condition.
         */
        fun today(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.TODAY, options = datePatternOptions(datePattern))

        /**
         * Creates a before-today condition for date fields.
         * Matches documents where the specified date field is before the specified time today.
         *
         * @param field The date field name to check.
         * @param time The time value to compare against (within today).
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new before-today condition.
         */
        fun beforeToday(
            field: String,
            time: Any,
            datePattern: Any? = null
        ) = Condition(
            field = field,
            operator = Operator.BEFORE_TODAY,
            value = time,
            options = datePatternOptions(datePattern),
        )

        /**
         * Creates a tomorrow condition for date fields.
         * Matches documents where the specified date field falls within tomorrow's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new tomorrow condition.
         */
        fun tomorrow(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.TOMORROW, options = datePatternOptions(datePattern))

        /**
         * Creates a this-week condition for date fields.
         * Matches documents where the specified date field falls within the current week's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new this-week condition.
         */
        fun thisWeek(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.THIS_WEEK, options = datePatternOptions(datePattern))

        /**
         * Creates a next-week condition for date fields.
         * Matches documents where the specified date field falls within next week's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new next-week condition.
         */
        fun nextWeek(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.NEXT_WEEK, options = datePatternOptions(datePattern))

        /**
         * Creates a last-week condition for date fields.
         * Matches documents where the specified date field falls within last week's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new last-week condition.
         */
        fun lastWeek(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.LAST_WEEK, options = datePatternOptions(datePattern))

        /**
         * Creates a this-month condition for date fields.
         * Matches documents where the specified date field falls within the current month's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new this-month condition.
         */
        fun thisMonth(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.THIS_MONTH, options = datePatternOptions(datePattern))

        /**
         * Creates a last-month condition for date fields.
         * Matches documents where the specified date field falls within last month's date range.
         *
         * @param field The date field name to check.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new last-month condition.
         */
        fun lastMonth(
            field: String,
            datePattern: Any? = null
        ) = Condition(field = field, operator = Operator.LAST_MONTH, options = datePatternOptions(datePattern))

        /**
         * Creates a recent-days condition for date fields.
         * Matches documents where the specified date field falls within the last N days (including today).
         *
         * @param field The date field name to check.
         * @param days The number of recent days to include (e.g., 7 for last week including today).
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new recent-days condition.
         */
        fun recentDays(
            field: String,
            days: Int,
            datePattern: Any? = null
        ) = Condition(
            field = field,
            operator = Operator.RECENT_DAYS,
            value = days,
            options = datePatternOptions(datePattern),
        )

        /**
         * Creates an earlier-days condition for date fields.
         * Matches documents where the specified date field is earlier than N days ago.
         *
         * @param field The date field name to check.
         * @param days The number of days ago as the cutoff point.
         * @param datePattern Optional date pattern for parsing/formatting. Can be String or DateTimeFormatter.
         * @return A new earlier-days condition.
         */
        fun earlierDays(
            field: String,
            days: Int,
            datePattern: Any? = null
        ) = Condition(
            field = field,
            operator = Operator.EARLIER_DAYS,
            value = days,
            options = datePatternOptions(datePattern),
        )

        /**
         * Creates a raw condition.
         * Uses the provided value directly as a raw database query condition without any processing.
         *
         * @param value The raw query condition value to use directly.
         * @return A new raw condition.
         */
        fun raw(value: Any) = Condition(field = EMPTY_VALUE, operator = Operator.RAW, value = value)
    }
}
