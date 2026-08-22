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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import java.time.LocalTime
import java.time.ZoneId

@QueryDslMarker
class FilterDsl internal constructor(private val prefix: String = "") {
    private val expressions = mutableListOf<FilterExpression>()

    fun expression(expression: FilterExpression) {
        expressions += expression
    }

    fun matchAll() = expression(MatchAllFilter)

    fun matchNone() = expression(MatchNoneFilter)

    fun deletion(deletionState: DeletionState) = expression(DeletionFilter(deletionState))

    fun and(block: FilterDsl.() -> Unit) = expression(nestedLogical("AND", ::AndFilter, block))

    fun or(block: FilterDsl.() -> Unit) = expression(nestedLogical("OR", ::OrFilter, block))

    fun nor(block: FilterDsl.() -> Unit) = expression(nestedLogical("NOR", ::NorFilter, block))

    fun String.nested(block: FilterDsl.() -> Unit) {
        val nested = FilterDsl(field(this).value).apply(block)
        require(nested.expressions.isNotEmpty()) { "nested block cannot be empty." }
        expressions += nested.expressions
    }

    fun String.elementMatch(block: FilterDsl.() -> Unit) {
        val nested = FilterDsl().apply(block)
        require(nested.expressions.isNotEmpty()) { "elementMatch block cannot be empty." }
        expression(ElementMatchFilter(field(this), nested.build()))
    }

    infix fun String.eq(value: Any?) = expression(
        value.literal().let {
            if (it.isNull) IsNullFilter(field(this)) else EqualFilter(field(this), it)
        }
    )

    infix fun String.ne(value: Any?) = expression(
        value.literal().let {
            if (it.isNull) IsNotNullFilter(field(this)) else NotEqualFilter(field(this), it)
        }
    )

    infix fun String.gt(value: Any?) = expression(GreaterThanFilter(field(this), value.literal()))

    infix fun String.gte(value: Any?) = expression(GreaterThanOrEqualFilter(field(this), value.literal()))

    infix fun String.lt(value: Any?) = expression(LessThanFilter(field(this), value.literal()))

    infix fun String.lte(value: Any?) = expression(LessThanOrEqualFilter(field(this), value.literal()))

    fun String.contains(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        expression(ContainsFilter(field(this), value, comparison))

    fun String.startsWith(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        expression(StartsWithFilter(field(this), value, comparison))

    fun String.endsWith(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        expression(EndsWithFilter(field(this), value, comparison))

    infix fun String.isIn(values: Iterable<*>) = expression(InFilter(field(this), values.literals()))

    infix fun String.notIn(values: Iterable<*>) = expression(NotInFilter(field(this), values.literals()))

    fun String.between(lowerBound: Any?, upperBound: Any?) =
        expression(BetweenFilter(field(this), lowerBound.literal(), upperBound.literal()))

    infix fun String.containsAll(values: Iterable<*>) = expression(ContainsAllFilter(field(this), values.literals()))

    fun String.isEmptyCollection() = expression(IsEmptyFilter(field(this)))

    fun String.isNull() = expression(IsNullFilter(field(this)))

    fun String.isNotNull() = expression(IsNotNullFilter(field(this)))

    fun String.exists() = expression(ExistsFilter(field(this)))

    fun String.notExists() = expression(NotExistsFilter(field(this)))

    fun search(query: String, vararg fields: String) =
        expression(SearchFilter(query, fields.mapTo(linkedSetOf(), ::field)))

    infix fun String.search(query: String) = search(query, this)

    fun String.today(zoneId: ZoneId? = null) = expression(TodayFilter(field(this), zoneId?.id))

    fun String.beforeToday(time: LocalTime, zoneId: ZoneId? = null) =
        expression(BeforeTodayFilter(field(this), time.toString(), zoneId?.id))

    fun String.tomorrow(zoneId: ZoneId? = null) = expression(TomorrowFilter(field(this), zoneId?.id))

    fun String.thisWeek(zoneId: ZoneId? = null) = expression(ThisWeekFilter(field(this), zoneId?.id))

    fun String.nextWeek(zoneId: ZoneId? = null) = expression(NextWeekFilter(field(this), zoneId?.id))

    fun String.lastWeek(zoneId: ZoneId? = null) = expression(LastWeekFilter(field(this), zoneId?.id))

    fun String.thisMonth(zoneId: ZoneId? = null) = expression(ThisMonthFilter(field(this), zoneId?.id))

    fun String.lastMonth(zoneId: ZoneId? = null) = expression(LastMonthFilter(field(this), zoneId?.id))

    fun String.recentDays(days: Int, zoneId: ZoneId? = null) =
        expression(RecentDaysFilter(field(this), days, zoneId?.id))

    fun String.earlierDays(days: Int, zoneId: ZoneId? = null) =
        expression(EarlierDaysFilter(field(this), days, zoneId?.id))

    internal fun build(): FilterExpression = when (expressions.size) {
        0 -> MatchAllFilter
        1 -> expressions.first()
        else -> AndFilter(expressions.toList())
    }

    private fun nestedLogical(
        name: String,
        create: (List<FilterExpression>) -> FilterExpression,
        block: FilterDsl.() -> Unit,
    ): FilterExpression {
        val nested = FilterDsl(prefix).apply(block)
        require(nested.expressions.isNotEmpty()) { "$name block cannot be empty." }
        return create(nested.expressions.toList())
    }

    private fun field(value: String): LogicalField = LogicalField(
        if (prefix == "") value else "$prefix.$value",
    )

    private fun Any?.literal(): JsonNode = JsonSerializer.valueToTree(this)

    private fun Iterable<*>.literals(): List<JsonNode> = map { it.literal() }
}

fun filter(block: FilterDsl.() -> Unit): FilterExpression = FilterDsl().apply(block).build()
