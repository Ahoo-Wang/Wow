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

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalTime

internal object LegacyConditionAdapter {
    private val jsonMapper = JsonMapper.builder().build()

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun adapt(condition: Condition): FilterExpression = when (condition.operator) {
        Operator.AND -> AndFilter(condition.requireChildren().map(::adapt))
        Operator.OR -> OrFilter(condition.requireChildren().map(::adapt))
        Operator.NOR -> NorFilter(condition.requireChildren().map(::adapt))
        Operator.ID -> EqualFilter(LogicalField("id"), condition.literal())
        Operator.IDS -> condition.collection("id", ::InFilter, MatchNoneFilter)
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

        Operator.TODAY -> TodayFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.BEFORE_TODAY -> BeforeTodayFilter(
            condition.logicalField(),
            condition.localTime().toString(),
            condition.zoneId()?.id
        )
        Operator.TOMORROW -> TomorrowFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.THIS_WEEK -> ThisWeekFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.NEXT_WEEK -> NextWeekFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.LAST_WEEK -> LastWeekFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.THIS_MONTH -> ThisMonthFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.LAST_MONTH -> LastMonthFilter(condition.logicalField(), condition.zoneId()?.id)
        Operator.RECENT_DAYS -> RecentDaysFilter(
            condition.logicalField(),
            condition.numberValue().toInt(),
            condition.zoneId()?.id
        )
        Operator.EARLIER_DAYS -> EarlierDaysFilter(
            condition.logicalField(),
            condition.numberValue().toInt(),
            condition.zoneId()?.id
        )
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
}

@Deprecated("Use FilterExpression directly.")
fun Condition.toFilterExpression(): FilterExpression = LegacyConditionAdapter.adapt(this)
