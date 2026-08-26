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

import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.query.AbstractMongoFilterConverter
import me.ahoo.wow.mongo.query.AbstractMongoQueryService
import me.ahoo.wow.mongo.query.MongoProjectionConverter
import me.ahoo.wow.mongo.query.MongoSortConverter
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter
import me.ahoo.wow.mongo.toMaterializedSnapshot
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.resolve
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import org.bson.Document
import org.bson.types.Decimal128
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toFlux

class MongoSnapshotQueryService<S : Any> private constructor(
    override val namedAggregate: NamedAggregate,
    override val collection: MongoCollection<Document>,
    override val converter: AbstractMongoFilterConverter,
    private val schemaProvider: QueryModelSchemaProvider,
    private val validationMode: QuerySchemaValidationMode,
) : AbstractMongoQueryService<MaterializedSnapshot<S>>(),
    SnapshotQueryService<S>,
    QueryModelSchemaProvider by schemaProvider {
    constructor(
        namedAggregate: NamedAggregate,
        collection: MongoCollection<Document>,
        converter: AbstractMongoFilterConverter = SnapshotFilterConverter,
    ) : this(
        namedAggregate,
        collection,
        converter,
        defaultSchemaProvider(namedAggregate, collection),
        QuerySchemaValidationMode.COMPATIBLE,
    )

    internal constructor(
        namedAggregate: NamedAggregate,
        collection: MongoCollection<Document>,
        schemaProvider: QueryModelSchemaProvider,
        validationMode: QuerySchemaValidationMode,
        converter: AbstractMongoFilterConverter = SnapshotFilterConverter,
    ) : this(namedAggregate, collection, converter, schemaProvider, validationMode)

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

    override fun resolve(query: ISingleQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: IListQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: IPagedQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(filter: FilterExpression) = schemaProvider.resolve(filter, validationMode)

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> {
        return schemaProvider.resolve(query, validationMode).flatMapMany { schema ->
            val result = collection.aggregate(
                MongoAggregationCompiler(converter).compile(query, schema.orElse(null)),
            ).toFlux().map { it.toAggregationResult(query) }
            if (query.groupBy.isEmpty()) {
                result.switchIfEmpty(Flux.just(query.emptySummary()))
            } else {
                result
            }
        }
    }

    private fun Document.toAggregationResult(query: AggregationQuery): DynamicDocument {
        query.groupBy.forEach { group ->
            (get(group.alias) as? Decimal128)?.let { this[group.alias] = it.toFiniteDouble(group.alias) }
        }
        query.metrics.forEach { metric ->
            this[metric.alias] = when (metric) {
                is AggregationMetric.Count -> (get(metric.alias) as Number).toLong()
                is AggregationMetric.Numeric -> get(metric.alias).toFiniteDouble(metric.alias)
            }
        }
        return toDynamicDocument()
    }

    private fun AggregationQuery.emptySummary(): DynamicDocument = metrics.associateTo(Document()) { metric ->
        metric.alias to if (metric is AggregationMetric.Count) 0L else null
    }.toDynamicDocument()

    private fun Any?.toFiniteDouble(alias: String): Double? {
        val value = when (this) {
            null -> return null
            is Decimal128 -> bigDecimalValue().toDouble()
            is Number -> toDouble()
            else -> error("Aggregation metric [$alias] must be numeric, but was [${this::class.java.name}].")
        }
        require(value.isFinite()) { "Aggregation metric [$alias] must be finite." }
        return value
    }

    companion object {
        private fun defaultSchemaProvider(
            namedAggregate: NamedAggregate,
            collection: MongoCollection<Document>,
        ): QueryModelSchemaProvider = DefaultQueryModelSchemaProvider(
            context = QuerySchemaContext(namedAggregate.materialize(), QueryModel.SNAPSHOT),
            sources = emptyList(),
            adapter = MongoQuerySchemaAdapter(collection),
        )
    }
}
