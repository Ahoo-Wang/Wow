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
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.query.converter.ConditionConverter

internal object ElasticsearchAggregationCompiler {
    fun compile(
        query: AggregationQuery,
        mapping: ElasticsearchIndexMapping,
        conditionConverter: ConditionConverter<Query>,
    ): ElasticsearchAggregationPlan {
        val elements = query.elements.mapIndexed { index, element ->
            val elementCondition = Condition.and(
                Condition.deleted(DeletionState.ALL),
                element.condition,
            )
            ResolvedElement(
                path = mapping.requireNested(element.path),
                condition = conditionConverter.convert(mapping.resolve(elementCondition)),
                index = index,
            )
        }
        val resolved = query.copy(
            condition = mapping.resolve(query.condition),
            groupBy = query.groupBy.map { it.resolve(mapping) },
            metrics = query.metrics.map { it.resolve(mapping) },
        )
        return ElasticsearchAggregationPlan(
            query = conditionConverter.convert(resolved.condition),
            aggregationQuery = resolved,
            elements = elements,
        )
    }
}

internal data class ElasticsearchAggregationPlan(
    val query: Query,
    val aggregationQuery: AggregationQuery,
    private val elements: List<ResolvedElement>,
) {
    fun wrap(leafAggregations: Map<String, Aggregation>): Map<String, Aggregation> {
        var children = leafAggregations
        elements.asReversed().forEach { element ->
            val filter = Aggregation.of {
                it.filter(element.condition).aggregations(children)
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
    val condition: Query,
    val index: Int,
) {
    val elementName: String = "__wow_element_$index"
    val filterName: String = "__wow_filter_$index"
}

internal fun AggregationQuery.metricAggregations(): Map<String, Aggregation> = buildMap {
    metrics.forEach { metric ->
        metric.toAggregation()?.let { put(metric.alias, it) }
    }
}

internal fun AggregationGroup.toCompositeSource(direction: Sort.Direction): NamedValue<CompositeAggregationSource> {
    val order = if (direction == Sort.Direction.ASC) SortOrder.Asc else SortOrder.Desc
    return NamedValue.of(
        alias,
        CompositeAggregationSource.of { source ->
            when (this) {
                is AggregationGroup.Terms -> source.terms {
                    it.field(field).missingBucket(false).order(order)
                }

                is AggregationGroup.Histogram -> source.histogram {
                    it.field(field).interval(interval).missingBucket(false).order(order)
                }

                is AggregationGroup.DateHistogram -> source.dateHistogram {
                    it.field(field)
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

private fun AggregationGroup.resolve(mapping: ElasticsearchIndexMapping): AggregationGroup = when (this) {
    is AggregationGroup.Terms -> copy(field = mapping.resolve(field, ElasticsearchFieldUsage.TERMS))
    is AggregationGroup.Histogram -> copy(field = mapping.resolve(field, ElasticsearchFieldUsage.NUMERIC))
    is AggregationGroup.DateHistogram -> copy(field = mapping.resolve(field, ElasticsearchFieldUsage.DATE))
}

private fun AggregationMetric.resolve(mapping: ElasticsearchIndexMapping): AggregationMetric = when (this) {
    is AggregationMetric.Count -> this
    is AggregationMetric.Numeric -> copy(
        expression = when (val expression = expression) {
            is AggregationExpression.Field -> expression.copy(
                field = mapping.resolve(expression.field, ElasticsearchFieldUsage.NUMERIC),
            )
        },
    )
}

private fun AggregationMetric.toAggregation(): Aggregation? = when (this) {
    is AggregationMetric.Count -> null
    is AggregationMetric.Numeric -> {
        val field = (expression as AggregationExpression.Field).field
        Aggregation.of {
            when (function) {
                AggregationFunction.SUM -> it.sum { sum -> sum.field(field) }
                AggregationFunction.AVG -> it.avg { avg -> avg.field(field) }
                AggregationFunction.MIN -> it.min { min -> min.field(field) }
                AggregationFunction.MAX -> it.max { max -> max.field(field) }
            }
        }
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
