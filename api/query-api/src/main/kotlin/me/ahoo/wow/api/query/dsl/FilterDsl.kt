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

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
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
import me.ahoo.wow.api.query.QueryField
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
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Builds a [FilterExpression]: each call adds one node, and the block's nodes are combined with `AND` (one node alone
 * is the filter itself). A field is named by its path, as a string (`"state.status"`) or as a [QueryField], such as a
 * generated `<State>Properties` constant; every field operator takes either.
 *
 * - **Values** are the JSON values the wire carries (a string, a number, a boolean) or a runtime value, such as an
 *   enum, which the backend compares as the JSON it writes as. `eq null` is `IS_NULL` and `ne null` is
 *   `IS_NOT_NULL`.
 * - **Combinators**: [and], [or] and [nor] take a block of operands; [not] negates its block, written on the wire as
 *   a `NOR` of one operand, since the protocol has no `NOT`.
 * - **Scopes**: [path] prefixes the relative fields of its block with a path; [elementMatch] matches the elements of
 *   a collection, its block naming fields relative to an element. A field under the element's own path, such as a
 *   generated `STATE__ITEMS__PRODUCT_ID` inside `STATE__ITEMS.elementMatch { }`, is read relative to it.
 */
@Suppress("TooManyFunctions", "LargeClass")
@QueryDslMarker
class FilterDsl internal constructor(
    private val prefix: String = "",
    private val allowScopedExpression: Boolean = false,
    /** The absolute path of the element [elementMatch] scopes this block to; `null` at the record's root. */
    private val element: QueryField? = null,
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

    /** `EXPRESSION`: compares the computed [expression] of each record with [value]. */
    fun expression(expression: AggregationExpression, comparison: ComparisonOperator, value: Double) =
        expression(ExpressionFilter(expression, comparison, value))

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

    /** Matches what [block] does not: `NOR` of the block's filter, its nodes combined with `AND` first. */
    fun not(block: FilterDsl.() -> Unit) = add(NorFilter(listOf(nested("NOT", block).build())))

    /**
     * Applies [block] in the logical field path scope represented by this string.
     *
     * Relative paths extend the current scope, while paths starting with the current scope plus `.` remain unchanged.
     * Multiple expressions in [block] form one implicit AND operand.
     */
    fun String.path(block: FilterDsl.() -> Unit) = scoped(at(), block)

    /** Applies [block] in the path scope of this field, as [String.path] does. */
    fun QueryField.path(block: FilterDsl.() -> Unit) = scoped(at(), block)

    /** `ELEMENT_MATCH`: an element of this collection matches [block], whose fields are relative to the element. */
    fun String.elementMatch(block: FilterDsl.() -> Unit) = matchElements(at(), block)

    /** `ELEMENT_MATCH` on this collection field, as [String.elementMatch] does. */
    fun QueryField.elementMatch(block: FilterDsl.() -> Unit) = matchElements(at(), block)

    infix fun String.eq(value: Any?) = equal(at(), value)

    infix fun QueryField.eq(value: Any?) = equal(at(), value)

    infix fun String.ne(value: Any?) = notEqual(at(), value)

    infix fun QueryField.ne(value: Any?) = notEqual(at(), value)

    infix fun String.gt(value: Any?) = add(GreaterThanFilter(at(), value))

    infix fun QueryField.gt(value: Any?) = add(GreaterThanFilter(at(), value))

    infix fun String.gte(value: Any?) = add(GreaterThanOrEqualFilter(at(), value))

    infix fun QueryField.gte(value: Any?) = add(GreaterThanOrEqualFilter(at(), value))

    infix fun String.lt(value: Any?) = add(LessThanFilter(at(), value))

    infix fun QueryField.lt(value: Any?) = add(LessThanFilter(at(), value))

    infix fun String.lte(value: Any?) = add(LessThanOrEqualFilter(at(), value))

    infix fun QueryField.lte(value: Any?) = add(LessThanOrEqualFilter(at(), value))

    fun String.containsText(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(ContainsFilter(at(), value, comparison))

    fun QueryField.containsText(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(ContainsFilter(at(), value, comparison))

    fun String.startsWithText(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(StartsWithFilter(at(), value, comparison))

    fun QueryField.startsWithText(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(StartsWithFilter(at(), value, comparison))

    fun String.endsWithText(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(EndsWithFilter(at(), value, comparison))

    fun QueryField.endsWithText(value: String, comparison: StringComparison = StringComparison.CASE_SENSITIVE) =
        add(EndsWithFilter(at(), value, comparison))

    infix fun String.isIn(values: Iterable<*>) = add(InFilter(at(), values.toList()))

    infix fun QueryField.isIn(values: Iterable<*>) = add(InFilter(at(), values.toList()))

    infix fun String.notIn(values: Iterable<*>) = add(NotInFilter(at(), values.toList()))

    infix fun QueryField.notIn(values: Iterable<*>) = add(NotInFilter(at(), values.toList()))

    /** `BETWEEN`: the field lies in `[lowerBound, upperBound]`, both bounds included. */
    fun String.between(lowerBound: Any?, upperBound: Any?) = add(BetweenFilter(at(), lowerBound, upperBound))

    fun QueryField.between(lowerBound: Any?, upperBound: Any?) = add(BetweenFilter(at(), lowerBound, upperBound))

    /** `BETWEEN` the bounds of [range], both included: `"state.price" between 10..20`. */
    infix fun String.between(range: ClosedRange<*>) = add(BetweenFilter(at(), range.start, range.endInclusive))

    infix fun QueryField.between(range: ClosedRange<*>) = add(BetweenFilter(at(), range.start, range.endInclusive))

    infix fun String.containsAll(values: Iterable<*>) = add(ContainsAllFilter(at(), values.toList()))

    infix fun QueryField.containsAll(values: Iterable<*>) = add(ContainsAllFilter(at(), values.toList()))

    fun String.isEmptyCollection() = add(IsEmptyFilter(at()))

    fun QueryField.isEmptyCollection() = add(IsEmptyFilter(at()))

    fun String.isEmptyString() = add(IsEmptyStringFilter(at()))

    fun QueryField.isEmptyString() = add(IsEmptyStringFilter(at()))

    fun String.isNotEmptyString() = add(IsNotEmptyStringFilter(at()))

    fun QueryField.isNotEmptyString() = add(IsNotEmptyStringFilter(at()))

    fun String.isNull() = add(IsNullFilter(at()))

    fun QueryField.isNull() = add(IsNullFilter(at()))

    fun String.isNotNull() = add(IsNotNullFilter(at()))

    fun QueryField.isNotNull() = add(IsNotNullFilter(at()))

    fun String.exists() = add(ExistsFilter(at()))

    fun QueryField.exists() = add(ExistsFilter(at()))

    fun String.notExists() = add(NotExistsFilter(at()))

    fun QueryField.notExists() = add(NotExistsFilter(at()))

    /** `SEARCH` of [query] in [fields], or in every searchable field of the model when none is named. */
    fun search(query: String, vararg fields: String) = search(query, SearchMode.TERMS, *fields)

    fun search(query: String, mode: SearchMode, vararg fields: String) =
        add(SearchFilter(query, fields.mapTo(linkedSetOf(), ::field), mode))

    fun search(query: String, mode: SearchMode, vararg fields: QueryField) =
        add(SearchFilter(query, fields.mapTo(linkedSetOf()) { it.at() }, mode))

    infix fun String.search(query: String) = search(query, this)

    infix fun QueryField.search(query: String) = search(query, SearchMode.TERMS, this)

    fun String.today(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(TodayFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.today(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(TodayFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.yesterday(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(YesterdayFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.yesterday(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(YesterdayFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    /** `BEFORE_TODAY`: before [time] of today. */
    fun String.beforeToday(
        time: LocalTime,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(BeforeTodayFilter(at(), time.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.beforeToday(
        time: LocalTime,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(BeforeTodayFilter(at(), time.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.tomorrow(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(TomorrowFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.tomorrow(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(TomorrowFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.thisWeek(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(ThisWeekFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.thisWeek(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(ThisWeekFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.nextWeek(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(NextWeekFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.nextWeek(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(NextWeekFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.lastWeek(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(LastWeekFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.lastWeek(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(LastWeekFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.thisMonth(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(ThisMonthFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.thisMonth(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(ThisMonthFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.lastMonth(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(LastMonthFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.lastMonth(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(LastMonthFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.nextMonth(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(NextMonthFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.nextMonth(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(NextMonthFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.lastYear(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(LastYearFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.lastYear(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(LastYearFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.thisYear(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(ThisYearFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.thisYear(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(ThisYearFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun String.nextYear(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(NextYearFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.nextYear(zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(NextYearFilter(at(), zoneId?.id, datePattern, timeUnit = timeUnit))

    /** `RECENT_DAYS`: within the last [days] days, today included. */
    fun String.recentDays(days: Int, zoneId: ZoneId? = null, datePattern: String? = null, timeUnit: TimeUnit = MILLIS) =
        add(RecentDaysFilter(at(), days, zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.recentDays(
        days: Int,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(RecentDaysFilter(at(), days, zoneId?.id, datePattern, timeUnit = timeUnit))

    /** `EARLIER_DAYS`: before the last [days] days. */
    fun String.earlierDays(
        days: Int,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS
    ) =
        add(EarlierDaysFilter(at(), days, zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.earlierDays(
        days: Int,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(EarlierDaysFilter(at(), days, zoneId?.id, datePattern, timeUnit = timeUnit))

    /** The field is before the server's `now + offset`; `offset` defaults to zero, i.e. before now. */
    fun String.beforeNow(
        offset: Duration = Duration.ZERO,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(BeforeNowFilter(at(), offset.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.beforeNow(
        offset: Duration = Duration.ZERO,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(BeforeNowFilter(at(), offset.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    /** The field is after the server's `now + offset`; a negative offset looks back, e.g. `Duration.ofMinutes(-30)`. */
    fun String.afterNow(
        offset: Duration = Duration.ZERO,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(AfterNowFilter(at(), offset.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    fun QueryField.afterNow(
        offset: Duration = Duration.ZERO,
        zoneId: ZoneId? = null,
        datePattern: String? = null,
        timeUnit: TimeUnit = MILLIS,
    ) = add(AfterNowFilter(at(), offset.toString(), zoneId?.id, datePattern, timeUnit = timeUnit))

    internal fun build(): FilterExpression {
        require(expressions.isNotEmpty()) { "filter block cannot be empty." }
        return expressions.singleOrNull() ?: AndFilter(expressions.toList())
    }

    private fun equal(field: QueryField, value: Any?) =
        add(if (value == null) IsNullFilter(field) else EqualFilter(field, value))

    private fun notEqual(field: QueryField, value: Any?) =
        add(if (value == null) IsNotNullFilter(field) else NotEqualFilter(field, value))

    private fun scoped(field: QueryField, block: FilterDsl.() -> Unit) {
        val scoped = FilterDsl(prefix = field.path, element = element).apply(block)
        require(scoped.expressions.isNotEmpty()) { "path block cannot be empty." }
        add(scoped.build())
    }

    /** [field] is relative to the enclosing element, if any; the nested block's element is its absolute path. */
    private fun matchElements(field: QueryField, block: FilterDsl.() -> Unit) {
        val nested = FilterDsl(element = element?.append(field) ?: field).apply(block)
        require(nested.expressions.isNotEmpty()) { "elementMatch block cannot be empty." }
        add(ElementMatchFilter(field, nested.build()))
    }

    private fun nested(name: String, block: FilterDsl.() -> Unit): FilterDsl {
        val nested = FilterDsl(prefix = prefix, allowScopedExpression = allowScopedExpression, element = element)
            .apply(block)
        require(nested.expressions.isNotEmpty()) { "$name block cannot be empty." }
        return nested
    }

    private fun nestedLogical(
        name: String,
        create: (List<FilterExpression>) -> FilterExpression,
        block: FilterDsl.() -> Unit,
    ): FilterExpression = create(nested(name, block).expressions.toList())

    private fun String.at(): QueryField = field(this)

    private fun QueryField.at(): QueryField = field(path)

    /** [value] in this block's terms: relative to its element when it lies under it, then under its path scope. */
    private fun field(value: String): QueryField {
        val relative = element?.let { QueryField(value).relativeTo(it)?.path } ?: value
        return QueryField(resolvePath(relative))
    }

    private fun resolvePath(value: String): String =
        if (prefix == "" || value.startsWith(prefix = "$prefix.")) value else "$prefix.$value"

    private companion object {
        val MILLIS: TimeUnit = TimeUnit.MILLISECONDS
    }
}

/** Builds a [FilterExpression]; see [FilterDsl]. */
fun filter(block: FilterDsl.() -> Unit): FilterExpression = FilterDsl().apply(block).build()
