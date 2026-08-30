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

@file:JvmName("QuerySchemaResolverKt")
@file:JvmMultifileClass
@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel

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

data class ResolvedAggregationQuery(
    val query: AggregationQuery,
    val schema: QueryModelSchema?,
)

fun <T> QuerySchemaResolution<T>.requireAccepted(mode: QuerySchemaValidationMode): T {
    if (!mode.accepts(compatibility)) {
        throw QuerySchemaValidationException(
            "Query compatibility [$compatibility] is rejected by mode [$mode].",
        )
    }
    return value
}

class QuerySchemaResolver(private val schema: QueryModelSchema) {
    private val fieldResolver = QueryFieldSchemaResolver(schema)
    private val filterResolver = QueryFilterSchemaResolver(schema, fieldResolver)

    fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            SingleQuery(filter.value, projection.value, sort.value),
            listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
        )
    }

    fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            ListQuery(filter.value, projection.value, sort.value, query.limit),
            listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
        )
    }

    fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery> {
        val filter = resolve(query.filter)
        val projection = resolve(query.projection)
        val sort = resolve(query.sort)
        return QuerySchemaResolution(
            PagedQuery(filter.value, projection.value, sort.value, query.pagination),
            listOf(filter.compatibility, projection.compatibility, sort.compatibility).combined(),
        )
    }

    fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression> =
        filterResolver.resolve(filter)

    fun resolve(projection: Projection): QuerySchemaResolution<Projection> {
        val include = projection.include.map {
            fieldResolver.resolveProjectionPath(it)
        }
        val exclude = projection.exclude.map {
            fieldResolver.resolveProjectionPath(it)
        }
        return QuerySchemaResolution(
            Projection(
                include.map { it.value },
                exclude.map { it.value },
            ),
            (include + exclude).map { it.compatibility }.combined(),
        )
    }

    fun resolve(sort: List<Sort>): QuerySchemaResolution<List<Sort>> {
        val resolved = sort.map { item ->
            fieldResolver.resolvePath(item.field, QueryCapability.SORT).let { field ->
                item.copy(field = field.value) to field.compatibility
            }
        }
        return QuerySchemaResolution(
            resolved.map(Pair<Sort, QueryCompatibilityLevel>::first),
            resolved.map(Pair<Sort, QueryCompatibilityLevel>::second).combined(),
        )
    }

    fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery> {
        val rootFilter = resolve(query.filter)
        val levels = mutableListOf(rootFilter.compatibility)
        var logicalParent: LogicalField? = null
        var physicalParent: String? = null
        val elements = query.elements.map { element ->
            val container = resolveAggregationField(
                element.path,
                QueryCapability.ELEMENT_SCOPE,
                logicalParent,
                physicalParent,
            )
            levels += container.compatibility
            val filter = filterResolver.resolve(element.filter, container.logical, container.physicalPath)
            levels += filter.compatibility
            logicalParent = container.logical
            physicalParent = container.physicalPath
            element.copy(filter = filter.value)
        }
        query.groupBy.forEach { group ->
            levels += resolveAggregationField(
                group.field,
                group.capability,
                logicalParent,
                physicalParent,
            ).compatibility
        }
        query.metrics.filterIsInstance<AggregationMetric.Any>().forEach { metric ->
            val resolved = resolveAggregationField(
                metric.field,
                QueryCapability.AGGREGATE_TERMS,
                logicalParent,
                physicalParent,
            )
            levels += resolved.compatibility
            if (resolved.fieldSchema?.cardinality == QueryCardinality.MANY) {
                levels += QueryCompatibilityLevel.INCOMPATIBLE
            }
        }
        query.metrics.filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
            collectExpressionLevels(metric.expression, logicalParent, physicalParent, levels)
        }
        return QuerySchemaResolution(
            query.copy(filter = rootFilter.value, elements = elements),
            levels.combined(),
        )
    }

    private fun collectExpressionLevels(
        expression: AggregationExpression,
        logicalParent: LogicalField?,
        physicalParent: String?,
        levels: MutableList<QueryCompatibilityLevel>,
    ) {
        when (expression) {
            is AggregationExpression.Field -> levels += resolveAggregationField(
                expression.field,
                QueryCapability.AGGREGATE_NUMERIC,
                logicalParent,
                physicalParent,
            ).compatibility
            is AggregationExpression.Constant -> Unit
            is AggregationExpression.Binary -> {
                collectExpressionLevels(expression.left, logicalParent, physicalParent, levels)
                collectExpressionLevels(expression.right, logicalParent, physicalParent, levels)
            }
            else -> levels += QueryCompatibilityLevel.INCOMPATIBLE
        }
    }

    private fun resolveAggregationField(
        field: LogicalField,
        capability: QueryCapability,
        logicalParent: LogicalField?,
        physicalParent: String?,
    ): QueryFieldResolution = fieldResolver.resolve(
        field = if (logicalParent == null) field else LogicalField("${logicalParent.value}.${field.value}"),
        capability = capability,
        logicalParent = logicalParent,
        physicalParent = physicalParent,
        fieldIsAbsolute = true,
    ).let { resolved ->
        if (resolved.fieldSchema?.maskRule == null) {
            resolved
        } else {
            resolved.copy(compatibility = QueryCompatibilityLevel.INCOMPATIBLE)
        }
    }

    private val AggregationGroup.capability: QueryCapability
        get() = when (this) {
            is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
            is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
            is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
        }
}

internal fun LogicalField.absoluteTo(parent: LogicalField?): LogicalField =
    if (parent == null || value == parent.value || value.startsWith("${parent.value}.")) {
        this
    } else {
        LogicalField("${parent.value}.$value")
    }
