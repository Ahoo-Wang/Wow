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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.aggregations.TermsAggregateBase
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.elasticsearch.query.toObjectNode
import tools.jackson.databind.node.ObjectNode

/*
 * Grouping expanded elements by a root field. A composite inside a nested aggregation reaches no root field, and a
 * composite may not sit under another multi-bucket aggregation, so a plan with root groups runs an outer composite
 * over the root records' keys, and under each of its buckets the nested scopes of the elements, where the element
 * groups are plain bucket aggregations nested one per group in sort order, built from the same sources. Each
 * bucket aggregation orders by key, as the composite source does, and keeps only the keys with documents.
 */

internal fun innerGroupAggregationName(index: Int): String = "__wow_inner_group_$index"

/** The element groups of [plan] as nested bucket aggregations, the metrics under the last, at most [size] keys each. */
internal fun ElasticsearchAggregationPlan.innerGroupAggregations(
    size: Int,
    metrics: Map<String, Aggregation>,
): Map<String, Aggregation> {
    var aggregations = metrics
    groupSources.indices.reversed().forEach { index ->
        aggregations = mapOf(
            innerGroupAggregationName(index) to groupSources[index].value().toBucketAggregation(size, aggregations),
        )
    }
    return aggregations
}

/**
 * The bucket aggregation of one group, built from its composite source: ordered by key as the source is, or a TERMS
 * group by [order] (a native top-N's metric order).
 */
internal fun CompositeAggregationSource.toBucketAggregation(
    size: Int,
    subAggregations: Map<String, Aggregation>,
    order: List<NamedValue<SortOrder>> = emptyList(),
): Aggregation = Aggregation.of { builder ->
    when {
        isTerms -> builder.terms { terms ->
            val source = terms()
            terms.field(source.field()).size(size)
                .order(order.ifEmpty { listOf(NamedValue.of(KEY_ORDER, checkNotNull(source.order()))) })
        }

        isHistogram -> builder.histogram { histogram ->
            val source = histogram()
            histogram.field(source.field())
                .interval(source.interval())
                .minDocCount(1)
                .order(NamedValue.of(KEY_ORDER, source.order()))
        }

        isDateHistogram -> builder.dateHistogram { dateHistogram ->
            val source = dateHistogram()
            dateHistogram.field(source.field()).minDocCount(1).order(NamedValue.of(KEY_ORDER, source.order()))
            source.calendarInterval()?.let { interval ->
                dateHistogram.calendarInterval(
                    CalendarInterval.entries.first { it.jsonValue() == interval.time() },
                )
            }
            source.fixedInterval()?.let(dateHistogram::fixedInterval)
            source.timeZone()?.let(dateHistogram::timeZone)
            dateHistogram
        }

        else -> error("Elasticsearch aggregation has no bucket aggregation for a [${_kind()}] group.")
    }.aggregations(subAggregations)
}

/**
 * The rows of one outer bucket: its element groups, or its one summary row when every group reads the root, each
 * carrying the bucket's root keys. A bucket whose root records keep no element after the element filters has no
 * row, as when a store unwinds the elements. More than [size] element groups under one outer bucket, or more than
 * [size] keys of one group, fail the query rather than return part of them.
 */
internal fun CompositeBucket.rootRows(plan: ElasticsearchAggregationPlan, size: Int): List<ObjectNode> {
    val rootKey = key().mapValuesTo(linkedMapOf<String, Any?>()) { (_, value) -> value.nativeValue() }
    val scope = aggregations().getValue(nestedAggregationName(0)).innermostScope(plan)
    if (scope.docCount() == 0L) return emptyList()
    if (plan.groupSources.isEmpty()) return listOf(rootKey.toRow(scope.summaryRow(plan)))
    val rows = mutableListOf<ObjectNode>()
    plan.collectInnerRows(scope.aggregations(), 0, rootKey, size, rows)
    check(rows.size <= size) { "Elasticsearch aggregation has more than $size element groups under one root group." }
    return rows
}

private fun ElasticsearchAggregationPlan.collectInnerRows(
    aggregations: Map<String, Aggregate>,
    index: Int,
    key: Map<String, Any?>,
    size: Int,
    rows: MutableList<ObjectNode>,
) {
    val alias = groupSources[index].name()
    aggregations.getValue(innerGroupAggregationName(index)).keyedBuckets(alias, size).forEach { bucket ->
        val bucketKey = key + (alias to bucket.key)
        if (index + 1 < groupSources.size) {
            collectInnerRows(bucket.aggregations, index + 1, bucketKey, size, rows)
        } else {
            rows += bucketKey.toRow(toRow(bucket.docCount, bucket.aggregations))
        }
    }
}

internal class KeyedBucket(val key: Any?, val docCount: Long, val aggregations: Map<String, Aggregate>)

/**
 * The buckets of a group aggregation, keyed as its composite source would key them. [complete]: every key of the
 * group fits [size], or the query fails; a top-N takes only its first [size] keys.
 */
internal fun Aggregate.keyedBuckets(alias: String, size: Int, complete: Boolean = true): List<KeyedBucket> {
    val buckets = when {
        isSterms || isLterms || isDterms -> termsBuckets(alias, size, complete)
        isUmterms -> emptyList()
        isHistogram -> histogram().buckets().array().map { KeyedBucket(it.key(), it.docCount(), it.aggregations()) }
        isDateHistogram -> dateHistogram().buckets().array()
            .map { KeyedBucket(it.key(), it.docCount(), it.aggregations()) }

        else -> error("Elasticsearch aggregation group [$alias] returned unsupported aggregate [${_kind()}].")
    }
    check(buckets.size <= size) { tooManyGroups(alias, size) }
    return buckets
}

/** A terms aggregate's buckets: a boolean field's keys read as booleans, as a composite source reads them. */
private fun Aggregate.termsBuckets(alias: String, size: Int, complete: Boolean): List<KeyedBucket> {
    val terms: TermsAggregateBase<*> = when {
        isSterms -> sterms()
        isLterms -> lterms()
        else -> dterms()
    }
    if (complete) terms.requireComplete(alias, size)
    return when {
        isSterms -> sterms().buckets().array()
            .map { KeyedBucket(it.key().nativeValue(), it.docCount(), it.aggregations()) }

        isLterms -> lterms().buckets().array().map {
            KeyedBucket(it.keyAsString()?.toBooleanStrictOrNull() ?: it.key(), it.docCount(), it.aggregations())
        }

        else -> dterms().buckets().array().map { KeyedBucket(it.key(), it.docCount(), it.aggregations()) }
    }
}

private fun TermsAggregateBase<*>.requireComplete(
    alias: String,
    size: Int,
) {
    check((sumOtherDocCount() ?: 0L) == 0L) { tooManyGroups(alias, size) }
}

private fun tooManyGroups(alias: String, size: Int): String =
    "Elasticsearch aggregation group [$alias] has more than $size keys under one root group."

private fun Map<String, Any?>.toRow(values: ObjectNode): ObjectNode = toObjectNode().setAll(values)
