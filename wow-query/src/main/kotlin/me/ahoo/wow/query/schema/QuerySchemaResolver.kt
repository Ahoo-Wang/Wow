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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.FORBIDDEN_CURSOR_SORTS

private val EVENT_BODY_PAYLOAD_FIELD = QueryField("body.body")
private val EVENT_BODY_TYPE_FIELD = QueryField("body.bodyType")

private fun QueryField.selects(target: QueryField): Boolean =
    this == target || target.relativeTo(this) != null

private fun QueryField.intersects(target: QueryField): Boolean =
    selects(target) || target.selects(this)

enum class QuerySchemaValidationMode {
    COMPATIBLE,
    STRICT,
    ;

    fun accepts(compatibility: QueryCompatibilityLevel): Boolean = when (this) {
        COMPATIBLE -> compatibility != QueryCompatibilityLevel.INCOMPATIBLE
        STRICT -> compatibility == QueryCompatibilityLevel.EXACT
    }
}

data class QuerySchemaResolution<T>(
    val value: T,
    val compatibility: QueryCompatibilityLevel,
)

fun <T> QuerySchemaResolution<T>.requireAccepted(mode: QuerySchemaValidationMode): T {
    if (!mode.accepts(compatibility)) {
        throw QuerySchemaValidationException(
            "Query compatibility [$compatibility] is rejected by mode [$mode].",
        )
    }
    return value
}

internal class QuerySchemaResolver(private val schema: QueryModelSchema) {
    private val fieldResolver = schema.fieldResolver
    private val filterResolver = QueryFilterSchemaResolver(schema, fieldResolver)
    private val maskedAggregationPaths = schema.maskedFields.flatMapTo(linkedSetOf()) { (logical, field) ->
        listOfNotNull(logical.path, field.projectionField?.path) + field.bindings.values.flatMap {
            listOf(it.resolvedField.path, it.physicalField.path)
        }
    }
    private val maskedProjectionPaths = schema.maskedFields.values.mapNotNullTo(hashSetOf()) { field ->
        field.projectionField?.path
    }
    private val maskedPhysicalPaths = schema.maskedFields.values.flatMapTo(hashSetOf()) { field ->
        field.bindings.values.flatMap { listOf(it.resolvedField.path, it.physicalField.path) }
    }

    fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            if (
                schema.rewriteMode == QueryRewriteMode.NONE ||
                filter.value === query.filter && sort.value === query.sort
            ) {
                query
            } else {
                SingleQuery(filter.value, projection.value, sort.value)
            },
            maxOf(filter.compatibility, projection.compatibility, sort.compatibility),
        )
    }

    fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            if (
                schema.rewriteMode == QueryRewriteMode.NONE ||
                filter.value === query.filter && sort.value === query.sort
            ) {
                query
            } else {
                ListQuery(filter.value, projection.value, sort.value, query.limit)
            },
            maxOf(filter.compatibility, projection.compatibility, sort.compatibility),
        )
    }

    fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            if (
                schema.rewriteMode == QueryRewriteMode.NONE ||
                filter.value === query.filter && sort.value === query.sort
            ) {
                query
            } else {
                PagedQuery(filter.value, projection.value, sort.value, query.pagination)
            },
            maxOf(filter.compatibility, projection.compatibility, sort.compatibility),
        )
    }

    fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolveCursorSort(query.sort)
        return QuerySchemaResolution(
            if (
                schema.rewriteMode == QueryRewriteMode.NONE ||
                filter.value === query.filter && sort.value === query.sort
            ) {
                query
            } else {
                CursorQuery(filter.value, projection.value, sort.value, query.size, query.cursor)
            },
            maxOf(filter.compatibility, projection.compatibility, sort.compatibility),
        )
    }

    fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression> =
        filterResolver.resolve(filter)

    fun resolve(projection: Projection): QuerySchemaResolution<Projection> {
        if (projection.isEmpty()) {
            return QuerySchemaResolution(projection, QueryCompatibilityLevel.EXACT)
        }
        var compatibility = QueryCompatibilityLevel.EXACT
        projection.include.forEach { field ->
            compatibility = maxOf(compatibility, fieldResolver.resolveProjection(field).compatibility)
        }
        projection.exclude.forEach { field ->
            compatibility = maxOf(compatibility, fieldResolver.resolveProjection(field).compatibility)
        }
        val payloadSelected = projection.include.isEmpty() ||
            projection.include.any { it.intersects(EVENT_BODY_PAYLOAD_FIELD) }
        val payloadExcluded = projection.exclude.any { it.selects(EVENT_BODY_PAYLOAD_FIELD) }
        val bodyTypeSelected = projection.include.isEmpty() ||
            projection.include.any { it.selects(EVENT_BODY_TYPE_FIELD) }
        val bodyTypeExcluded = projection.exclude.any { it.selects(EVENT_BODY_TYPE_FIELD) }
        val eventProjectionAccepted = !payloadSelected || payloadExcluded ||
            bodyTypeSelected && !bodyTypeExcluded
        return QuerySchemaResolution(
            projection,
            if (schema.model == QueryModel.EVENT_STREAM && !eventProjectionAccepted) {
                QueryCompatibilityLevel.INCOMPATIBLE
            } else {
                compatibility
            },
        )
    }

    fun resolve(sort: List<Sort>): QuerySchemaResolution<List<Sort>> {
        if (sort.isEmpty()) {
            return QuerySchemaResolution(sort, QueryCompatibilityLevel.EXACT)
        }
        var values: ArrayList<Sort>? = null
        var compatibility = QueryCompatibilityLevel.EXACT
        sort.forEachIndexed { index, item ->
            val field = fieldResolver.resolve(item.field, QueryCapability.SORT, null, null, null)
            val value = if (schema.rewriteMode == QueryRewriteMode.NONE || field.value === item.field) {
                item
            } else {
                item.copy(field = field.value)
            }
            if (values != null) {
                values += value
            } else if (value !== item) {
                values = ArrayList<Sort>(sort.size).apply {
                    addAll(sort.subList(0, index))
                    add(value)
                }
            }
            compatibility = maxOf(compatibility, field.compatibility)
        }
        return QuerySchemaResolution(values ?: sort, compatibility)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun resolveCursorSort(sort: List<Sort>): QuerySchemaResolution<List<Sort>> {
        if (sort.isEmpty()) {
            return QuerySchemaResolution(sort, QueryCompatibilityLevel.EXACT)
        }
        var values: ArrayList<Sort>? = null
        val physicalFields = HashSet<QueryField>(sort.size)
        var compatibility = QueryCompatibilityLevel.EXACT
        sort.forEachIndexed { index, item ->
            val field = fieldResolver.resolve(item.field, QueryCapability.SORT, null, null, null)
            val physicalField = field.physicalField ?: field.value
            val accepted = field.compatibility == QueryCompatibilityLevel.EXACT &&
                field.fieldSchema != null && field.fieldSchema.cardinality == QueryCardinality.SINGLE &&
                field.fieldSchema.maskRule == null && physicalField !in FORBIDDEN_CURSOR_SORTS &&
                !field.matchesMaskedCandidate()
            val value = if (schema.rewriteMode == QueryRewriteMode.NONE || field.value === item.field) {
                item
            } else {
                item.copy(field = field.value)
            }
            if (values != null) {
                values += value
            } else if (value !== item) {
                values = ArrayList<Sort>(sort.size).apply {
                    addAll(sort.subList(0, index))
                    add(value)
                }
            }
            if (!accepted || !physicalFields.add(physicalField)) {
                compatibility = QueryCompatibilityLevel.INCOMPATIBLE
            }
        }
        return QuerySchemaResolution(values ?: sort, compatibility)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery> {
        val rootFilter = resolve(query.filter)
        var compatibility = rootFilter.compatibility
        var logicalParent: QueryField? = null
        var resolvedParent: QueryField? = null
        var physicalParent: QueryField? = null
        var elements: ArrayList<AggregationElement>? = null
        query.elements.forEachIndexed { index, element ->
            val container = resolveAggregationField(
                element.path,
                QueryCapability.ELEMENT_SCOPE,
                logicalParent,
                resolvedParent,
                physicalParent,
            )
            compatibility = maxOf(compatibility, container.compatibility)
            val filter = filterResolver.resolve(
                element.filter,
                container.logical,
                container.resolvedField,
                container.physicalField,
            )
            compatibility = maxOf(compatibility, filter.compatibility)
            logicalParent = container.logical
            resolvedParent = container.resolvedField
            physicalParent = container.physicalField
            val value = if (filter.value === element.filter) element else element.copy(filter = filter.value)
            if (elements != null) {
                elements += value
            } else if (value !== element) {
                elements = ArrayList<AggregationElement>(query.elements.size).apply {
                    addAll(query.elements.subList(0, index))
                    add(value)
                }
            }
        }
        query.groupBy.forEach { group ->
            compatibility = maxOf(
                compatibility,
                resolveAggregationField(
                    group.field,
                    group.capability,
                    logicalParent,
                    resolvedParent,
                    physicalParent,
                ).compatibility,
            )
        }
        query.metrics.forEach { metric ->
            if (metric is AggregationMetric.Any) {
                val resolved = resolveAggregationField(
                    metric.field,
                    QueryCapability.AGGREGATE_TERMS,
                    logicalParent,
                    resolvedParent,
                    physicalParent,
                )
                compatibility = maxOf(compatibility, resolved.compatibility)
                if (resolved.fieldSchema?.cardinality == QueryCardinality.MANY) {
                    compatibility = QueryCompatibilityLevel.INCOMPATIBLE
                }
            }
        }
        query.metrics.forEach { metric ->
            if (metric is AggregationMetric.Numeric) {
                compatibility = maxOf(
                    compatibility,
                    resolveExpression(metric.expression, logicalParent, resolvedParent, physicalParent),
                )
            }
        }
        val resolvedElements = elements ?: query.elements
        return QuerySchemaResolution(
            if (
                schema.rewriteMode == QueryRewriteMode.NONE ||
                rootFilter.value === query.filter && resolvedElements === query.elements
            ) {
                query
            } else {
                query.copy(filter = rootFilter.value, elements = resolvedElements)
            },
            compatibility,
        )
    }

    private fun resolveExpression(
        expression: AggregationExpression,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
    ): QueryCompatibilityLevel = when (expression) {
        is AggregationExpression.Field -> resolveAggregationField(
            expression.field,
            QueryCapability.AGGREGATE_NUMERIC,
            logicalParent,
            resolvedParent,
            physicalParent,
        ).compatibility
        is AggregationExpression.Constant -> QueryCompatibilityLevel.EXACT
        is AggregationExpression.Binary -> maxOf(
            resolveExpression(expression.left, logicalParent, resolvedParent, physicalParent),
            resolveExpression(expression.right, logicalParent, resolvedParent, physicalParent),
        )
        else -> QueryCompatibilityLevel.INCOMPATIBLE
    }

    private fun resolveAggregationField(
        field: QueryField,
        capability: QueryCapability,
        logicalParent: QueryField?,
        resolvedParent: QueryField?,
        physicalParent: QueryField?,
    ): QueryFieldResolution = fieldResolver.resolve(
        field = logicalParent?.append(field) ?: field,
        capability = capability,
        logicalParent = logicalParent,
        resolvedParent = resolvedParent,
        physicalParent = physicalParent,
    ).let { resolved ->
        val logicalCandidate = resolved.logical.path
        val resolvedCandidate = resolved.resolvedField?.path ?: logicalCandidate
        val physicalCandidate = resolved.physicalField?.path
        val matchesMaskedCandidate = isMaskedAggregationCandidate(logicalCandidate) ||
            resolvedCandidate != logicalCandidate && isMaskedAggregationCandidate(resolvedCandidate) ||
            physicalCandidate != null && physicalCandidate != resolvedCandidate &&
            isMaskedAggregationCandidate(physicalCandidate)
        if (resolved.fieldSchema?.maskRule == null && !matchesMaskedCandidate) {
            resolved
        } else {
            resolved.copy(compatibility = QueryCompatibilityLevel.INCOMPATIBLE)
        }
    }

    private fun isMaskedAggregationCandidate(path: String): Boolean {
        if (maskedAggregationPaths.isEmpty()) return false
        if (path in maskedAggregationPaths) return true
        var separator = path.lastIndexOf('.')
        while (separator > 0) {
            if (path.substring(0, separator) in maskedAggregationPaths) return true
            separator = path.lastIndexOf('.', separator - 1)
        }
        return false
    }

    private fun QueryFieldResolution.matchesMaskedCandidate(): Boolean {
        val logicalCandidate = logical.path
        val projectionCandidate = fieldSchema?.projectionField?.path
        val resolvedCandidate = resolvedField?.path ?: logicalCandidate
        val physicalCandidate = physicalField?.path
        return logicalCandidate in maskedProjectionPaths ||
            projectionCandidate != null && projectionCandidate in maskedProjectionPaths ||
            resolvedCandidate in maskedPhysicalPaths ||
            physicalCandidate != null && physicalCandidate in maskedPhysicalPaths
    }

    private val AggregationGroup.capability: QueryCapability
        get() = when (this) {
            is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
            is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
            is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
        }
}
