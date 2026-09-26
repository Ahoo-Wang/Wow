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
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.aggregation.denseGroup
import me.ahoo.wow.query.aggregation.metricSorted

internal class ElasticsearchAggregationCompiler(
    private val filterCompiler: AbstractElasticsearchFilterCompiler,
) {
    /**
     * Compiles the native part of [admitted]. HAVING, top-N by a metric and dense fill are RESIDUAL for
     * Elasticsearch (composite aggregations have no bucket selector, no metric ordering and no empty buckets), so
     * the core removes them before the query arrives and computes them over the rows the pager streams.
     */
    fun compile(admitted: AdmittedQuery<AggregationQuery>): ElasticsearchAggregationPlan {
        val query = admitted.query
        check(query.having == null && query.denseGroup == null && !query.metricSorted) {
            "Elasticsearch aggregation received an operator it declares RESIDUAL."
        }
        val rootQuery = filterCompiler.compile(query.filter, admitted)
        val elements = query.elements.map { element ->
            ElasticsearchAggregationElement(
                path = element.path.physicalPath(admitted),
                filter = filterCompiler.compile(element.filter, admitted),
            )
        }

        val runtimeMappings = linkedMapOf<String, RuntimeField>()
        val effectiveSort = query.effectiveSort()
        val groups = query.groupBy.withIndex().associateBy { it.value.alias }
        val groupSources = effectiveSort.mapNotNull { sort ->
            groups[sort.field.path]?.let { indexed ->
                indexed.value.toSource(sort, indexed.index, admitted, runtimeMappings)
            }
        }
        return ElasticsearchAggregationPlan(
            rootQuery = rootQuery,
            elements = elements,
            groupSources = groupSources,
            metrics = compileMetrics(admitted, runtimeMappings),
            runtimeMappings = runtimeMappings,
            effectiveSort = effectiveSort,
        )
    }

    /**
     * Compiles the declared metrics in order. Declaration order (list iteration order) lets a
     * [AggregationMetric.Derived] metric resolve the plans of metrics declared before it;
     * referenced aliases keep one stable `vN`/`cN` param index across the whole compile.
     */
    private fun compileMetrics(
        admitted: AdmittedQuery<AggregationQuery>,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): List<ElasticsearchAggregationMetric> {
        val metricPlans = mutableListOf<ElasticsearchAggregationMetric>()
        val priorByAlias = linkedMapOf<String, ElasticsearchAggregationMetric>()
        val derivedRefIndexes = linkedMapOf<String, Int>()
        admitted.query.metrics.forEachIndexed { index, metric ->
            val plan = metric.toPlan(index, admitted, runtimeMappings, priorByAlias, derivedRefIndexes)
            metricPlans += plan
            priorByAlias[metric.alias] = plan
        }
        return metricPlans
    }

    private fun AggregationMetric.toPlan(
        index: Int,
        admitted: AdmittedQuery<AggregationQuery>,
        runtimeMappings: MutableMap<String, RuntimeField>,
        prior: Map<String, ElasticsearchAggregationMetric>,
        derivedRefIndexes: MutableMap<String, Int>,
    ): ElasticsearchAggregationMetric {
        val filter = metricFilter(admitted)
        return when (this) {
            is AggregationMetric.Count -> ElasticsearchAggregationMetric.Count(alias, filter)
            is AggregationMetric.Any -> ElasticsearchAggregationMetric.Any(alias, field.physicalPath(admitted), filter)
            is AggregationMetric.Numeric -> ElasticsearchAggregationMetric.Numeric(
                alias,
                function,
                numericInput(expression, index, admitted, runtimeMappings),
                filter,
            )

            is AggregationMetric.DistinctCount -> ElasticsearchAggregationMetric.DistinctCount(
                alias,
                (expression as? AggregationExpression.Field)?.field?.physicalPath(admitted)
                    ?: runtimeExpression(expression, index, admitted, runtimeMappings),
                filter,
            )

            is AggregationMetric.Percentile -> ElasticsearchAggregationMetric.Percentile(
                alias,
                numericInput(expression, index, admitted, runtimeMappings),
                percentile,
                filter,
            )

            is AggregationMetric.Derived -> toDerivedPlan(expression, prior, derivedRefIndexes)
            is AggregationMetric.Edge -> {
                val field = field.physicalPath(admitted)
                val orderBy = checkNotNull(orderBy).physicalPath(admitted)
                ElasticsearchAggregationMetric.Edge(
                    alias,
                    field,
                    orderBy,
                    last = this is AggregationMetric.Last,
                    epochMillis = admitted.field(this.field).temporal == Temporal.Date,
                    filter = bool { query ->
                        query.filter(exists { it.field(field) }, exists { it.field(orderBy) })
                        filter?.let(query::filter)
                        query
                    },
                )
            }
        }
    }

    /**
     * Compiles the record-level filter of this metric against its enclosing scope,
     * or returns `null` for [MatchAllFilter] so unfiltered metrics keep their unwrapped aggregations.
     */
    private fun AggregationMetric.metricFilter(admitted: AdmittedQuery<AggregationQuery>): Query? =
        if (filter === MatchAllFilter) null else filterCompiler.compile(filter, admitted)

    /** A single-valued field aggregates its doc values directly; any other expression runs as a runtime field. */
    private fun numericInput(
        expression: AggregationExpression,
        index: Int,
        admitted: AdmittedQuery<AggregationQuery>,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): String {
        val scalarField: QueryField? = (expression as? AggregationExpression.Field)?.field?.takeIf { field ->
            admitted.field(field).cardinality == QueryCardinality.SINGLE
        }
        return scalarField?.physicalPath(admitted) ?: runtimeExpression(expression, index, admitted, runtimeMappings)
    }

    private fun runtimeExpression(
        expression: AggregationExpression,
        index: Int,
        admitted: AdmittedQuery<AggregationQuery>,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): String = "__wow_expression_$index".also { runtimeFieldName ->
        runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(admitted).compile(expression)
    }
}
