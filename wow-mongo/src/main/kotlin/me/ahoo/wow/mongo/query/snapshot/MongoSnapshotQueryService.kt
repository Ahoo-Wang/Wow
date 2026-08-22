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

package me.ahoo.wow.mongo.query.snapshot

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.query.AbstractMongoQueryService
import me.ahoo.wow.mongo.query.MongoProjectionConverter
import me.ahoo.wow.mongo.query.MongoSortConverter
import me.ahoo.wow.mongo.toMaterializedSnapshot
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toFlux

class MongoSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val collection: MongoCollection<Document>,
    override val converter: ConditionConverter<Bson> = SnapshotConditionConverter
) : AbstractMongoQueryService<MaterializedSnapshot<S>>(), SnapshotQueryService<S> {
    override val name: String
        get() = MongoSnapshotStore.NAME
    override val projectionConverter: MongoProjectionConverter = MongoProjectionConverter(SnapshotFieldConverter)
    override val sortConverter: MongoSortConverter = MongoSortConverter(SnapshotFieldConverter)
    private val snapshotType = JsonSerializer.typeFactory
        .constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
        )

    override fun toTypedResult(document: Document): MaterializedSnapshot<S> {
        return document.toMaterializedSnapshot(snapshotType)
    }

    override fun toDynamicDocument(document: Document): DynamicDocument {
        return document.replacePrimaryKeyToAggregateId().toDynamicDocument()
    }

    override fun aggregate(aggregationQuery: AggregationQuery): Flux<Map<String, Any?>> = Flux.defer {
        val pipeline = aggregationQuery.toMongoPipeline(converter)
        collection.aggregate(pipeline)
            .toFlux()
            .map<Map<String, Any?>> { document ->
                LinkedHashMap<String, Any?>(document).apply {
                    aggregationQuery.groupBy.filterIsInstance<AggregationGroup.Terms>().forEach { group ->
                        val value = get(group.alias)
                        if (value is Int) {
                            put(group.alias, value.toLong())
                        }
                    }
                }
            }
            .let { result ->
                if (aggregationQuery.groupBy.isEmpty()) {
                    result.switchIfEmpty(Flux.just(aggregationQuery.emptyGlobalResult()))
                } else {
                    result
                }
            }
    }
}

private fun AggregationQuery.toMongoPipeline(conditionConverter: ConditionConverter<Bson>): List<Bson> = buildList {
    val fieldConverter = SnapshotFieldConverter
    add(Aggregates.match(Filters.and(toMongoFilters(conditionConverter, fieldConverter))))
    add(Document("\$group", toMongoGroup(fieldConverter)))
    add(Document("\$project", toMongoProjection()))

    if (groupBy.isNotEmpty()) {
        add(Document("\$sort", toMongoSort()))
        add(Document("\$limit", limit))
    }
}

private fun AggregationQuery.toMongoFilters(
    conditionConverter: ConditionConverter<Bson>,
    fieldConverter: SnapshotFieldConverter,
): List<Bson> = buildList {
    add(conditionConverter.convert(condition))
    groupBy.forEach { group ->
        val field = fieldConverter.convert(group.field)
        add(Filters.exists(field, true))
        add(Filters.ne(field, null))
    }
}

private fun AggregationQuery.toMongoGroup(fieldConverter: SnapshotFieldConverter): Document {
    val groupId = groupBy.takeIf { it.isNotEmpty() }?.let {
        Document().apply {
            groupBy.forEach { group -> put(group.alias, group.toMongoExpression(fieldConverter)) }
        }
    }
    return Document("_id", groupId).apply {
        metrics.forEach { metric -> put(metric.alias, metric.toMongoAccumulator(fieldConverter)) }
    }
}

private fun AggregationQuery.toMongoProjection(): Document = Document("_id", 0).apply {
    groupBy.forEach { group ->
        val value = "\$_id.${group.alias}"
        put(group.alias, if (group is AggregationGroup.DateHistogram) Document("\$toLong", value) else value)
    }
    metrics.forEach { metric ->
        val value = "\$${metric.alias}"
        put(
            metric.alias,
            if (metric is AggregationMetric.Count) {
                Document("\$toLong", value)
            } else {
                Document("\$convert", Document("input", value).append("to", "double"))
            },
        )
    }
}

private fun AggregationQuery.toMongoSort(): Document = Document().apply {
    effectiveSort().forEach { sort -> put(sort.field, sort.direction.toMongoDirection()) }
}

private fun AggregationGroup.toMongoExpression(fieldConverter: SnapshotFieldConverter): Any {
    val field = "\$${fieldConverter.convert(field)}"
    return when (this) {
        is AggregationGroup.Terms -> field
        is AggregationGroup.Histogram -> Document(
            "\$add",
            listOf(
                offset,
                Document(
                    "\$multiply",
                    listOf(
                        Document(
                            "\$floor",
                            Document(
                                "\$divide",
                                listOf(
                                    Document("\$subtract", listOf(field.toMongoDouble(), offset)),
                                    interval,
                                ),
                            ),
                        ),
                        interval,
                    ),
                ),
            ),
        )

        is AggregationGroup.DateHistogram -> Document(
            "\$dateTrunc",
            Document("date", field.toMongoDate())
                .append("unit", unit.toMongoDateUnit())
                .append("timezone", timeZone)
                .also { options ->
                    if (unit == AggregationDateUnit.WEEK) {
                        options.append("startOfWeek", "monday")
                    }
                },
        )
    }
}

private fun AggregationMetric.toMongoAccumulator(fieldConverter: SnapshotFieldConverter): Document = when (this) {
    is AggregationMetric.Count -> Document("\$sum", 1)
    is AggregationMetric.Sum -> Document("\$sum", "\$${fieldConverter.convert(field)}".toMongoDouble())
    is AggregationMetric.Avg -> Document("\$avg", "\$${fieldConverter.convert(field)}".toMongoDouble())
    is AggregationMetric.Min -> Document("\$min", "\$${fieldConverter.convert(field)}".toMongoDouble())
    is AggregationMetric.Max -> Document("\$max", "\$${fieldConverter.convert(field)}".toMongoDouble())
}

private fun String.toMongoDouble(): Document =
    Document("\$convert", Document("input", this).append("to", "double"))

private fun String.toMongoDate(): Document =
    Document("\$convert", Document("input", this).append("to", "date"))

private fun AggregationDateUnit.toMongoDateUnit(): String = name.lowercase()

private fun Sort.Direction.toMongoDirection(): Int = if (this == Sort.Direction.ASC) 1 else -1

private fun AggregationQuery.effectiveSort(): List<Sort> = buildList {
    addAll(sort)
    val sortedAliases = sort.mapTo(hashSetOf(), Sort::field)
    groupBy.map(AggregationGroup::alias)
        .filterNot(sortedAliases::contains)
        .forEach { add(Sort(it, Sort.Direction.ASC)) }
}

private fun AggregationQuery.emptyGlobalResult(): Map<String, Any?> = buildMap {
    metrics.forEach { metric ->
        put(
            metric.alias,
            when (metric) {
                is AggregationMetric.Count -> 0L
                is AggregationMetric.Sum -> 0.0
                is AggregationMetric.Avg,
                is AggregationMetric.Min,
                is AggregationMetric.Max,
                -> null
            },
        )
    }
}
