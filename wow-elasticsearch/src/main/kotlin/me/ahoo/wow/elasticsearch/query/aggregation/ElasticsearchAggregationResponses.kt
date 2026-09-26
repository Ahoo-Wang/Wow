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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.elasticsearch.query.toObjectNode
import tools.jackson.databind.node.ObjectNode

internal fun ResponseBody<Map<*, *>>.summary(plan: ElasticsearchAggregationPlan): ObjectNode {
    val scope = aggregations().getValue(ROOT_AGGREGATION).let { root ->
        if (plan.elements.isEmpty()) root.filter() else root.innermostScope(plan)
    }
    if (plan.groupSources.isEmpty() && plan.metrics.any { it is ElasticsearchAggregationMetric.Derived }) {
        val bucket = scope.aggregations()
            .getValue(SUMMARY_BUCKET_AGGREGATION)
            .filters()
            .buckets()
            .keyed()
            .getValue(SUMMARY_BUCKET_KEY)
        return plan.toRow(bucket.docCount(), bucket.aggregations())
    }
    return plan.toRow(scope.docCount(), scope.aggregations())
}

internal fun ResponseBody<Map<*, *>>.innermost(plan: ElasticsearchAggregationPlan): Map<String, Aggregate> {
    if (plan.elements.isEmpty()) {
        return mapOf(GROUP_AGGREGATION to aggregations().getValue(ROOT_AGGREGATION))
    }
    var aggregations = aggregations()
    plan.elements.indices.forEach { index ->
        val nested = aggregations.getValue(if (index == 0) ROOT_AGGREGATION else nestedAggregationName(index))
            .nested()
        val filter = nested.aggregations().getValue(filterAggregationName(index)).filter()
        aggregations = filter.aggregations()
    }
    return aggregations
}

internal fun Aggregate.innermostScope(
    plan: ElasticsearchAggregationPlan,
): co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate {
    var aggregations = nested().aggregations()
    var scope: co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate? = null
    plan.elements.indices.forEach { index ->
        scope = aggregations.getValue(filterAggregationName(index)).filter()
        aggregations = scope!!.aggregations()
        if (index + 1 < plan.elements.size) {
            aggregations = aggregations.getValue(nestedAggregationName(index + 1)).nested().aggregations()
        }
    }
    return requireNotNull(scope)
}

internal fun CompositeBucket.toRow(plan: ElasticsearchAggregationPlan): ObjectNode {
    val row = key().mapValuesTo(linkedMapOf()) { (_, value) -> value.nativeValue() }
    plan.metrics.forEach { metric -> row[metric.alias] = metric.value(docCount(), aggregations()) }
    return row.toObjectNode()
}

internal fun ElasticsearchAggregationPlan.toRow(
    docCount: Long,
    aggregations: Map<String, Aggregate>,
): ObjectNode = metrics.associateTo(linkedMapOf()) { metric ->
    metric.alias to metric.value(docCount, aggregations)
}.toObjectNode()

internal fun ElasticsearchAggregationMetric.value(
    docCount: Long,
    aggregations: Map<String, Aggregate>,
): Any? = when (this) {
    is ElasticsearchAggregationMetric.Count -> if (filter == null) {
        docCount
    } else {
        aggregations.getValue(alias).filter().docCount()
    }

    is ElasticsearchAggregationMetric.Any -> aggregations.filtered(this).getValue(alias).anyValue(alias)
    is ElasticsearchAggregationMetric.Numeric -> numericValue(aggregations.filtered(this))
    is ElasticsearchAggregationMetric.DistinctCount ->
        aggregations.filtered(this).getValue(alias).cardinality().value()
    is ElasticsearchAggregationMetric.Percentile -> percentileValue(aggregations.filtered(this))
    is ElasticsearchAggregationMetric.Edge -> edgeValue(aggregations.filtered(this))

    /**
     * A skipped bucket_script (default gap_policy=skip: a referenced path is missing or null)
     * is omitted from the response bucket, so the key itself may be absent — null either way.
     */
    is ElasticsearchAggregationMetric.Derived -> aggregations[alias]?.simpleValue()?.value()
}

/** The first doc value of the top hit, or `null` when no document qualified. */
internal fun ElasticsearchAggregationMetric.Edge.edgeValue(aggregations: Map<String, Aggregate>): Any? {
    val hit = aggregations.getValue(alias).topHits().hits().hits().firstOrNull() ?: return null
    val value = hit.fields()[field]?.to(List::class.java)?.firstOrNull() ?: return null
    return if (epochMillis) value.toString().toLong() else value
}

internal fun Aggregate.anyValue(alias: String): Any? = when {
    isSterms -> sterms().buckets().array().firstOrNull()?.key()?.nativeValue()
    isLterms -> lterms().buckets().array().firstOrNull()?.let {
        it.keyAsString()?.toBooleanStrictOrNull() ?: it.key()
    }
    isDterms -> dterms().buckets().array().firstOrNull()?.key()
    isUmterms -> null
    else -> error("Aggregation ANY metric [$alias] returned unsupported Elasticsearch aggregate [${_kind()}].")
}

internal fun ElasticsearchAggregationMetric.Numeric.numericValue(
    aggregations: Map<String, Aggregate>,
): Double? {
    if (aggregations.getValue(valueCountAlias).valueCount().value() == 0.0) return null
    val value = when (function) {
        AggregationFunction.SUM -> aggregations.getValue(alias).sum().value()
        AggregationFunction.AVG -> aggregations.getValue(alias).avg().value()
        AggregationFunction.MIN -> aggregations.getValue(alias).min().value()
        AggregationFunction.MAX -> aggregations.getValue(alias).max().value()
        AggregationFunction.STDDEV -> aggregations.getValue(alias).extendedStats().stdDeviationPopulation()
        AggregationFunction.VARIANCE -> aggregations.getValue(alias).extendedStats().variancePopulation()
    }
    return requireNotNull(value) { "Aggregation metric [$alias] is missing its value." }
}

internal fun ElasticsearchAggregationMetric.Percentile.percentileValue(
    aggregations: Map<String, Aggregate>,
): Double? {
    if (aggregations.getValue(valueCountAlias).valueCount().value() == 0.0) return null
    val entry = aggregations.getValue(alias).tdigestPercentiles().values().array()
        .firstOrNull { it.key() == percentile }
        ?: error("Aggregation metric [$alias] is missing percentile [$percentile].")
    val value = entry.value()
    return requireNotNull(value) { "Aggregation metric [$alias] is missing its value." }
}

internal fun FieldValue.nativeValue(): Any? = when {
    isString -> stringValue()
    isLong -> longValue()
    isDouble -> doubleValue()
    isBoolean -> booleanValue()
    isNull -> null
    else -> error("Unsupported Elasticsearch aggregation key [${_kind()}].")
}

internal fun Map<String, Aggregate>.filtered(metric: ElasticsearchAggregationMetric): Map<String, Aggregate> {
    val filter = metric.filter ?: return this
    return getValue(metricFilterAggregationName(metric.alias)).filter().aggregations()
}
