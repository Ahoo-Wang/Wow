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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val LEGACY_VALUE_MAPPER = tools.jackson.databind.json.JsonMapper.builder().build()
private val LEGACY_NODE_FACTORY = tools.jackson.databind.node.JsonNodeFactory.instance

internal object LegacyConditionAdapter {
    fun adapt(condition: Condition): FilterExpression = LegacyConditionFilter(condition)
}

private data class LegacyConditionFilter(val condition: Condition) : FilterExpression {
    override val operator: FilterOperator = condition.operator.toFilterOperator()
}

fun FilterExpression.toExecutableFilter(): FilterExpression =
    (this as? LegacyConditionFilter)?.condition?.toExecutableFilter() ?: this

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun Condition.toExecutableFilter(): FilterExpression = when (operator) {
    Operator.ALL -> MatchAllFilter
    Operator.AND -> AndFilter(children.requireChildren("AND").map(Condition::toExecutableFilter))
    Operator.OR -> OrFilter(children.requireChildren("OR").map(Condition::toExecutableFilter))
    Operator.NOR -> NorFilter(children.requireChildren("NOR").map(Condition::toExecutableFilter))
    Operator.ID -> IdFilter(valueAs())
    Operator.IDS -> valueAs<List<String>>().takeIf { it.isNotEmpty() }?.let(::IdsFilter) ?: MatchNoneFilter
    Operator.AGGREGATE_ID -> AggregateIdFilter(valueAs())
    Operator.AGGREGATE_IDS -> valueAs<List<String>>().takeIf { it.isNotEmpty() }
        ?.let(::AggregateIdsFilter) ?: MatchNoneFilter
    Operator.TENANT_ID -> TenantIdFilter(valueAs())
    Operator.OWNER_ID -> OwnerIdFilter(valueAs())
    Operator.SPACE_ID -> SpaceIdFilter(valueAs())
    Operator.DELETED -> DeletionFilter(deletionState())
    Operator.EQ -> EqualFilter(LogicalField(field), value.toFilterValue())
    Operator.NE -> NotEqualFilter(LogicalField(field), value.toFilterValue())
    Operator.GT -> GreaterThanFilter(LogicalField(field), value.toFilterValue())
    Operator.GTE -> GreaterThanOrEqualFilter(LogicalField(field), value.toFilterValue())
    Operator.LT -> LessThanFilter(LogicalField(field), value.toFilterValue())
    Operator.LTE -> LessThanOrEqualFilter(LogicalField(field), value.toFilterValue())
    Operator.CONTAINS -> ContainsFilter(LogicalField(field), valueAs(), stringComparison())
    Operator.STARTS_WITH -> StartsWithFilter(LogicalField(field), valueAs(), stringComparison())
    Operator.ENDS_WITH -> EndsWithFilter(LogicalField(field), valueAs(), stringComparison())
    Operator.IN -> valueAs<List<Any>>().takeIf { it.isNotEmpty() }
        ?.map { it.toFilterValue() }
        ?.let { InFilter(LogicalField(field), it) } ?: MatchNoneFilter
    Operator.NOT_IN -> valueAs<List<Any>>().takeIf { it.isNotEmpty() }
        ?.map { it.toFilterValue() }
        ?.let { NotInFilter(LogicalField(field), it) } ?: MatchAllFilter
    Operator.BETWEEN -> betweenFilter()
    Operator.ALL_IN -> valueAs<List<Any>>().takeIf { it.isNotEmpty() }
        ?.map { it.toFilterValue() }
        ?.let { ContainsAllFilter(LogicalField(field), it) } ?: MatchAllFilter
    Operator.ELEM_MATCH -> elementMatchFilter()
    Operator.NULL -> IsNullFilter(LogicalField(field))
    Operator.NOT_NULL -> IsNotNullFilter(LogicalField(field))
    Operator.TRUE -> EqualFilter(LogicalField(field), true.toFilterValue())
    Operator.FALSE -> EqualFilter(LogicalField(field), false.toFilterValue())
    Operator.EXISTS -> {
        if (valueAs<Boolean>()) {
            ExistsFilter(LogicalField(field))
        } else {
            NotExistsFilter(LogicalField(field))
        }
    }
    Operator.MATCH -> SearchFilter(
        query = valueAs(),
        fields = field.takeIf(String::isNotBlank)?.let { setOf(LogicalField(it)) }.orEmpty(),
    )
    Operator.TODAY -> todayFilter()
    Operator.BEFORE_TODAY -> beforeTodayFilter()
    Operator.TOMORROW -> tomorrowFilter()
    Operator.THIS_WEEK -> thisWeekFilter()
    Operator.NEXT_WEEK -> nextWeekFilter()
    Operator.LAST_WEEK -> lastWeekFilter()
    Operator.THIS_MONTH -> thisMonthFilter()
    Operator.LAST_MONTH -> lastMonthFilter()
    Operator.RECENT_DAYS -> recentDaysFilter()
    Operator.EARLIER_DAYS -> earlierDaysFilter()
}

private fun List<Condition>.requireChildren(name: String): List<Condition> =
    apply { require(isNotEmpty()) { "$name children cannot be empty." } }

private fun Any?.toFilterValue(): JsonNode = when (this) {
    is JsonNode -> this
    null, is String, is Number, is Boolean -> LEGACY_VALUE_MAPPER.valueToTree(this)
    is Iterable<*> -> LEGACY_NODE_FACTORY.arrayNode().also { node ->
        forEach { node.add(it.toFilterValue()) }
    }
    is Array<*> -> asIterable().toFilterValue()
    else -> LEGACY_NODE_FACTORY.pojoNode(this)
}

private fun Condition.stringComparison() =
    if (ignoreCase() == true) StringComparison.CASE_INSENSITIVE else StringComparison.CASE_SENSITIVE

private fun Condition.betweenFilter(): FilterExpression {
    val bounds = valueAs<List<Any>>()
    require(bounds.size == 2) { "BETWEEN value must contain exactly 2 elements." }
    return BetweenFilter(
        LogicalField(field),
        bounds[0].toFilterValue(),
        bounds[1].toFilterValue(),
    )
}

private fun Condition.elementMatchFilter(): FilterExpression {
    val predicates = children.requireChildren("ELEM_MATCH").map(Condition::toExecutableFilter)
    return ElementMatchFilter(LogicalField(field), predicates.singleOrNull() ?: AndFilter(predicates))
}

private val Condition.logicalField: LogicalField get() = LogicalField(field)
private val Condition.zoneValue: String? get() = zoneId()?.id
private val Condition.patternValue: String?
    get() = options[Condition.DATE_PATTERN_OPTION_KEY] as? String
private val Condition.formatterValue: DateTimeFormatter?
    get() = options[Condition.DATE_PATTERN_OPTION_KEY] as? DateTimeFormatter

private fun Condition.todayFilter() =
    TodayFilter(logicalField, zoneValue, patternValue, formatterValue)
private fun Condition.tomorrowFilter() =
    TomorrowFilter(logicalField, zoneValue, patternValue, formatterValue)
private fun Condition.thisWeekFilter() =
    ThisWeekFilter(logicalField, zoneValue, patternValue, formatterValue)
private fun Condition.nextWeekFilter() =
    NextWeekFilter(logicalField, zoneValue, patternValue, formatterValue)
private fun Condition.lastWeekFilter() =
    LastWeekFilter(logicalField, zoneValue, patternValue, formatterValue)
private fun Condition.thisMonthFilter() =
    ThisMonthFilter(logicalField, zoneValue, patternValue, formatterValue)
private fun Condition.lastMonthFilter() =
    LastMonthFilter(logicalField, zoneValue, patternValue, formatterValue)

private fun Condition.beforeTodayFilter(): BeforeTodayFilter {
    val localTime = when (val raw = value) {
        is Number -> LocalTime.ofSecondOfDay(raw.toLong())
        is String -> LocalTime.parse(raw)
        is LocalTime -> raw
        else -> throw IllegalArgumentException("Unsupported BEFORE_TODAY value type: ${raw::class.java.name}.")
    }
    return BeforeTodayFilter(logicalField, localTime.toString(), zoneValue, patternValue, formatterValue)
}

private fun Condition.recentDaysFilter() = RecentDaysFilter(
    logicalField,
    (value as? Number)?.toInt()
        ?: throw IllegalArgumentException("RECENT_DAYS value must be a number."),
    zoneValue,
    patternValue,
    formatterValue,
)

private fun Condition.earlierDaysFilter() = EarlierDaysFilter(
    logicalField,
    (value as? Number)?.toInt()
        ?: throw IllegalArgumentException("EARLIER_DAYS value must be a number."),
    zoneValue,
    patternValue,
    formatterValue,
)

internal class LegacyConditionFilterValueFilter {
    override fun equals(other: Any?): Boolean = (other as? FilterExpression)?.legacyConditionOrNull() != null

    override fun hashCode(): Int = javaClass.hashCode()
}

@Suppress("CyclomaticComplexMethod")
private fun Operator.toFilterOperator(): FilterOperator = when (this) {
    Operator.AND -> FilterOperator.AND
    Operator.OR -> FilterOperator.OR
    Operator.NOR -> FilterOperator.NOR
    Operator.ALL -> FilterOperator.MATCH_ALL
    Operator.DELETED -> FilterOperator.DELETION
    Operator.ID -> FilterOperator.ID
    Operator.IDS -> FilterOperator.IDS
    Operator.AGGREGATE_ID -> FilterOperator.AGGREGATE_ID
    Operator.AGGREGATE_IDS -> FilterOperator.AGGREGATE_IDS
    Operator.TENANT_ID -> FilterOperator.TENANT_ID
    Operator.OWNER_ID -> FilterOperator.OWNER_ID
    Operator.SPACE_ID -> FilterOperator.SPACE_ID
    Operator.EQ,
    Operator.TRUE,
    Operator.FALSE,
    -> FilterOperator.EQ
    Operator.NE -> FilterOperator.NE
    Operator.GT -> FilterOperator.GT
    Operator.LT -> FilterOperator.LT
    Operator.GTE -> FilterOperator.GTE
    Operator.LTE -> FilterOperator.LTE
    Operator.CONTAINS -> FilterOperator.CONTAINS
    Operator.IN -> FilterOperator.IN
    Operator.NOT_IN -> FilterOperator.NOT_IN
    Operator.BETWEEN -> FilterOperator.BETWEEN
    Operator.ALL_IN -> FilterOperator.CONTAINS_ALL
    Operator.STARTS_WITH -> FilterOperator.STARTS_WITH
    Operator.ENDS_WITH -> FilterOperator.ENDS_WITH
    Operator.ELEM_MATCH -> FilterOperator.ELEMENT_MATCH
    Operator.NULL -> FilterOperator.IS_NULL
    Operator.NOT_NULL -> FilterOperator.IS_NOT_NULL
    Operator.EXISTS -> FilterOperator.EXISTS
    Operator.TODAY -> FilterOperator.TODAY
    Operator.BEFORE_TODAY -> FilterOperator.BEFORE_TODAY
    Operator.TOMORROW -> FilterOperator.TOMORROW
    Operator.THIS_WEEK -> FilterOperator.THIS_WEEK
    Operator.NEXT_WEEK -> FilterOperator.NEXT_WEEK
    Operator.LAST_WEEK -> FilterOperator.LAST_WEEK
    Operator.THIS_MONTH -> FilterOperator.THIS_MONTH
    Operator.LAST_MONTH -> FilterOperator.LAST_MONTH
    Operator.RECENT_DAYS -> FilterOperator.RECENT_DAYS
    Operator.EARLIER_DAYS -> FilterOperator.EARLIER_DAYS
    Operator.MATCH -> FilterOperator.SEARCH
}

@Deprecated("Legacy Condition compatibility only.")
fun FilterExpression.legacyConditionOrNull(): Condition? = (this as? LegacyConditionFilter)?.condition

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun FilterExpression.toLegacyCondition(): Condition = when (this) {
    is LegacyConditionFilter -> condition
    MatchAllFilter -> Condition.ALL
    MatchNoneFilter -> Condition.ids(emptyList())
    is IdFilter -> Condition.id(value)
    is IdsFilter -> Condition.ids(values)
    is AggregateIdFilter -> Condition.aggregateId(value)
    is AggregateIdsFilter -> Condition.aggregateIds(values)
    is TenantIdFilter -> Condition.tenantId(value)
    is OwnerIdFilter -> Condition.ownerId(value)
    is SpaceIdFilter -> Condition.spaceId(value)
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
    isArray -> asSequence().map(JsonNode::toLegacyValue).toList()
    isPojo -> (this as tools.jackson.databind.node.POJONode).pojo
    else -> error("Filter value must be a JSON scalar or scalar array.")
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

@Deprecated("Legacy Condition compatibility only. Use FilterExpression directly.")
fun FilterExpression.toCondition(): Condition = toLegacyCondition()
