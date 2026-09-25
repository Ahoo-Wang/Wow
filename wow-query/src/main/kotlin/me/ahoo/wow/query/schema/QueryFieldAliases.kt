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

import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.filter.predicateField
import me.ahoo.wow.query.filter.withPredicateField

/*
 * Admission replaces every field alias a query uses with its canonical field (design §6.3), before validation, so
 * results, sort uniqueness, protection and cursor fingerprints only ever see canonical names. A schema without
 * aliases returns each query unchanged.
 */

/** This query with its aliased fields replaced by their canonical fields. */
fun ISingleQuery.withCanonicalFields(schema: QueryModelSchema): ISingleQuery {
    if (!schema.hasAliases) return this
    val aliases = QueryFieldAliases(schema.definition)
    return SingleQuery(aliases.filter(filter, null), aliases.projection(projection), aliases.sort(sort))
}

/** This query with its aliased fields replaced by their canonical fields. */
fun IListQuery.withCanonicalFields(schema: QueryModelSchema): IListQuery {
    if (!schema.hasAliases) return this
    val aliases = QueryFieldAliases(schema.definition)
    return ListQuery(aliases.filter(filter, null), aliases.projection(projection), aliases.sort(sort), limit)
}

/** This query with its aliased fields replaced by their canonical fields. */
fun IPagedQuery.withCanonicalFields(schema: QueryModelSchema): IPagedQuery {
    if (!schema.hasAliases) return this
    val aliases = QueryFieldAliases(schema.definition)
    return PagedQuery(aliases.filter(filter, null), aliases.projection(projection), aliases.sort(sort), pagination)
}

/** This query with its aliased fields replaced by their canonical fields. */
fun ICursorQuery.withCanonicalFields(schema: QueryModelSchema): ICursorQuery {
    if (!schema.hasAliases) return this
    val aliases = QueryFieldAliases(schema.definition)
    return CursorQuery(aliases.filter(filter, null), aliases.projection(projection), aliases.sort(sort), size, cursor)
}

/** This filter with its aliased fields replaced by their canonical fields. */
fun FilterExpression.withCanonicalFields(schema: QueryModelSchema): FilterExpression =
    if (!schema.hasAliases) this else QueryFieldAliases(schema.definition).filter(this, null)

/** This aggregation with its aliased fields replaced by their canonical fields; aliases of results are kept. */
fun AggregationQuery.withCanonicalFields(schema: QueryModelSchema): AggregationQuery =
    if (!schema.hasAliases) this else QueryFieldAliases(schema.definition).aggregation(this)

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
        else -> expression.predicateField()?.let { expression.withPredicateField(field(it, parent)) } ?: expression
    }

    fun projection(projection: Projection): Projection = Projection(
        include = projection.include.map { field(it, null) },
        exclude = projection.exclude.map { field(it, null) },
    )

    fun sort(sort: List<Sort>): List<Sort> = sort.map { it.copy(field = field(it.field, null)) }

    fun aggregation(query: AggregationQuery): AggregationQuery {
        var parent: QueryField? = null
        val elements = query.elements.map { element ->
            val path = field(element.path, parent)
            val container = absolute(path, parent)
            AggregationElement(path, filter(element.filter, container)).also { parent = container }
        }
        return query.copy(
            filter = filter(query.filter, null),
            elements = elements,
            groupBy = query.groupBy.map { group(it, parent) },
            metrics = query.metrics.map { metric(it, parent) },
        )
    }

    private fun group(group: AggregationGroup, parent: QueryField?): AggregationGroup = when (group) {
        is AggregationGroup.Terms -> group.copy(field = field(group.field, parent))
        is AggregationGroup.Histogram -> group.copy(field = field(group.field, parent))
        is AggregationGroup.DateHistogram -> group.copy(field = field(group.field, parent))
        is AggregationGroup.DatePart -> group.copy(field = field(group.field, parent))
    }

    private fun metric(metric: AggregationMetric, parent: QueryField?): AggregationMetric = when (metric) {
        is AggregationMetric.Count -> metric.copy(filter = filter(metric.filter, parent))
        is AggregationMetric.Numeric -> metric.copy(
            expression = expression(metric.expression, parent),
            filter = filter(metric.filter, parent),
        )
        is AggregationMetric.Any -> metric.copy(
            field = field(metric.field, parent),
            filter = filter(metric.filter, parent)
        )
        is AggregationMetric.DistinctCount -> metric.copy(
            expression = expression(metric.expression, parent),
            filter = filter(metric.filter, parent),
        )
        is AggregationMetric.Percentile -> metric.copy(
            expression = expression(metric.expression, parent),
            filter = filter(metric.filter, parent),
        )
        is AggregationMetric.First -> metric.copy(
            field = field(metric.field, parent),
            orderBy = metric.orderBy?.let { field(it, parent) },
            filter = filter(metric.filter, parent),
        )
        is AggregationMetric.Last -> metric.copy(
            field = field(metric.field, parent),
            orderBy = metric.orderBy?.let { field(it, parent) },
            filter = filter(metric.filter, parent),
        )
        is AggregationMetric.Derived -> metric
    }

    private fun expression(expression: AggregationExpression, parent: QueryField?): AggregationExpression =
        when (expression) {
            is AggregationExpression.Field -> AggregationExpression.Field(field(expression.field, parent))
            is AggregationExpression.Constant -> expression
            is AggregationExpression.Binary -> expression.copy(
                left = expression(expression.left, parent),
                right = expression(expression.right, parent),
            )
        }

    private fun absolute(field: QueryField, parent: QueryField?): QueryField = parent?.append(field) ?: field
}
