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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.Time
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.query.AggregationFieldCatalog

internal object ElasticsearchAggregationCompiler {
    fun compileCount(
        query: AggregationQuery,
        filterConverter: AbstractElasticsearchFilterConverter,
    ): ElasticsearchAggregationPlan {
        require(query.isRootCountOnly) {
            "Mapping-free aggregation supports only root Count metrics."
        }
        return ElasticsearchAggregationPlan(
            query = filterConverter.convert(query.filter),
            aggregationQuery = query,
            elements = emptyList(),
        )
    }

    fun compile(
        query: AggregationQuery,
        mapping: ElasticsearchIndexMapping,
        filterConverter: AbstractElasticsearchFilterConverter,
        fieldCatalog: AggregationFieldCatalog? = null,
        resolveFilter: (FilterExpression) -> FilterExpression = mapping::resolve,
    ): ElasticsearchAggregationPlan {
        val elements = query.elements.mapIndexed { index, element ->
            val elementFilter = AndFilter(
                listOf(DeletionFilter(DeletionState.ALL), element.filter),
            )
            ResolvedElement(
                path = mapping.requireNested(element.path),
                filter = filterConverter.convert(mapping.resolveAggregationFilter(elementFilter)),
                index = index,
            )
        }
        return ElasticsearchAggregationPlan(
            query = filterConverter.convert(resolveFilter(query.filter)),
            aggregationQuery = query,
            elements = elements,
            resolvedGroupFields = query.groupBy.associate { group ->
                group.alias to group.resolveField(mapping, fieldCatalog)
            },
            resolvedMetricFields = query.metrics.filterIsInstance<AggregationMetric.Numeric>().associate { metric ->
                metric.alias to metric.resolveField(mapping, fieldCatalog)
            },
        )
    }
}

internal val AggregationQuery.isRootCountOnly: Boolean
    get() = elements.isEmpty() && groupBy.isEmpty() && metrics.all { it is AggregationMetric.Count }

internal data class ElasticsearchAggregationPlan(
    val query: Query,
    val aggregationQuery: AggregationQuery,
    private val elements: List<ResolvedElement>,
    private val resolvedGroupFields: Map<String, String> = emptyMap(),
    private val resolvedMetricFields: Map<String, String> = emptyMap(),
) {
    private val metricNames = aggregationQuery.metrics
        .filterIsInstance<AggregationMetric.Numeric>()
        .mapIndexed { index, metric -> metric.alias to "${AggregationQuery.INTERNAL_ALIAS_PREFIX}metric_$index" }
        .toMap()

    fun compositeSource(
        group: AggregationGroup,
        direction: Sort.Direction,
    ): NamedValue<CompositeAggregationSource> = group.toCompositeSource(
        direction = direction,
        resolvedField = checkNotNull(resolvedGroupFields[group.alias]) {
            "Elasticsearch aggregation plan is missing group field [${group.alias}]."
        },
    )

    fun metricAggregations(): Map<String, Aggregation> = buildMap {
        aggregationQuery.metrics.filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
            put(
                metricName(metric),
                metric.toAggregation(
                    checkNotNull(resolvedMetricFields[metric.alias]) {
                        "Elasticsearch aggregation plan is missing metric field [${metric.alias}]."
                    },
                ),
            )
        }
    }

    fun metricName(metric: AggregationMetric.Numeric): String = checkNotNull(metricNames[metric.alias]) {
        "Elasticsearch aggregation plan is missing metric [${metric.alias}]."
    }

    fun wrap(leafAggregations: Map<String, Aggregation>): Map<String, Aggregation> {
        var children = leafAggregations
        elements.asReversed().forEach { element ->
            val filter = Aggregation.of {
                it.filter(element.filter).aggregations(children)
            }
            val nested = Aggregation.of {
                it.nested { nested -> nested.path(element.path) }
                    .aggregations(element.filterName, filter)
            }
            children = mapOf(element.elementName to nested)
        }
        return children
    }

    fun leaf(aggregations: Map<String, Aggregate>): ElasticsearchAggregationLeaf {
        var current = aggregations
        var documentCount: Long? = null
        elements.forEach { element ->
            val nested = current.required(element.elementName)
            check(nested.isNested) {
                "Elasticsearch aggregation response [${element.elementName}] must be nested."
            }
            current = nested.nested().aggregations()

            val filter = current.required(element.filterName)
            check(filter.isFilter) {
                "Elasticsearch aggregation response [${element.filterName}] must be filter."
            }
            documentCount = filter.filter().docCount()
            current = filter.filter().aggregations()
        }
        return ElasticsearchAggregationLeaf(current, documentCount)
    }
}

internal data class ElasticsearchAggregationLeaf(
    val aggregations: Map<String, Aggregate>,
    val documentCount: Long?,
)

internal data class ResolvedElement(
    val path: String,
    val filter: Query,
    val index: Int,
) {
    val elementName: String = "${AggregationQuery.INTERNAL_ALIAS_PREFIX}element_$index"
    val filterName: String = "${AggregationQuery.INTERNAL_ALIAS_PREFIX}filter_$index"
}

internal fun AggregationGroup.toCompositeSource(
    direction: Sort.Direction,
    resolvedField: String = field,
): NamedValue<CompositeAggregationSource> {
    val order = if (direction == Sort.Direction.ASC) SortOrder.Asc else SortOrder.Desc
    return NamedValue.of(
        alias,
        CompositeAggregationSource.of { source ->
            when (this) {
                is AggregationGroup.Terms -> source.terms {
                    it.field(resolvedField).missingBucket(false).order(order)
                }

                is AggregationGroup.Histogram -> source.histogram {
                    it.field(resolvedField).interval(interval).missingBucket(false).order(order)
                }

                is AggregationGroup.DateHistogram -> source.dateHistogram {
                    it.field(resolvedField)
                        .format("epoch_millis")
                        .missingBucket(false)
                        .order(order)
                        .timeZone(timeZone)
                        .also { histogram ->
                            val interval = Time.of { time -> time.time(unit.toElasticsearchInterval()) }
                            if (unit == AggregationDateUnit.SECOND) {
                                histogram.fixedInterval(interval)
                            } else {
                                histogram.calendarInterval(interval)
                            }
                        }
                }
            }
        },
    )
}

private fun AggregationGroup.resolveField(
    mapping: ElasticsearchIndexMapping,
    catalog: AggregationFieldCatalog?,
): String = when (this) {
    is AggregationGroup.Terms -> resolveAggregationField(mapping, catalog, ElasticsearchFieldUsage.TERMS)
    is AggregationGroup.Histogram -> resolveAggregationField(mapping, catalog, ElasticsearchFieldUsage.NUMERIC)
    is AggregationGroup.DateHistogram -> mapping.resolve(field, ElasticsearchFieldUsage.DATE)
}

private fun AggregationMetric.Numeric.resolveField(
    mapping: ElasticsearchIndexMapping,
    catalog: AggregationFieldCatalog?,
): String =
    when (val expression = expression) {
        is AggregationExpression.Field -> expression.resolveAggregationField(
            mapping,
            catalog,
            ElasticsearchFieldUsage.NUMERIC,
        )
    }

private fun AggregationGroup.resolveAggregationField(
    mapping: ElasticsearchIndexMapping,
    catalog: AggregationFieldCatalog?,
    usage: ElasticsearchFieldUsage,
): String = catalog?.paths?.get(field)?.takeIf { it.isNumeric }?.let {
    mapping.resolveAggregation(field, usage, it.type.rawClass)
} ?: mapping.resolve(field, usage)

private fun AggregationExpression.Field.resolveAggregationField(
    mapping: ElasticsearchIndexMapping,
    catalog: AggregationFieldCatalog?,
    usage: ElasticsearchFieldUsage,
): String = catalog?.paths?.get(field)?.let {
    mapping.resolveAggregation(field, usage, it.type.rawClass)
} ?: mapping.resolve(field, usage)

private fun AggregationMetric.Numeric.toAggregation(resolvedField: String): Aggregation = Aggregation.of {
    when (function) {
        AggregationFunction.SUM -> it.sum { sum -> sum.field(resolvedField) }
        AggregationFunction.AVG -> it.avg { avg -> avg.field(resolvedField) }
        AggregationFunction.MIN -> it.min { min -> min.field(resolvedField) }
        AggregationFunction.MAX -> it.max { max -> max.field(resolvedField) }
    }
}

private fun AggregationDateUnit.toElasticsearchInterval(): String = when (this) {
    AggregationDateUnit.YEAR -> "1y"
    AggregationDateUnit.QUARTER -> "1q"
    AggregationDateUnit.MONTH -> "1M"
    AggregationDateUnit.WEEK -> "1w"
    AggregationDateUnit.DAY -> "1d"
    AggregationDateUnit.HOUR -> "1h"
    AggregationDateUnit.MINUTE -> "1m"
    AggregationDateUnit.SECOND -> "1s"
}

private fun Map<String, Aggregate>.required(name: String): Aggregate = checkNotNull(get(name)) {
    "Elasticsearch aggregation response is missing [$name]."
}
