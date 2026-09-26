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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.query.filter.predicateField
import me.ahoo.wow.query.filter.withPredicateField

/**
 * This filter with its aliased fields replaced by their canonical fields (design §6.3), for a filter evaluated without
 * admission's resolution pass (a point read's restriction). Admitted queries are canonicalized by that pass itself.
 * A schema without aliases returns the filter unchanged.
 */
fun FilterExpression.withCanonicalFields(schema: QueryModelSchema): FilterExpression =
    if (!schema.hasAliases) this else QueryFieldAliases(schema.definition).filter(this, null)

private class QueryFieldAliases(private val definition: LogicalQuerySchema) {
    /** The canonical form of [field], which is relative to [parent] (an absolute canonical field) when given. */
    fun field(field: QueryField, parent: QueryField?): QueryField {
        if (parent == null) return definition.canonical(field)
        val canonical = definition.canonical(parent.append(field))
        return canonical.relativeTo(parent) ?: field
    }

    fun filter(expression: FilterExpression, parent: QueryField?): FilterExpression = when (expression) {
        is AndFilter -> AndFilter(expression.operands.map { filter(it, parent) })
        is OrFilter -> OrFilter(expression.operands.map { filter(it, parent) })
        is NorFilter -> NorFilter(expression.operands.map { filter(it, parent) })
        is ElementMatchFilter -> {
            val container = field(expression.field, parent)
            ElementMatchFilter(container, filter(expression.predicate, absolute(container, parent)))
        }
        is SearchFilter -> expression.copy(fields = expression.fields.mapTo(linkedSetOf()) { field(it, parent) })
        is ExpressionFilter -> expression.copy(expression = expression(expression.expression, parent))
        else -> expression.predicateField()?.let { expression.withPredicateField(field(it, parent)) } ?: expression
    }

    private fun expression(expression: AggregationExpression, parent: QueryField?): AggregationExpression =
        when (expression) {
            is AggregationExpression.Field -> AggregationExpression.Field(field(expression.field, parent))
            is AggregationExpression.Constant -> expression
            is AggregationExpression.Binary -> expression.copy(
                left = expression(expression.left, parent),
                right = expression(expression.right, parent),
            )
            is AggregationExpression.DateDiff -> expression.copy(
                from = field(expression.from, parent),
                to = field(expression.to, parent),
            )
        }

    private fun absolute(field: QueryField, parent: QueryField?): QueryField = parent?.append(field) ?: field
}
