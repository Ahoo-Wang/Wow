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

package me.ahoo.wow.query.memory

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
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
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.ResolvedField
import me.ahoo.wow.query.schema.Temporal
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Where a filter or an aggregation input is read: a record, and the elements an aggregation expanded or an
 * `ELEMENT_MATCH` entered, innermost last, each under the absolute physical path of its container.
 */
internal class RecordScope(val record: ObjectNode, private val elements: List<Pair<QueryField, JsonNode>> = emptyList()) {
    fun enter(container: QueryField, element: JsonNode): RecordScope = RecordScope(record, elements + (container to element))

    /** The values [field] reads here: from the element of its physical parent, or from the record at the root. */
    fun values(field: ResolvedField): List<JsonNode> {
        val parent = field.physicalParent ?: return record.valuesAt(field.physicalField)
        val element = elements.lastOrNull { it.first == parent }?.second
            ?: throw QueryExecutionException("Field [${field.logicalField}] is read outside its element scope.")
        return element.valuesAt(field.relativePhysicalField)
    }
}

/** Evaluates admitted filters with the canonical semantics, every operator a schema can grant included. */
internal class InMemoryFilters(private val query: AdmittedQuery<*>) {
    fun matches(filter: FilterExpression, scope: RecordScope): Boolean = when (filter) {
        MatchAllFilter -> true
        MatchNoneFilter -> false
        is AndFilter -> filter.operands.all { matches(it, scope) }
        is OrFilter -> filter.operands.any { matches(it, scope) }
        is NorFilter -> filter.operands.none { matches(it, scope) }
        is IdFilter -> system(filter, scope) == filter.value
        is IdsFilter -> system(filter, scope) in filter.values
        is AggregateIdFilter -> system(filter, scope) == filter.value
        is AggregateIdsFilter -> system(filter, scope) in filter.values
        is TenantIdFilter -> system(filter, scope) == filter.value
        is OwnerIdFilter -> system(filter, scope) == filter.value
        is SpaceIdFilter -> system(filter, scope) == filter.value
        is DeletionFilter -> deletion(filter, scope)
        is ElementMatchFilter -> {
            val container = query.field(filter.field)
            scope.values(container).flattened().filter { it.isObject }
                .any { matches(filter.predicate, scope.enter(container.physicalField, it)) }
        }
        is ExpressionFilter -> expression(filter.expression, scope)?.let { compare(it, filter.comparison, filter.value) } == true
        is SearchFilter -> throw QueryExecutionException("The in-memory backend grants no full-text search.")
        is RelativeTimeFilter -> throw QueryExecutionException("Relative time reaches a backend as a range.")
        else -> field(filter, scope)
    }

    private fun system(filter: FilterExpression, scope: RecordScope): String? =
        scope.record.valuesAt(query.systemField(filter).physicalField).firstOrNull()?.takeIf { it.isString }?.stringValue()

    private fun deletion(filter: DeletionFilter, scope: RecordScope): Boolean {
        val deleted = scope.record.valuesAt(query.systemField(filter).physicalField).firstOrNull()?.booleanValue() == true
        return when (filter.deletionState) {
            DeletionState.ACTIVE -> !deleted
            DeletionState.DELETED -> deleted
            DeletionState.ALL -> true
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun field(filter: FilterExpression, scope: RecordScope): Boolean {
        val field = query.field(checkNotNull((filter as? me.ahoo.wow.api.query.FieldPredicate)?.field))
        val values = scope.values(field)
        return when (filter) {
            is EqualFilter -> values.any { it.matches(filter.value.operandNode()) }
            is NotEqualFilter -> values.none { it.matches(filter.value.operandNode()) }
            is InFilter -> filter.values.map { it.operandNode() }.let { operands ->
                values.any { value -> operands.any { value.matches(it) } }
            }
            is NotInFilter -> filter.values.map { it.operandNode() }.let { operands ->
                values.none { value -> operands.any { value.matches(it) } }
            }
            is ContainsAllFilter -> filter.values.map { it.operandNode() }.all { operand ->
                values.flattened().any { it.sameValue(operand) }
            }
            is GreaterThanFilter -> range(values, field, filter.value) { it > 0 }
            is GreaterThanOrEqualFilter -> range(values, field, filter.value) { it >= 0 }
            is LessThanFilter -> range(values, field, filter.value) { it < 0 }
            is LessThanOrEqualFilter -> range(values, field, filter.value) { it <= 0 }
            is BetweenFilter -> range(values, field, filter.lowerBound) { it >= 0 } &&
                range(values, field, filter.upperBound) { it <= 0 }
            is ContainsFilter -> text(values, filter.stringComparison) { value, operand -> value.contains(operand) }(filter.value)
            is StartsWithFilter -> text(values, filter.stringComparison) { value, operand -> value.startsWith(operand) }(filter.value)
            is EndsWithFilter -> text(values, filter.stringComparison) { value, operand -> value.endsWith(operand) }(filter.value)
            is IsNullFilter -> values.isEmpty() || values.any { it.isNull }
            is IsNotNullFilter -> values.isNotEmpty() && values.none { it.isNull }
            is ExistsFilter -> values.isNotEmpty()
            is NotExistsFilter -> values.isEmpty()
            is IsEmptyFilter -> values.any { it.isArray && it.isEmpty }
            is IsEmptyStringFilter -> values.any { it.isString && it.stringValue().isEmpty() }
            is IsNotEmptyStringFilter -> values.isNotEmpty() && values.none { it.isNull || it.isString && it.stringValue().isEmpty() }
            else -> throw QueryExecutionException("Filter [${filter.operator}] is not a field predicate.")
        }
    }

    /** Whether some stored value orders against [operand] as [accepts] says; a date field compares instants. */
    private fun range(values: List<JsonNode>, field: ResolvedField, operand: Any?, accepts: (Int) -> Boolean): Boolean {
        val bound = operand.operandNode()
        val temporal = field.temporal
        return values.flattened().filterNot { it.isNull }.any { value ->
            val order = if (temporal == Temporal.Date) {
                val left = value.epochMillis(temporal)
                val right = bound.epochMillis(temporal)
                if (left == null || right == null) null else left.compareTo(right)
            } else {
                compareScalars(value, bound)
            }
            order != null && accepts(order)
        }
    }

    private fun text(
        values: List<JsonNode>,
        comparison: StringComparison,
        test: (String, String) -> Boolean,
    ): (String) -> Boolean = { operand ->
        val ignoreCase = comparison == StringComparison.CASE_INSENSITIVE
        values.flattened().filter { it.isString }.any { node ->
            val value = node.stringValue()
            if (ignoreCase) test(value.lowercase(), operand.lowercase()) else test(value, operand)
        }
    }

    /**
     * The value of an aggregation [expression] here, or `null` when a field it reads holds no single number or a
     * division is by zero.
     */
    fun expression(expression: AggregationExpression, scope: RecordScope): Double? = when (expression) {
        is AggregationExpression.Field -> scope.values(query.field(expression.field)).singleOrNull()?.numberOrNull()
        is AggregationExpression.Constant -> expression.value
        is AggregationExpression.Binary -> {
            val left = expression(expression.left, scope)
            val right = expression(expression.right, scope)
            if (left == null || right == null) {
                null
            } else {
                when (expression.operator) {
                    AggregationExpressionOperator.ADD -> left + right
                    AggregationExpressionOperator.SUBTRACT -> left - right
                    AggregationExpressionOperator.MULTIPLY -> left * right
                    AggregationExpressionOperator.DIVIDE -> if (right == 0.0) null else left / right
                }?.takeIf { it.isFinite() }
            }
        }
        is AggregationExpression.DateDiff -> {
            val from = instant(expression.from, scope)
            val to = instant(expression.to, scope)
            if (from == null || to == null) null else (to - from).toDouble() / expression.unit.millis
        }
    }

    /** The instant [field] holds here, in epoch milliseconds. */
    fun instant(field: QueryField, scope: RecordScope): Long? {
        val resolved = query.field(field)
        return scope.values(resolved).singleOrNull()?.epochMillis(resolved.temporal)
    }

    companion object {
        fun compare(left: Double, operator: ComparisonOperator, right: Double): Boolean = when (operator) {
            ComparisonOperator.EQ -> left == right
            ComparisonOperator.NE -> left != right
            ComparisonOperator.GT -> left > right
            ComparisonOperator.GTE -> left >= right
            ComparisonOperator.LT -> left < right
            ComparisonOperator.LTE -> left <= right
        }
    }
}
