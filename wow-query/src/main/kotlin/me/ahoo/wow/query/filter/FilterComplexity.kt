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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
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
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
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
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.api.query.spec.OperatorCost
import me.ahoo.wow.api.query.spec.spec
import java.util.ArrayDeque

/*
 * Pure structural analysis of filter, having and metric trees.
 *
 * Every function below uses an exhaustive `when` over the sealed hierarchy (no `else` branch), so adding a new
 * filter operator, having node or metric type fails compilation here until its complexity is classified.
 */

/**
 * Whether this single filter node (children are not inspected) is costly for a backend to evaluate, as the
 * operator's [me.ahoo.wow.api.query.spec.FilterOperatorSpec] states.
 */
fun FilterExpression.isExpensive(): Boolean = spec.cost(this) == OperatorCost.EXPENSIVE

/**
 * Whether this filter provably matches every document: [MatchAllFilter], a [DeletionFilter] for
 * [DeletionState.ALL], an [AndFilter] whose operands all match everything, or an [OrFilter] with at least one
 * operand that matches everything. Any other filter is treated as restrictive.
 */
fun FilterExpression.isMatchAll(): Boolean = when (this) {
    MatchAllFilter -> true
    is DeletionFilter -> deletionState == DeletionState.ALL
    is AndFilter -> operands.all { it.isMatchAll() }
    is OrFilter -> operands.any { it.isMatchAll() }
    MatchNoneFilter,
    is NorFilter,
    is ElementMatchFilter,
    is SearchFilter,
    is ExpressionFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is EqualFilter,
    is NotEqualFilter,
    is GreaterThanFilter,
    is GreaterThanOrEqualFilter,
    is LessThanFilter,
    is LessThanOrEqualFilter,
    is ContainsFilter,
    is StartsWithFilter,
    is EndsWithFilter,
    is InFilter,
    is NotInFilter,
    is BetweenFilter,
    is ContainsAllFilter,
    is IsEmptyFilter,
    is IsEmptyStringFilter,
    is IsNotEmptyStringFilter,
    is IsNullFilter,
    is IsNotNullFilter,
    is ExistsFilter,
    is NotExistsFilter,
    is TodayFilter,
    is BeforeTodayFilter,
    is TomorrowFilter,
    is ThisWeekFilter,
    is NextWeekFilter,
    is LastWeekFilter,
    is ThisMonthFilter,
    is LastMonthFilter,
    is RecentDaysFilter,
    is EarlierDaysFilter,
    is YesterdayFilter,
    is NextMonthFilter,
    is LastYearFilter,
    is ThisYearFilter,
    is NextYearFilter,
    is BeforeNowFilter,
    is AfterNowFilter,
    -> false
}

/**
 * The number of literal values carried by a multi-value filter node (`IN`, `NOT_IN`, `CONTAINS_ALL`, `IDS`,
 * `AGGREGATE_IDS`), or `null` for nodes that do not carry a value list.
 */
fun FilterExpression.valueCount(): Int? = when (this) {
    is InFilter -> values.size
    is NotInFilter -> values.size
    is ContainsAllFilter -> values.size
    is IdsFilter -> values.size
    is AggregateIdsFilter -> values.size
    MatchAllFilter,
    MatchNoneFilter,
    is AndFilter,
    is OrFilter,
    is NorFilter,
    is DeletionFilter,
    is ElementMatchFilter,
    is SearchFilter,
    is ExpressionFilter,
    is IdFilter,
    is AggregateIdFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is EqualFilter,
    is NotEqualFilter,
    is GreaterThanFilter,
    is GreaterThanOrEqualFilter,
    is LessThanFilter,
    is LessThanOrEqualFilter,
    is ContainsFilter,
    is StartsWithFilter,
    is EndsWithFilter,
    is BetweenFilter,
    is IsEmptyFilter,
    is IsEmptyStringFilter,
    is IsNotEmptyStringFilter,
    is IsNullFilter,
    is IsNotNullFilter,
    is ExistsFilter,
    is NotExistsFilter,
    is TodayFilter,
    is BeforeTodayFilter,
    is TomorrowFilter,
    is ThisWeekFilter,
    is NextWeekFilter,
    is LastWeekFilter,
    is ThisMonthFilter,
    is LastMonthFilter,
    is RecentDaysFilter,
    is EarlierDaysFilter,
    is YesterdayFilter,
    is NextMonthFilter,
    is LastYearFilter,
    is ThisYearFilter,
    is NextYearFilter,
    is BeforeNowFilter,
    is AfterNowFilter,
    -> null
}

/**
 * The direct child filters of this node: the operands of `AND` / `OR` / `NOR` and the predicate of
 * `ELEMENT_MATCH`. Leaf nodes return an empty list.
 */
fun FilterExpression.childFilters(): List<FilterExpression> = when (this) {
    is AndFilter -> operands
    is OrFilter -> operands
    is NorFilter -> operands
    is ElementMatchFilter -> listOf(predicate)
    MatchAllFilter,
    MatchNoneFilter,
    is DeletionFilter,
    is SearchFilter,
    is ExpressionFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is EqualFilter,
    is NotEqualFilter,
    is GreaterThanFilter,
    is GreaterThanOrEqualFilter,
    is LessThanFilter,
    is LessThanOrEqualFilter,
    is ContainsFilter,
    is StartsWithFilter,
    is EndsWithFilter,
    is InFilter,
    is NotInFilter,
    is BetweenFilter,
    is ContainsAllFilter,
    is IsEmptyFilter,
    is IsEmptyStringFilter,
    is IsNotEmptyStringFilter,
    is IsNullFilter,
    is IsNotNullFilter,
    is ExistsFilter,
    is NotExistsFilter,
    is TodayFilter,
    is BeforeTodayFilter,
    is TomorrowFilter,
    is ThisWeekFilter,
    is NextWeekFilter,
    is LastWeekFilter,
    is ThisMonthFilter,
    is LastMonthFilter,
    is RecentDaysFilter,
    is EarlierDaysFilter,
    is YesterdayFilter,
    is NextMonthFilter,
    is LastYearFilter,
    is ThisYearFilter,
    is NextYearFilter,
    is BeforeNowFilter,
    is AfterNowFilter,
    -> emptyList()
}

/**
 * Lazily walks every node of these filter trees (roots included) depth-first without recursion, so arbitrarily
 * deep trees cannot overflow the stack. Nodes are visited last-in-first-out: the last root first, and each node
 * before its children. Consumers can stop early, e.g. once a node budget is exceeded.
 */
fun List<FilterExpression>.walkFilterNodes(): Sequence<FilterExpression> =
    walkTree(this, FilterExpression::childFilters)

/** Lazily walks every node of this filter tree; see [walkFilterNodes]. */
fun FilterExpression.walkFilterNodes(): Sequence<FilterExpression> = listOf(this).walkFilterNodes()

/** The total number of nodes in this filter tree, the root included. */
fun FilterExpression.filterNodeCount(): Int = walkFilterNodes().count()

/**
 * The number of literal values carried by this having node: one for `CONDITION`, two for `BETWEEN`, the list
 * size for `IN`, and zero for `IS_NULL` and the logical `AND` / `OR` nodes.
 */
fun HavingExpression.valueCount(): Int = when (this) {
    is HavingExpression.Condition -> 1
    is HavingExpression.Between -> 2
    is HavingExpression.In -> values.size
    is HavingExpression.IsNull -> 0
    is HavingExpression.And -> 0
    is HavingExpression.Or -> 0
}

/** The direct child expressions of this having node: the operands of `AND` / `OR`, otherwise empty. */
fun HavingExpression.childExpressions(): List<HavingExpression> = when (this) {
    is HavingExpression.And -> operands
    is HavingExpression.Or -> operands
    is HavingExpression.Condition,
    is HavingExpression.Between,
    is HavingExpression.In,
    is HavingExpression.IsNull,
    -> emptyList()
}

/** Lazily walks every node of this having tree, the root included, in the same order as [walkFilterNodes]. */
fun HavingExpression.walkHavingNodes(): Sequence<HavingExpression> =
    walkTree(listOf(this), HavingExpression::childExpressions)

/**
 * Whether this metric evaluates anything other than a bare field: a non-field [AggregationExpression]
 * (arithmetic or a constant) on `NUMERIC` / `DISTINCT_COUNT` / `PERCENTILE`, or any `DERIVED` metric.
 * `COUNT`, `ANY`, `FIRST` and `LAST` never carry an expression.
 */
fun AggregationMetric.hasArithmeticExpression(): Boolean = when (this) {
    is AggregationMetric.Numeric -> expression !is AggregationExpression.Field
    is AggregationMetric.DistinctCount -> expression !is AggregationExpression.Field
    is AggregationMetric.Percentile -> expression !is AggregationExpression.Field
    is AggregationMetric.Derived -> true
    is AggregationMetric.Count, is AggregationMetric.Any, is AggregationMetric.Edge -> false
}

private fun <T : Any> walkTree(roots: List<T>, children: (T) -> List<T>): Sequence<T> = sequence {
    val pending = ArrayDeque<T>(roots)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        yield(current)
        pending.addAll(children(current))
    }
}
