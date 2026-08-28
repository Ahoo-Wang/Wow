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

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
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
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@QueryDslMarker
class FilterDsl internal constructor(
    private val prefix: String = "",
    private val allowScopedExpression: Boolean = false,
) {
    private val expressions = mutableListOf<FilterExpression>()

    private fun add(expression: FilterExpression) {
        expressions += expression
    }

    /**
     * Adds a prebuilt filter expression unchanged at the current query context root.
     *
     * Logical fields in [expression] must already be valid for the insertion context.
     */
    fun expression(expression: FilterExpression) {
        require(prefix == "" || allowScopedExpression) {
            "Prebuilt expression cannot be added inside a path scope."
        }
        add(expression)
    }

    fun matchAll() = add(MatchAllFilter)

    fun matchNone() = add(MatchNoneFilter)

    fun id(value: String) = expression(IdFilter(value))

    fun ids(values: List<String>) = expression(IdsFilter(values))

    fun ids(vararg values: String) = ids(values.toList())

    fun aggregateId(value: String) = expression(AggregateIdFilter(value))

    fun aggregateIds(values: List<String>) = expression(AggregateIdsFilter(values))

    fun aggregateIds(vararg values: String) = aggregateIds(values.toList())

    fun tenantId(value: String) = expression(TenantIdFilter(value))

    fun ownerId(value: String) = expression(OwnerIdFilter(value))

    fun spaceId(value: String) = expression(SpaceIdFilter(value))

    fun deletion(deletionState: DeletionState) = expression(DeletionFilter(deletionState))

    fun and(block: FilterDsl.() -> Unit) = add(nestedLogical("AND", ::AndFilter, block))

    fun or(block: FilterDsl.() -> Unit) = add(nestedLogical("OR", ::OrFilter, block))

    fun nor(block: FilterDsl.() -> Unit) = add(nestedLogical("NOR", ::NorFilter, block))

    /**
     * Applies [block] in the logical field path scope represented by this string.
     *
     * Relative paths extend the current scope, while paths starting with the current scope plus `.` remain unchanged.
     * Multiple expressions in [block] form one implicit AND operand.
     */
    fun String.path(block: FilterDsl.() -> Unit) {
        val scoped = FilterDsl(prefix = field(this).value).apply(block)
        require(scoped.expressions.isNotEmpty()) { "path block cannot be empty." }
        add(scoped.build())
    }

    @Deprecated("Use path. Unlike nested, path groups multiple expressions with AND.")
    fun String.nested(block: FilterDsl.() -> Unit) {
        val nested = FilterDsl(
            prefix = field(this).value,
            allowScopedExpression = prefix == "" || allowScopedExpression,
        ).apply(block)
        require(nested.expressions.isNotEmpty()) { "nested block cannot be empty." }
        nested.expressions.forEach(::add)
    }

    fun String.elementMatch(block: FilterDsl.() -> Unit) {
        val nested = FilterDsl().apply(block)
        require(nested.expressions.isNotEmpty()) { "elementMatch block cannot be empty." }
        add(ElementMatchFilter(field(this), nested.build()))
    }

    infix fun String.eq(value: Any?) = add(
        value.literal().let {
            if (it.isNull) IsNullFilter(field(this)) else EqualFilter(field(this), it)
        }
    )

    infix fun String.ne(value: Any?) = add(
        value.literal().let {
            if (it.isNull) IsNotNullFilter(field(this)) else NotEqualFilter(field(this), it)
        }
    )

    infix fun String.gt(value: Any?) = add(GreaterThanFilter(field(this), value.literal()))

    infix fun String.gte(value: Any?) = add(GreaterThanOrEqualFilter(field(this), value.literal()))

    infix fun String.lt(value: Any?) = add(LessThanFilter(field(this), value.literal()))

    infix fun String.lte(value: Any?) = add(LessThanOrEqualFilter(field(this), value.literal()))

    fun String.contains(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(ContainsFilter(field(this), value, comparison))

    fun String.startsWith(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(StartsWithFilter(field(this), value, comparison))

    fun String.endsWith(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(EndsWithFilter(field(this), value, comparison))

    infix fun String.isIn(values: Iterable<*>) = add(InFilter(field(this), values.literals()))

    infix fun String.notIn(values: Iterable<*>) = add(NotInFilter(field(this), values.literals()))

    fun String.between(lowerBound: Any?, upperBound: Any?) =
        add(BetweenFilter(field(this), lowerBound.literal(), upperBound.literal()))

    infix fun String.containsAll(values: Iterable<*>) = add(ContainsAllFilter(field(this), values.literals()))

    fun String.isEmptyCollection() = add(IsEmptyFilter(field(this)))

    fun String.isNull() = add(IsNullFilter(field(this)))

    fun String.isNotNull() = add(IsNotNullFilter(field(this)))

    fun String.exists() = add(ExistsFilter(field(this)))

    fun String.notExists() = add(NotExistsFilter(field(this)))

    fun search(query: String, vararg fields: String) = search(query, SearchMode.TERMS, *fields)

    fun search(query: String, mode: SearchMode, vararg fields: String) =
        add(SearchFilter(query, fields.mapTo(linkedSetOf(), ::field), mode))

    infix fun String.search(query: String) = search(query, this)

    fun String.today(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(TodayFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.yesterday(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(YesterdayFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.beforeToday(
        time: LocalTime,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(BeforeTodayFilter(field(this), time.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.tomorrow(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(TomorrowFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.thisWeek(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(ThisWeekFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.nextWeek(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(NextWeekFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.lastWeek(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(LastWeekFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.thisMonth(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(ThisMonthFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.lastMonth(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(LastMonthFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.nextMonth(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(NextMonthFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.lastYear(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(LastYearFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.thisYear(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(ThisYearFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.nextYear(
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(NextYearFilter(field(this), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.recentDays(
        days: Int,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(RecentDaysFilter(field(this), days, zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.earlierDays(
        days: Int,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) = add(EarlierDaysFilter(field(this), days, zoneId?.id, datePattern, timeUnit = timeUnit))

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
        val nested = FilterDsl(
            prefix = prefix,
            allowScopedExpression = allowScopedExpression,
        ).apply(block)
        require(nested.expressions.isNotEmpty()) { "$name block cannot be empty." }
        return create(nested.expressions.toList())
    }

    private fun field(value: String): LogicalField = LogicalField(resolvePath(value))

    private fun resolvePath(value: String): String =
        if (prefix == "" || value.startsWith(prefix = "$prefix.")) value else "$prefix.$value"

    private fun Any?.literal(): JsonNode = JsonSerializer.valueToTree(this)

    private fun Iterable<*>.literals(): List<JsonNode> = map { it.literal() }
}

fun filter(block: FilterDsl.() -> Unit): FilterExpression = FilterDsl().apply(block).build()
