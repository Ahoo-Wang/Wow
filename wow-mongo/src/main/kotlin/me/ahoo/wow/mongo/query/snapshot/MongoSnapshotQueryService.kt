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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
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
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.resolve
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class MongoSnapshotQueryService<S : Any> @JvmOverloads constructor(
    override val namedAggregate: NamedAggregate,
    override val collection: MongoCollection<Document>,
    override val converter: AbstractMongoFilterConverter = SnapshotFilterConverter,
    private val schemaProvider: QueryModelSchemaProvider =
        defaultSchemaProvider(namedAggregate, collection, converter),
    private val validationMode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
    protected override val cursorTokenCodec: CursorTokenCodec? = null,
) : AbstractMongoQueryService<MaterializedSnapshot<S>>(),
    SnapshotQueryService<S>,
    QueryModelSchemaProvider by schemaProvider {
    override val name: String
        get() = MongoSnapshotStore.NAME
    override val projectionConverter: MongoProjectionConverter = MongoProjectionConverter(SnapshotFieldConverter)
    override val sortConverter: MongoSortConverter = MongoSortConverter(SnapshotFieldConverter)
    override val cursorUniqueField: String = MessageRecords.AGGREGATE_ID
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

    override fun resolve(query: ICursorQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(filter: FilterExpression) = schemaProvider.resolve(filter, validationMode)

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> {
        return schemaProvider.resolve(query, validationMode).flatMapMany(::executeAggregation)
    }

    companion object {
        private fun defaultSchemaProvider(
            namedAggregate: NamedAggregate,
            collection: MongoCollection<Document>,
            converter: AbstractMongoFilterConverter,
        ): QueryModelSchemaProvider {
            if (converter !== SnapshotFilterConverter) {
                return object : QueryModelSchemaProvider {
                    override fun schema(): Mono<me.ahoo.wow.query.schema.QueryModelSchema> = unavailable()

                    override fun refresh(): Mono<me.ahoo.wow.query.schema.QueryModelSchema> = unavailable()

                    private fun unavailable(): Mono<me.ahoo.wow.query.schema.QueryModelSchema> = Mono.error(
                        QuerySchemaUnavailableException(
                            "MongoDB query schema is unavailable for custom filter converters.",
                        ),
                    )
                }
            }
            return DefaultQueryModelSchemaProvider(
                context = QuerySchemaContext(namedAggregate.materialize(), QueryModel.SNAPSHOT),
                sources = emptyList(),
                adapter = MongoQuerySchemaAdapter(collection),
            )
        }
    }
}
