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

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
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
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode

fun validateQuery(query: ISingleQuery, schema: QueryModelSchema): ISingleQuery = query.also {
    QueryValidator(schema).apply {
        filter(it.filter)
        projection(it.projection)
        sort(it.sort)
    }
}
fun validateQuery(query: IListQuery, schema: QueryModelSchema): IListQuery = query.also {
    QueryValidator(schema).apply {
        filter(it.filter)
        projection(it.projection)
        sort(it.sort)
    }
}
fun validateQuery(query: IPagedQuery, schema: QueryModelSchema): IPagedQuery = query.also {
    QueryValidator(schema).apply {
        filter(it.filter)
        projection(it.projection)
        sort(it.sort)
    }
}
fun validateQuery(query: ICursorQuery, schema: QueryModelSchema): ICursorQuery = query.also {
    QueryValidator(schema).apply {
        filter(it.filter)
        projection(it.projection)
        sort(it.sort, cursor = true)
    }
}
fun validateQuery(query: FilterExpression, schema: QueryModelSchema): FilterExpression = query.also {
    QueryValidator(schema).filter(it)
}
fun validateQuery(query: AggregationQuery, schema: QueryModelSchema): AggregationQuery = query.also {
    QueryValidator(schema).aggregate(it)
}
fun validateQuery(projection: Projection, schema: QueryModelSchema): Projection = projection.also {
    QueryValidator(schema).projection(it)
}

/** Per-invocation public checks, never retained by a schema and never producing physical AST. */
private class QueryValidator(private val schema: QueryModelSchema) {
    private fun field(
        name: QueryField,
        capability: QueryCapability,
        parent: QueryField? = null,
    ): QueryFieldSchema = field(name, setOf(capability), capability.toString(), parent)

    private fun field(
        name: QueryField,
        capabilities: Set<QueryCapability>,
        label: String,
        parent: QueryField?,
    ): QueryFieldSchema {
        val logical = absoluteLogicalField(name, parent)
        val field = schema.field(logical) ?: throw QuerySchemaValidationException("Unknown logical field [$logical].")
        requireSchema(capabilities.any { field.binding(it) != null }) { "Field [$logical] does not support [$label]." }
        requireSchema(
            field.elementAncestors != null && field.elementAncestors == schema.requiredElementAncestors(parent)
        ) {
            "Field [$logical] requires its declared element scope."
        }
        return field
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun filter(expression: FilterExpression, parent: QueryField? = null) {
        when (expression) {
            MatchAllFilter, MatchNoneFilter -> Unit
            is IdFilter, is IdsFilter -> metadata(
                if (schema.model == QueryModel.EVENT_STREAM) MessageRecords.ID else MessageRecords.AGGREGATE_ID
            )
            is AggregateIdFilter, is AggregateIdsFilter -> metadata(MessageRecords.AGGREGATE_ID)
            is TenantIdFilter -> metadata(MessageRecords.TENANT_ID)
            is OwnerIdFilter -> metadata(MessageRecords.OWNER_ID)
            is SpaceIdFilter -> metadata(MessageRecords.SPACE_ID)
            is DeletionFilter -> metadata(StateAggregateRecords.DELETED)
            is AndFilter -> expression.operands.forEach { filter(it, parent) }
            is OrFilter -> expression.operands.forEach { filter(it, parent) }
            is NorFilter -> expression.operands.forEach { filter(it, parent) }
            is EqualFilter -> equality(expression.field, expression.value, parent)
            is NotEqualFilter -> equality(expression.field, expression.value, parent)
            is InFilter -> values(expression.field, QueryCapability.EXACT_MATCH, expression.values, parent)
            is NotInFilter -> values(expression.field, QueryCapability.EXACT_MATCH, expression.values, parent)
            is ContainsAllFilter -> {
                collection(expression.field, QueryCapability.EXACT_MATCH, parent)
                values(expression.field, QueryCapability.EXACT_MATCH, expression.values, parent)
            }
            is ContainsFilter -> field(expression.field, QueryCapability.LITERAL_MATCH, parent)
            is StartsWithFilter -> field(expression.field, QueryCapability.LITERAL_MATCH, parent)
            is EndsWithFilter -> field(expression.field, QueryCapability.LITERAL_MATCH, parent)
            is GreaterThanFilter -> values(expression.field, QueryCapability.RANGE, listOf(expression.value), parent)
            is GreaterThanOrEqualFilter -> values(
                expression.field,
                QueryCapability.RANGE,
                listOf(expression.value),
                parent
            )
            is LessThanFilter -> values(expression.field, QueryCapability.RANGE, listOf(expression.value), parent)
            is LessThanOrEqualFilter -> values(
                expression.field,
                QueryCapability.RANGE,
                listOf(expression.value),
                parent
            )
            is BetweenFilter -> values(
                expression.field,
                QueryCapability.RANGE,
                listOf(expression.lowerBound, expression.upperBound),
                parent
            )
            is IsEmptyFilter -> collection(expression.field, QueryCapability.PRESENCE, parent)
            is IsEmptyStringFilter -> string(expression.field, parent)
            is IsNotEmptyStringFilter -> string(expression.field, parent)
            is IsNullFilter -> field(expression.field, QueryCapability.PRESENCE, parent)
            is IsNotNullFilter -> field(expression.field, QueryCapability.PRESENCE, parent)
            is ExistsFilter -> field(expression.field, QueryCapability.PRESENCE, parent)
            is NotExistsFilter -> field(expression.field, QueryCapability.PRESENCE, parent)
            is RelativeTimeFilter -> expression.temporal(field(expression.field, QueryCapability.RANGE, parent).value)
            is SearchFilter -> {
                val capability = if (expression.mode == SearchMode.TERMS) QueryCapability.FULL_TEXT_TERMS else QueryCapability.FULL_TEXT_PHRASE
                if (expression.fields.isEmpty()) {
                    requireSchema(schema.supports(capability)) { "Model search is unsupported." }
                } else {
                    expression.fields.forEach { field(it, capability, parent) }
                }
            }
            is ElementMatchFilter -> {
                val container = field(expression.field, QueryCapability.ELEMENT_SCOPE, parent)
                filter(expression.predicate, container.logicalField)
            }
        }
    }

    private fun metadata(name: String) { field(QueryField(name), QueryCapability.EXACT_MATCH) }

    private fun equality(name: QueryField, value: JsonNode, parent: QueryField?) {
        if (value.isNull) {
            field(name, QueryCapability.PRESENCE, parent)
            return
        }
        val canonical = if (value is POJONode) JsonSerializer.valueToTree<JsonNode>(value.pojo) else value
        values(name, QueryCapability.EXACT_MATCH, if (canonical.isArray) canonical else listOf(canonical), parent)
    }

    private fun values(name: QueryField, capability: QueryCapability, values: Iterable<JsonNode>, parent: QueryField?) {
        requireSchema(field(name, capability, parent).value.accepts(values)) { "Filter value does not match [$name]." }
    }

    private fun collection(name: QueryField, capability: QueryCapability, parent: QueryField?) {
        val value = field(name, capability, parent).value
        val alternatives = value.alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
        requireSchema(alternatives.isNotEmpty() && alternatives.all { it.kind == QueryValueKind.ARRAY }) {
            "Field [$name] is not a known collection."
        }
    }

    private fun string(name: QueryField, parent: QueryField?) {
        val value = field(name, QueryCapability.EXACT_MATCH, parent).value
        requireSchema(
            value.cardinality == QueryCardinality.SINGLE && value.operationValues().all {
                it.kind == QueryValueKind.NULL || it.valueTypes == setOf(QueryValueType.STRING)
            }
        ) { "Field [$name] is not a single string." }
    }

    fun projection(projection: Projection) {
        requireSchema(projection.include.isNotEmpty() || schema.fullProjectionAvailable) {
            "Native storage cannot deliver a complete source projection; select available fields explicitly."
        }
        (projection.include + projection.exclude).forEach { schema.projectionField(it) }
        if (schema.model != QueryModel.EVENT_STREAM) return
        val payload = QueryField("body.body")
        val type = QueryField("body.bodyType")
        fun QueryField.selects(other: QueryField) = this == other || other.relativeTo(this) != null
        val payloadSelected = projection.include.isEmpty() || projection.include.any {
            it.selects(payload) || payload.selects(it)
        }
        val payloadExcluded = projection.exclude.any { it.selects(payload) }
        val typeSelected = projection.include.isEmpty() || projection.include.any { it.selects(type) }
        requireSchema(
            !payloadSelected || payloadExcluded || typeSelected && projection.exclude.none { it.selects(type) }
        ) {
            "Event payload projection must retain bodyType."
        }
    }

    fun sort(sort: List<Sort>, cursor: Boolean = false) {
        val capability = if (cursor) QueryCapability.CURSOR_SORT else QueryCapability.SORT
        sort.forEach {
            val field = field(it.field, capability)
            if (cursor) {
                requireSchema(isCursorFieldAllowed(schema, field.logicalField, field)) {
                    "Field [${field.logicalField}] cannot be used for a cursor."
                }
            }
        }
    }

    fun aggregate(query: AggregationQuery) {
        filter(query.filter)
        var parent: QueryField? = null
        query.elements.forEach { element ->
            val container = field(element.path, QueryCapability.ELEMENT_SCOPE, parent)
            parent = container.logicalField
            filter(element.filter, parent)
        }
        query.groupBy.forEach { group ->
            val capability = when (group) {
                is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
                is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
                is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
            }
            aggregationField(group.field, capability, parent)
        }
        query.metrics.forEach { metric ->
            when (metric) {
                is AggregationMetric.Count -> Unit
                is AggregationMetric.Any -> requireSchema(
                    aggregationField(metric.field, QueryCapability.AGGREGATE_TERMS, parent).value.cardinality == QueryCardinality.SINGLE,
                ) { "ANY requires a single value." }
                is AggregationMetric.Numeric -> expression(metric.expression, parent)
                is AggregationMetric.DistinctCount -> distinctCountExpression(metric.expression, parent)
                is AggregationMetric.Percentile -> expression(metric.expression, parent)
            }
        }
    }

    private fun expression(expression: AggregationExpression, parent: QueryField?) {
        when (expression) {
            is AggregationExpression.Field -> aggregationField(
                expression.field,
                QueryCapability.AGGREGATE_NUMERIC,
                parent
            )
            is AggregationExpression.Constant -> Unit
            is AggregationExpression.Binary -> {
                expression(expression.left, parent)
                expression(expression.right, parent)
            }
        }
    }

    private fun distinctCountExpression(expression: AggregationExpression, parent: QueryField?) {
        when (expression) {
            is AggregationExpression.Field -> aggregationField(
                expression.field,
                setOf(QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC),
                parent,
            )
            is AggregationExpression.Constant -> Unit
            is AggregationExpression.Binary -> {
                expression(expression.left, parent)
                expression(expression.right, parent)
            }
        }
    }

    private fun aggregationField(name: QueryField, capability: QueryCapability, parent: QueryField?): QueryFieldSchema {
        val field = field(name, capability, parent)
        requireSchema(!isFieldProtected(schema, field.logicalField, field)) {
            "Protected field [${field.logicalField}] cannot be aggregated."
        }
        return field
    }

    private fun aggregationField(
        name: QueryField,
        capabilities: Set<QueryCapability>,
        parent: QueryField?,
    ): QueryFieldSchema {
        val field = field(name, capabilities, capabilities.joinToString(" or "), parent)
        requireSchema(!isFieldProtected(schema, field.logicalField, field)) {
            "Protected field [${field.logicalField}] cannot be aggregated."
        }
        return field
    }
}
