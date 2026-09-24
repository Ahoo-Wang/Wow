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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.mapping.RuntimeField
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.distinctCountCapability
import java.time.Instant
import java.time.ZoneId

internal class ElasticsearchAggregationCompiler(
    private val filterCompiler: AbstractElasticsearchFilterCompiler,
) {
    fun compile(query: AggregationQuery, schema: QueryModelSchema): ElasticsearchAggregationPlan = compile(
        query,
        schema,
        Instant.now()
    )

    internal fun compile(query: AggregationQuery, schema: QueryModelSchema, now: Instant): ElasticsearchAggregationPlan {
        val rootQuery = filterCompiler.compile(query.filter, schema, now)
        val elements = mutableListOf<ElasticsearchAggregationElement>()
        var logicalParent: QueryField? = null
        var physicalParent: QueryField? = null
        query.elements.forEach { element ->
            val previousLogicalParent = logicalParent
            logicalParent = previousLogicalParent?.append(element.path) ?: element.path
            val nestedPath = element.path.resolve(
                previousLogicalParent,
                physicalParent,
                schema,
                QueryCapability.ELEMENT_SCOPE,
            )
            physicalParent = QueryField(nestedPath)
            elements += ElasticsearchAggregationElement(
                path = nestedPath,
                filter = filterCompiler.compileScoped(
                    element.filter,
                    schema,
                    logicalParent,
                    physicalParent,
                    now,
                ),
            )
        }

        val runtimeMappings = linkedMapOf<String, RuntimeField>()
        val effectiveSort = query.effectiveSort()
        val groups = query.groupBy.withIndex().associateBy { it.value.alias }
        val groupSources = effectiveSort.mapNotNull { sort ->
            groups[sort.field.path]?.let { indexed ->
                indexed.value.toSource(logicalParent, physicalParent, sort, indexed.index, schema, runtimeMappings)
            }
        }
        val metricPlans = compileMetrics(query, logicalParent, physicalParent, schema, runtimeMappings, now)
        val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        val dense = query.groupBy.singleOrNull()?.let { it as? AggregationGroup.DateHistogram }?.takeIf { it.dense }
            ?.let { DenseBucketPlan(it.alias, DenseDateGrid(it.unit, ZoneId.of(it.timeZone)), query.metrics) }
        return ElasticsearchAggregationPlan(
            rootQuery = rootQuery,
            elements = elements,
            groupSources = groupSources,
            metrics = metricPlans,
            runtimeMappings = runtimeMappings,
            effectiveSort = effectiveSort,
            limit = query.limit,
            metricSorted = effectiveSort.any { it.field.path in metricAliases },
            having = query.having,
            dense = dense,
        )
    }

    /**
     * Compiles the declared metrics in order. Declaration order (list iteration order) lets a
     * [AggregationMetric.Derived] metric resolve the plans of metrics declared before it;
     * referenced aliases keep one stable `vN`/`cN` param index across the whole compile.
     */
    private fun compileMetrics(
        query: AggregationQuery,
        logicalParent: QueryField?,
        physicalParent: QueryField?,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        now: Instant,
    ): List<ElasticsearchAggregationMetric> {
        val metricPlans = mutableListOf<ElasticsearchAggregationMetric>()
        val priorByAlias = linkedMapOf<String, ElasticsearchAggregationMetric>()
        val derivedRefIndexes = linkedMapOf<String, Int>()
        query.metrics.forEachIndexed { index, metric ->
            val plan = metric.toPlan(
                logicalParent,
                physicalParent,
                index,
                schema,
                runtimeMappings,
                now,
                priorByAlias,
                derivedRefIndexes,
            )
            metricPlans += plan
            priorByAlias[metric.alias] = plan
        }
        return metricPlans
    }

    private fun AggregationMetric.toPlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        now: Instant,
        prior: Map<String, ElasticsearchAggregationMetric>,
        derivedRefIndexes: MutableMap<String, Int>,
    ): ElasticsearchAggregationMetric {
        val filter = metricFilter(parent, physicalParent, schema, now)
        return when (this) {
            is AggregationMetric.Count -> ElasticsearchAggregationMetric.Count(alias, filter)
            is AggregationMetric.Any -> ElasticsearchAggregationMetric.Any(
                alias,
                field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS),
                filter,
            )
            is AggregationMetric.Numeric -> {
                val metricExpression = expression
                val scalarField = (metricExpression as? AggregationExpression.Field)?.field?.takeIf { field ->
                    val logicalField = parent?.append(field) ?: field
                    schema.field(logicalField)?.value?.cardinality == QueryCardinality.SINGLE
                }
                val metricField = if (scalarField != null) {
                    scalarField.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
                } else {
                    "__wow_expression_$index".also { runtimeFieldName ->
                        runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                            parent,
                            physicalParent,
                            schema,
                        ).compile(metricExpression)
                    }
                }
                ElasticsearchAggregationMetric.Numeric(alias, function, metricField, filter)
            }

            is AggregationMetric.DistinctCount -> toDistinctCountPlan(
                parent,
                physicalParent,
                index,
                schema,
                runtimeMappings,
                filter,
            )

            is AggregationMetric.Percentile -> toPercentilePlan(
                parent,
                physicalParent,
                index,
                schema,
                runtimeMappings,
                filter,
            )

            is AggregationMetric.Derived -> toDerivedPlan(expression, prior, derivedRefIndexes)
        }
    }

    /**
     * Compiles the record-level filter of this metric against its enclosing scope,
     * or returns `null` for [MatchAllFilter] so unfiltered metrics keep their unwrapped aggregations.
     */
    private fun AggregationMetric.metricFilter(
        parent: QueryField?,
        physicalParent: QueryField?,
        schema: QueryModelSchema,
        now: Instant,
    ): Query? {
        val filter = this.filter
        if (filter === MatchAllFilter) {
            return null
        }
        if (parent == null || physicalParent == null) {
            return filterCompiler.compile(filter, schema, now)
        }
        return filterCompiler.compileScoped(filter, schema, parent, physicalParent, now)
    }

    private fun AggregationMetric.DistinctCount.toDistinctCountPlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        filter: Query?,
    ): ElasticsearchAggregationMetric.DistinctCount {
        val metricField = (expression as? AggregationExpression.Field)?.field?.let { field ->
            field.resolve(parent, physicalParent, schema, schema.distinctCountCapability(field, parent))
        } ?: "__wow_expression_$index".also { runtimeFieldName ->
            runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                parent,
                physicalParent,
                schema,
            ).compile(expression)
        }
        return ElasticsearchAggregationMetric.DistinctCount(alias, metricField, filter)
    }

    private fun AggregationMetric.Percentile.toPercentilePlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        filter: Query?,
    ): ElasticsearchAggregationMetric.Percentile {
        val metricExpression = expression
        val scalarField = (metricExpression as? AggregationExpression.Field)?.field?.takeIf { field ->
            val logicalField = parent?.append(field) ?: field
            schema.field(logicalField)?.value?.cardinality == QueryCardinality.SINGLE
        }
        val metricField = if (scalarField != null) {
            scalarField.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
        } else {
            "__wow_expression_$index".also { runtimeFieldName ->
                runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                    parent,
                    physicalParent,
                    schema,
                ).compile(metricExpression)
            }
        }
        return ElasticsearchAggregationMetric.Percentile(alias, metricField, percentile, filter)
    }
}
