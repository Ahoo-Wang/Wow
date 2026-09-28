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

import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.childFilters
import me.ahoo.wow.api.query.spec.OperatorCost
import me.ahoo.wow.api.query.spec.ValueArity
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
 * Whether this filter provably matches every document, as its operator's
 * [me.ahoo.wow.api.query.spec.FilterOperatorSpec.matchesAll] states: [MatchAllFilter], a [DeletionFilter] for
 * [DeletionState.ALL], an [AndFilter] whose operands all match everything, or an [OrFilter] with at least one
 * operand that matches everything. Any other filter is treated as restrictive.
 */
fun FilterExpression.isMatchAll(): Boolean = spec.matchesAll(this)

/**
 * The number of literal values carried by a multi-value filter node (`IN`, `NOT_IN`, `CONTAINS_ALL`, `IDS`,
 * `AGGREGATE_IDS`: the operators whose [value arity][me.ahoo.wow.api.query.spec.Arity.values] is a list), or `null`
 * for nodes that do not carry a value list.
 */
fun FilterExpression.valueCount(): Int? = if (spec.arity.values == ValueArity.LIST) spec.valueCount(this) else null

/**
 * Lazily walks every node of these filter trees (roots included) depth-first without recursion, so arbitrarily
 * deep trees cannot overflow the stack. Nodes are visited last-in-first-out: the last root first, and each node
 * before its children. Consumers can stop early, e.g. once a node budget is exceeded.
 */
fun List<FilterExpression>.walkFilterNodes(): Sequence<FilterExpression> =
    walkTree(this, FilterExpression::childFilters)

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
internal fun HavingExpression.childExpressions(): List<HavingExpression> = when (this) {
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
