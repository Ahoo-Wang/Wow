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

import com.mongodb.client.model.Collation
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import me.ahoo.wow.mongo.query.AbstractMongoQueryService
import me.ahoo.wow.mongo.query.MongoProjectionConverter
import me.ahoo.wow.mongo.query.MongoSortConverter
import me.ahoo.wow.mongo.toMaterializedSnapshot
import me.ahoo.wow.query.AggregationFieldCatalog
import me.ahoo.wow.query.snapshot.AggregationQueryValidator
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import org.bson.Document
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toFlux

class MongoSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val collection: MongoCollection<Document>,
    override val converter: AbstractMongoFilterConverter = SnapshotFilterConverter
) : AbstractMongoQueryService<MaterializedSnapshot<S>>(), SnapshotQueryService<S> {
    override val name: String
        get() = MongoSnapshotStore.NAME
    override val projectionConverter: MongoProjectionConverter = MongoProjectionConverter(SnapshotFieldConverter)
    override val sortConverter: MongoSortConverter = MongoSortConverter(SnapshotFieldConverter)
    private val stateType = namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
    private val temporalAggregationFields = AggregationFieldCatalog.scan(stateType).temporalPaths
    private val snapshotType = JsonSerializer.typeFactory
        .constructParametricType(
            MaterializedSnapshot::class.java,
            stateType,
        )

    override fun toTypedResult(document: Document): MaterializedSnapshot<S> {
        return document.toMaterializedSnapshot(snapshotType)
    }

    override fun toDynamicDocument(document: Document): DynamicDocument {
        return document.replacePrimaryKeyToAggregateId().toDynamicDocument()
    }

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> = Flux.defer {
        AggregationQueryValidator.validate(query, namedAggregate)
        collection.aggregate(MongoAggregationCompiler.compile(query, converter, temporalAggregationFields))
            .collation(SIMPLE_COLLATION)
            .toFlux()
            .map<DynamicDocument> { document -> document.normalizeAggregationResult(query).toDynamicDocument() }
            .let { result ->
                if (query.groupBy.isEmpty()) {
                    result.switchIfEmpty(Flux.just(query.emptyGlobalResult().toDynamicDocument()))
                } else {
                    result
                }
            }
    }

    private companion object {
        val SIMPLE_COLLATION: Collation = Collation.builder().locale("simple").build()
    }
}

private fun Document.normalizeAggregationResult(query: AggregationQuery): Document = apply {
    query.groupBy.filterIsInstance<AggregationGroup.Terms>().forEach { group ->
        when (val value = get(group.alias)) {
            is Byte -> put(group.alias, value.toLong())
            is Short -> put(group.alias, value.toLong())
            is Int -> put(group.alias, value.toLong())
            is Float -> put(group.alias, value.toDouble())
            is Decimal128 -> put(group.alias, value.bigDecimalValue().toDouble())
        }
    }
    query.metrics.filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
        val value = get(metric.alias) as? Number ?: return@forEach
        require(value.toDouble().isFinite()) {
            "MongoDB aggregation metric [${metric.alias}] returned a non-finite value."
        }
    }
}

private fun AggregationQuery.emptyGlobalResult(): Document = Document().apply {
    metrics.forEach { metric ->
        put(
            metric.alias,
            when (metric) {
                is AggregationMetric.Count -> 0L
                is AggregationMetric.Numeric -> if (metric.function == AggregationFunction.SUM) 0.0 else null
            },
        )
    }
}
