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
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.spec.FilterOperatorSpec
import me.ahoo.wow.api.query.spec.OperatorTarget
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.api.query.spec.ValueRule
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.filter.childFilters
import me.ahoo.wow.query.filter.predicateField
import me.ahoo.wow.query.filter.requiredCapability
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
    ): QueryFieldSchema = field(name, setOf(capability), parent)

    private fun field(
        name: QueryField,
        capabilities: Set<QueryCapability>,
        parent: QueryField?,
    ): QueryFieldSchema {
        val logical = absoluteLogicalField(name, parent)
        val field = schema.field(logical) ?: throw QuerySchemaValidationException(QueryViolation.UnknownField(logical))
        requireValid(capabilities.any { field.binding(it) != null }) {
            QueryViolation.UnsupportedCapability(logical, capabilities)
        }
        requireValid(
            field.elementAncestors != null && field.elementAncestors == schema.requiredElementAncestors(parent)
        ) {
            QueryViolation.ElementScopeRequired(logical)
        }
        return field
    }

    fun filter(expression: FilterExpression, parent: QueryField? = null) {
        val spec = expression.spec
        when (spec.target) {
            OperatorTarget.NONE -> Unit
            OperatorTarget.LOGICAL -> expression.childFilters().forEach { filter(it, parent) }
            OperatorTarget.SYSTEM_FIELD -> field(
                systemField(checkNotNull(spec.systemField)),
                expression.requiredCapability(),
                parent,
            )
            OperatorTarget.MODEL_OR_FIELDS -> search(
                expression as SearchFilter,
                expression.requiredCapability(),
                parent,
            )
            OperatorTarget.FIELD -> predicate(expression, spec, parent)
        }
    }

    private fun predicate(expression: FilterExpression, spec: FilterOperatorSpec, parent: QueryField?) {
        val name = checkNotNull(expression.predicateField())
        val capability = expression.requiredCapability()
        when (spec.valueRule) {
            ValueRule.NONE -> field(name, capability, parent)
            ValueRule.DOMAIN -> {
                val values = domainValues(expression)
                if (values == null) field(name, capability, parent) else values(name, capability, values, parent)
            }
            ValueRule.COLLECTION_DOMAIN -> {
                collection(name, capability, parent)
                values(name, capability, checkNotNull(domainValues(expression)), parent)
            }
            ValueRule.COLLECTION -> collection(name, capability, parent)
            ValueRule.SINGLE_STRING -> string(name, capability, parent)
            ValueRule.TEMPORAL -> (expression as RelativeTimeFilter).temporal(field(name, capability, parent).value)
            ValueRule.ELEMENT_SCOPE -> {
                val container = field(name, capability, parent)
                filter((expression as ElementMatchFilter).predicate, container.logicalField)
            }
        }
    }

    private fun systemField(field: SystemField): QueryField = when (field) {
        SystemField.IDENTITY -> schema.requireIdentityField()
        SystemField.AGGREGATE_ID -> QueryField(MessageRecords.AGGREGATE_ID)
        SystemField.TENANT_ID -> QueryField(MessageRecords.TENANT_ID)
        SystemField.OWNER_ID -> QueryField(MessageRecords.OWNER_ID)
        SystemField.SPACE_ID -> QueryField(MessageRecords.SPACE_ID)
        SystemField.DELETED -> QueryField(StateAggregateRecords.DELETED)
    }

    private fun search(expression: SearchFilter, capability: QueryCapability, parent: QueryField?) {
        if (expression.fields.isEmpty()) {
            requireValid(schema.supports(capability)) { QueryViolation.ModelSearchUnsupported }
        } else {
            expression.fields.forEach { field(it, capability, parent) }
        }
    }

    /** The values checked against the field's domain; `null` when equality asks for presence instead. */
    private fun domainValues(expression: FilterExpression): Iterable<JsonNode>? = when (expression) {
        is EqualFilter -> equalityValues(expression.value)
        is NotEqualFilter -> equalityValues(expression.value)
        is InFilter -> expression.values
        is NotInFilter -> expression.values
        is ContainsAllFilter -> expression.values
        is GreaterThanFilter -> listOf(expression.value)
        is GreaterThanOrEqualFilter -> listOf(expression.value)
        is LessThanFilter -> listOf(expression.value)
        is LessThanOrEqualFilter -> listOf(expression.value)
        is BetweenFilter -> listOf(expression.lowerBound, expression.upperBound)
        else -> error("Filter [${expression.operator}] carries no domain values.")
    }

    private fun equalityValues(value: JsonNode): Iterable<JsonNode>? {
        if (value.isNull) return null
        val canonical = if (value is POJONode) JsonSerializer.valueToTree<JsonNode>(value.pojo) else value
        return if (canonical.isArray) canonical else listOf(canonical)
    }

    private fun values(name: QueryField, capability: QueryCapability, values: Iterable<JsonNode>, parent: QueryField?) {
        requireValid(field(name, capability, parent).value.accepts(values)) { QueryViolation.ValueMismatch(name) }
    }

    private fun collection(name: QueryField, capability: QueryCapability, parent: QueryField?) {
        val value = field(name, capability, parent).value
        val alternatives = value.alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
        requireValid(alternatives.isNotEmpty() && alternatives.all { it.kind == QueryValueKind.ARRAY }) {
            QueryViolation.NotCollection(name)
        }
    }

    private fun string(name: QueryField, capability: QueryCapability, parent: QueryField?) {
        val value = field(name, capability, parent).value
        requireValid(
            value.cardinality == QueryCardinality.SINGLE && value.operationValues().all {
                it.kind == QueryValueKind.NULL || it.valueTypes == setOf(QueryValueType.STRING)
            }
        ) { QueryViolation.NotSingleString(name) }
    }

    fun projection(projection: Projection) {
        requireValid(projection.include.isNotEmpty() || schema.fullProjectionAvailable) {
            QueryViolation.IncompleteProjection
        }
        (projection.include + projection.exclude).forEach { schema.projectionField(it) }
        schema.profile?.validateProjection(projection)
    }

    fun sort(sort: List<Sort>, cursor: Boolean = false) {
        val capability = if (cursor) QueryCapability.CURSOR_SORT else QueryCapability.SORT
        sort.forEach {
            val field = field(it.field, capability)
            if (cursor) {
                requireValid(field.cursorSortable) { QueryViolation.CursorNotAllowed(field.logicalField) }
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
            val field = aggregationField(group.field, setOf(capability), parent)
            requireTermsMissingKeySupport(group, field)
        }
        query.metrics.forEach { metric ->
            if (metric.filter !== MatchAllFilter) {
                filter(metric.filter, parent)
                metric.filter.requireScalarMetricFilterFields(parent, schema)
            }
            when (metric) {
                is AggregationMetric.Count -> Unit
                is AggregationMetric.Derived -> Unit
                is AggregationMetric.Any -> requireValid(
                    aggregationField(
                        metric.field,
                        setOf(QueryCapability.AGGREGATE_TERMS),
                        parent,
                    ).value.cardinality == QueryCardinality.SINGLE,
                ) { QueryViolation.AnyRequiresSingleValue }
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
                setOf(QueryCapability.AGGREGATE_NUMERIC),
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

    private fun requireTermsMissingKeySupport(group: AggregationGroup, field: QueryFieldSchema) {
        if (group is AggregationGroup.Terms && group.missingKey != null) {
            val value = field.value
            requireValid(
                value.cardinality == QueryCardinality.SINGLE &&
                    value.operationValues().all {
                        it.kind == QueryValueKind.NULL || it.valueTypes == setOf(QueryValueType.STRING)
                    },
            ) { QueryViolation.MissingKeyRequiresString(field.logicalField) }
        }
    }

    private fun aggregationField(
        name: QueryField,
        capabilities: Set<QueryCapability>,
        parent: QueryField?,
    ): QueryFieldSchema {
        val field = field(name, capabilities, parent)
        requireValid(!field.protected) { QueryViolation.ProtectedAggregation(field.logicalField) }
        return field
    }
}
