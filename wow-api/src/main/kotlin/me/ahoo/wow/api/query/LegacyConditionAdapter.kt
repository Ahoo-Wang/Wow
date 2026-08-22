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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.api.query

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal object LegacyConditionAdapter {
    private val jsonMapper = JsonMapper.builder().build()

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun adapt(condition: Condition): FilterExpression = when (condition.operator) {
        Operator.AND -> AndFilter(condition.requireChildren().map(::adapt))
        Operator.OR -> OrFilter(condition.requireChildren().map(::adapt))
        Operator.NOR -> NorFilter(condition.requireChildren().map(::adapt))
        Operator.ID -> EqualFilter(LogicalField("_id"), condition.literal())
        Operator.IDS -> condition.collection("_id", ::InFilter, MatchNoneFilter)
        Operator.AGGREGATE_ID -> EqualFilter(LogicalField("aggregateId"), condition.literal())
        Operator.AGGREGATE_IDS -> condition.collection("aggregateId", ::InFilter, MatchNoneFilter)
        Operator.TENANT_ID -> EqualFilter(LogicalField("tenantId"), condition.literal())
        Operator.OWNER_ID -> EqualFilter(LogicalField("ownerId"), condition.literal())
        Operator.SPACE_ID -> EqualFilter(LogicalField("spaceId"), condition.literal())
        Operator.DELETED -> DeletionFilter(condition.deletionState())
        Operator.ALL -> MatchAllFilter
        Operator.EQ -> condition.equal()
        Operator.NE -> condition.notEqual()
        Operator.GT -> GreaterThanFilter(condition.logicalField(), condition.literal())
        Operator.LT -> LessThanFilter(condition.logicalField(), condition.literal())
        Operator.GTE -> GreaterThanOrEqualFilter(condition.logicalField(), condition.literal())
        Operator.LTE -> LessThanOrEqualFilter(condition.logicalField(), condition.literal())
        Operator.CONTAINS -> ContainsFilter(condition.logicalField(), condition.stringValue(), condition.comparison())
        Operator.IN -> condition.collection(condition.field, ::InFilter, MatchNoneFilter)
        Operator.NOT_IN -> condition.collection(condition.field, ::NotInFilter, MatchAllFilter)
        Operator.BETWEEN -> condition.between()
        Operator.ALL_IN -> condition.collection(condition.field, ::ContainsAllFilter, MatchAllFilter)
        Operator.STARTS_WITH -> StartsWithFilter(
            condition.logicalField(),
            condition.stringValue(),
            condition.comparison()
        )
        Operator.ENDS_WITH -> EndsWithFilter(condition.logicalField(), condition.stringValue(), condition.comparison())
        Operator.ELEM_MATCH -> ElementMatchFilter(condition.logicalField(), adapt(condition.requireChildren().single()))
        Operator.NULL -> IsNullFilter(condition.logicalField())
        Operator.NOT_NULL -> IsNotNullFilter(condition.logicalField())
        Operator.TRUE -> EqualFilter(condition.logicalField(), jsonMapper.valueToTree(true))
        Operator.FALSE -> EqualFilter(condition.logicalField(), jsonMapper.valueToTree(false))
        Operator.EXISTS -> if (condition.valueAs<Boolean>()) {
            ExistsFilter(condition.logicalField())
        } else {
            NotExistsFilter(condition.logicalField())
        }

        Operator.TODAY -> condition.relativeTime(::TodayFilter)
        Operator.BEFORE_TODAY -> condition.relativeTime { field, zoneId, datePattern, dateFormatter ->
            BeforeTodayFilter(field, condition.localTime().toString(), zoneId, datePattern, dateFormatter)
        }
        Operator.TOMORROW -> condition.relativeTime(::TomorrowFilter)
        Operator.THIS_WEEK -> condition.relativeTime(::ThisWeekFilter)
        Operator.NEXT_WEEK -> condition.relativeTime(::NextWeekFilter)
        Operator.LAST_WEEK -> condition.relativeTime(::LastWeekFilter)
        Operator.THIS_MONTH -> condition.relativeTime(::ThisMonthFilter)
        Operator.LAST_MONTH -> condition.relativeTime(::LastMonthFilter)
        Operator.RECENT_DAYS -> condition.relativeTime { field, zoneId, datePattern, dateFormatter ->
            RecentDaysFilter(field, condition.numberValue().toInt(), zoneId, datePattern, dateFormatter)
        }
        Operator.EARLIER_DAYS -> condition.relativeTime { field, zoneId, datePattern, dateFormatter ->
            EarlierDaysFilter(field, condition.numberValue().toInt(), zoneId, datePattern, dateFormatter)
        }
        Operator.MATCH -> SearchFilter(condition.stringValue(), setOf(condition.logicalField()))
    }

    private fun Condition.requireChildren(): List<Condition> {
        require(children.isNotEmpty()) { "$operator children cannot be empty." }
        return children
    }

    private fun Condition.logicalField(field: String = this.field): LogicalField = LogicalField(field)

    private fun Condition.literal(value: Any? = this.value): JsonNode = jsonMapper.valueToTree<JsonNode>(value).also {
        it.requireFilterLiteral()
    }

    private fun Condition.stringValue(): String {
        require(value is String) { "$operator value must be a string." }
        return value
    }

    private fun Condition.numberValue(): Number {
        require(value is Number) { "$operator value must be a number." }
        return value
    }

    private fun Condition.comparison(): StringComparison = if (ignoreCase() == true) {
        StringComparison.CASE_INSENSITIVE
    } else {
        StringComparison.CASE_SENSITIVE
    }

    private fun Condition.equal(): FilterExpression {
        if (value is Collection<*>) {
            require(value.isEmpty()) { "EQ collection value is not supported." }
            return IsEmptyFilter(logicalField())
        }
        val literal = literal()
        return if (literal.isNull) IsNullFilter(logicalField()) else EqualFilter(logicalField(), literal)
    }

    private fun Condition.notEqual(): FilterExpression {
        if (value is Collection<*>) {
            require(value.isEmpty()) { "NE collection value is not supported." }
            return NorFilter(listOf(IsEmptyFilter(logicalField())))
        }
        val literal = literal()
        return if (literal.isNull) IsNotNullFilter(logicalField()) else NotEqualFilter(logicalField(), literal)
    }

    private fun Condition.collection(
        field: String,
        create: (LogicalField, List<JsonNode>) -> FilterExpression,
        empty: FilterExpression,
    ): FilterExpression {
        require(value is Iterable<*>) { "$operator value must be an iterable." }
        val values = value.map { literal(it) }
        return if (values.isEmpty()) empty else create(logicalField(field), values)
    }

    private fun Condition.between(): FilterExpression {
        require(value is Iterable<*>) { "BETWEEN value must be an iterable." }
        val values = value.map { literal(it) }
        require(values.size == 2) { "BETWEEN value must contain exactly two elements." }
        return BetweenFilter(logicalField(), values[0], values[1])
    }

    private fun Condition.localTime(): LocalTime = when (value) {
        is Number -> LocalTime.ofSecondOfDay(value.toLong())
        is String -> LocalTime.parse(value)
        is LocalTime -> value
        else -> throw IllegalArgumentException("BEFORE_TODAY value is invalid.")
    }

    private fun Condition.relativeTime(
        create: (LogicalField, String?, String?, DateTimeFormatter?) -> FilterExpression,
    ): FilterExpression {
        val datePatternOption = options[Condition.DATE_PATTERN_OPTION_KEY]
        val datePattern = datePatternOption as? String
        val dateFormatter = when (datePatternOption) {
            is String -> null
            is DateTimeFormatter -> datePatternOption
            null -> null
            else -> error("Unsupported date pattern type: ${datePatternOption::class.java.name}")
        }
        return create(logicalField(), zoneId()?.id, datePattern, dateFormatter)
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun FilterExpression.toLegacyCondition(): Condition = when (this) {
    MatchAllFilter -> Condition.ALL
    MatchNoneFilter -> Condition.ids(emptyList())
    is AndFilter -> Condition.and(operands.map(FilterExpression::toLegacyCondition))
    is OrFilter -> Condition.or(operands.map(FilterExpression::toLegacyCondition))
    is NorFilter -> Condition.nor(operands.map(FilterExpression::toLegacyCondition))
    is EqualFilter -> if (value.isNull) {
        Condition.isNull(field.value)
    } else {
        Condition.eq(field.value, value.toRequiredLegacyValue())
    }
    is NotEqualFilter -> if (value.isNull) {
        Condition.notNull(field.value)
    } else {
        Condition.ne(field.value, value.toRequiredLegacyValue())
    }
    is GreaterThanFilter -> Condition.gt(field.value, value.toRequiredLegacyValue())
    is GreaterThanOrEqualFilter -> Condition.gte(field.value, value.toRequiredLegacyValue())
    is LessThanFilter -> Condition.lt(field.value, value.toRequiredLegacyValue())
    is LessThanOrEqualFilter -> Condition.lte(field.value, value.toRequiredLegacyValue())
    is ContainsFilter -> Condition.contains(field.value, value, stringComparison.isIgnoreCase)
    is StartsWithFilter -> Condition.startsWith(field.value, value, stringComparison.isIgnoreCase)
    is EndsWithFilter -> Condition.endsWith(field.value, value, stringComparison.isIgnoreCase)
    is InFilter -> Condition.isIn(field.value, values.map(JsonNode::toRequiredLegacyValue))
    is NotInFilter -> Condition.notIn(field.value, values.map(JsonNode::toRequiredLegacyValue))
    is BetweenFilter -> Condition.between(
        field.value,
        lowerBound.toRequiredLegacyValue(),
        upperBound.toRequiredLegacyValue(),
    )
    is ContainsAllFilter -> Condition.all(field.value, values.map(JsonNode::toRequiredLegacyValue))
    is IsEmptyFilter -> Condition.eq(field.value, emptyList<Any>())
    is IsNullFilter -> Condition.isNull(field.value)
    is IsNotNullFilter -> Condition.notNull(field.value)
    is ExistsFilter -> Condition.exists(field.value)
    is NotExistsFilter -> Condition.exists(field.value, false)
    is DeletionFilter -> Condition.deleted(deletionState)
    is ElementMatchFilter -> Condition.elemMatch(field.value, predicate.toLegacyCondition())
    is SearchFilter -> Condition.match(fields.firstOrNull()?.value.orEmpty(), query)
    is TodayFilter -> relativeCondition(field, Operator.TODAY, zoneId, datePattern, dateFormatter)
    is BeforeTodayFilter -> relativeCondition(
        field,
        Operator.BEFORE_TODAY,
        zoneId,
        datePattern,
        dateFormatter,
        time,
    )
    is TomorrowFilter -> relativeCondition(field, Operator.TOMORROW, zoneId, datePattern, dateFormatter)
    is ThisWeekFilter -> relativeCondition(field, Operator.THIS_WEEK, zoneId, datePattern, dateFormatter)
    is NextWeekFilter -> relativeCondition(field, Operator.NEXT_WEEK, zoneId, datePattern, dateFormatter)
    is LastWeekFilter -> relativeCondition(field, Operator.LAST_WEEK, zoneId, datePattern, dateFormatter)
    is ThisMonthFilter -> relativeCondition(field, Operator.THIS_MONTH, zoneId, datePattern, dateFormatter)
    is LastMonthFilter -> relativeCondition(field, Operator.LAST_MONTH, zoneId, datePattern, dateFormatter)
    is RecentDaysFilter -> relativeCondition(
        field,
        Operator.RECENT_DAYS,
        zoneId,
        datePattern,
        dateFormatter,
        days,
    )
    is EarlierDaysFilter -> relativeCondition(
        field,
        Operator.EARLIER_DAYS,
        zoneId,
        datePattern,
        dateFormatter,
        days,
    )
}

private val StringComparison.isIgnoreCase: Boolean
    get() = this == StringComparison.CASE_INSENSITIVE

private fun JsonNode.toLegacyValue(): Any? = when {
    isNull -> null
    isString -> asString()
    isNumber -> numberValue()
    isBoolean -> booleanValue()
    else -> error("Filter value must be a JSON scalar.")
}

private fun JsonNode.toRequiredLegacyValue(): Any =
    requireNotNull(toLegacyValue()) { "Filter value cannot be null." }

private fun relativeCondition(
    field: LogicalField,
    operator: Operator,
    zoneId: String?,
    datePattern: String?,
    dateFormatter: DateTimeFormatter?,
    value: Any = Condition.EMPTY_VALUE,
): Condition = Condition(
    field = field.value,
    operator = operator,
    value = value,
    options = buildMap {
        zoneId?.let { put(Condition.ZONE_ID_OPTION_KEY, it) }
        (dateFormatter ?: datePattern)?.let { put(Condition.DATE_PATTERN_OPTION_KEY, it) }
    },
)

@Deprecated("Use FilterExpression directly.")
fun Condition.toFilterExpression(): FilterExpression = LegacyConditionAdapter.adapt(this)
