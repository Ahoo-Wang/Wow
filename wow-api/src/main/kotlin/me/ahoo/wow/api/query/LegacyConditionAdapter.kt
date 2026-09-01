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

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Deprecated("Scheduled for removal in 10.0.0. Use FilterExpression directly.")
fun Condition.toFilterExpression(): FilterExpression = when (operator) {
    Operator.ALL -> MatchAllFilter
    Operator.AND -> AndFilter(children.requireChildren("AND").map(Condition::toFilterExpression))
    Operator.OR -> OrFilter(children.requireChildren("OR").map(Condition::toFilterExpression))
    Operator.NOR -> NorFilter(children.requireChildren("NOR").map(Condition::toFilterExpression))
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
        ?.let { ContainsAllFilter(LogicalField(field), it) } ?: MatchNoneFilter
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
    val predicates = children.requireChildren("ELEM_MATCH").map(Condition::toFilterExpression)
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
