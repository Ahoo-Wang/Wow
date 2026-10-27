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

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.mapping.RuntimeField
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.ElasticsearchFilterCompiler
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler.toSortOrder
import me.ahoo.wow.elasticsearch.query.checkExecution
import me.ahoo.wow.elasticsearch.query.metricSorted
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.aggregation.denseGroup
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.Temporal

internal object ElasticsearchAggregationCompiler {
    /**
     * Compiles the native part of [admitted]. HAVING, top-N by a metric and dense fill are RESIDUAL for
     * Elasticsearch (composite aggregations have no bucket selector, no metric ordering and no empty buckets), so
     * the core removes them before the query arrives and computes them over the rows the pager streams. A metric
     * sort that arrives is one the core left to the storage ([AggregationSupport.termsTopN] on one primary shard):
     * one TERMS group ordered by a count, compiled as [ElasticsearchAggregationPlan.metricOrder].
     */
    fun compile(admitted: AdmittedQuery<AggregationQuery>): ElasticsearchAggregationPlan {
        val query = admitted.query
        checkExecution(query.having == null && query.denseGroup == null) {
            "Elasticsearch aggregation received an operator it declares RESIDUAL."
        }
        checkExecution(!query.metricSorted || query.groupBy.singleOrNull() is AggregationGroup.Terms) {
            "Elasticsearch aggregation received a metric sort it cannot order natively."
        }
        val rootQuery = ElasticsearchFilterCompiler.compile(query.filter, admitted)
        val elements = query.elements.map { element ->
            ElasticsearchAggregationElement(
                path = admitted.physicalPath(element.path),
                filter = ElasticsearchFilterCompiler.compile(element.filter, admitted),
            )
        }

        val runtimeMappings = linkedMapOf<String, RuntimeField>()
        val effectiveSort = query.effectiveSort()
        val groups = query.groupBy.withIndex().associateBy { it.value.alias }
        // Admission put the ROOT groups first in the effective sort, so the outer composite leads the order.
        val (rootSources, groupSources) = effectiveSort.mapNotNull { sort ->
            groups[sort.field.path]?.let { indexed ->
                indexed.value to indexed.value.toSource(sort, indexed.index, admitted, runtimeMappings)
            }
        }.partition { (group, _) -> elements.isNotEmpty() && group.scope == AggregationGroupScope.ROOT }
        val metrics = compileMetrics(admitted, runtimeMappings)
        return ElasticsearchAggregationPlan(
            rootQuery = rootQuery,
            elements = elements,
            groupSources = groupSources.map { it.second },
            metrics = metrics,
            runtimeMappings = runtimeMappings,
            effectiveSort = effectiveSort,
            rootSources = rootSources.map { it.second },
            metricOrder = if (query.metricSorted) metricOrder(effectiveSort, groups.keys, metrics) else emptyList(),
        )
    }

    /**
     * The `terms` order of a native top-N: each entry of the effective sort, a metric by its bucket path and the
     * group by its key, which breaks ties as the core's group order does.
     */
    private fun metricOrder(
        effectiveSort: List<Sort>,
        groups: Set<String>,
        metrics: List<ElasticsearchAggregationMetric>,
    ): List<NamedValue<SortOrder>> {
        val byAlias = metrics.associateBy { it.alias }
        return effectiveSort.map { sort ->
            val path = if (sort.field.path in groups) {
                KEY_ORDER
            } else {
                checkNotNull(byAlias[sort.field.path]).orderPath()
            }
            NamedValue.of(path, sort.direction.toSortOrder())
        }
    }

    /**
     * A COUNT by the documents of its bucket, or of its filter (a single-bucket aggregation orders by its document
     * count under its own name), a DISTINCT_COUNT by its cardinality.
     */
    private fun ElasticsearchAggregationMetric.orderPath(): String = when (this) {
        is ElasticsearchAggregationMetric.Count -> if (filter == null) COUNT_ORDER else alias
        is ElasticsearchAggregationMetric.DistinctCount ->
            if (filter == null) alias else "${metricFilterAggregationName(alias)}>$alias"
        else -> throw QueryExecutionException("Elasticsearch aggregation cannot order groups by [$alias] natively.")
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
            is AggregationMetric.Any -> ElasticsearchAggregationMetric.Any(alias, admitted.physicalPath(field), filter)
            is AggregationMetric.Numeric -> ElasticsearchAggregationMetric.Numeric(
                alias,
                function,
                numericInput(expression, index, admitted, runtimeMappings),
                filter,
            )

            is AggregationMetric.DistinctCount -> ElasticsearchAggregationMetric.DistinctCount(
                alias,
                (expression as? AggregationExpression.Field)?.field?.let(admitted::physicalPath)
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
                val field = admitted.physicalPath(field)
                val orderBy = admitted.physicalPath(checkNotNull(orderBy))
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
        if (filter === MatchAllFilter) null else ElasticsearchFilterCompiler.compile(filter, admitted)

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
        return scalarField?.let(admitted::physicalPath) ?: runtimeExpression(expression, index, admitted, runtimeMappings)
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

internal const val KEY_ORDER = "_key"
private const val COUNT_ORDER = "_count"
