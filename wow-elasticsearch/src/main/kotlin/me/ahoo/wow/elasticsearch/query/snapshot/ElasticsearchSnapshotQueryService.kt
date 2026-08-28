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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.resolve
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class ElasticsearchSnapshotQueryService<S : Any> private constructor(
    override val namedAggregate: NamedAggregate,
    override val elasticsearchClient: ReactiveElasticsearchClient,
    override val filterConverter: AbstractElasticsearchFilterConverter,
    override val queryBatchSize: Int,
    override val queryKeepAlive: Duration,
    private val schemaProvider: QueryModelSchemaProvider,
    private val validationMode: QuerySchemaValidationMode,
) : AbstractElasticsearchQueryService<MaterializedSnapshot<S>>(),
    SnapshotQueryService<S>,
    QueryModelSchemaProvider by schemaProvider {
    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        filterConverter: AbstractElasticsearchFilterConverter = SnapshotFilterConverter,
    ) : this(
        namedAggregate,
        elasticsearchClient,
        filterConverter,
        DEFAULT_SEARCH_BATCH_SIZE,
        DEFAULT_PIT_KEEP_ALIVE,
        defaultSchemaProvider(namedAggregate, elasticsearchClient, filterConverter),
        QuerySchemaValidationMode.COMPATIBLE,
    )

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        filterConverter: AbstractElasticsearchFilterConverter,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
    ) : this(
        namedAggregate,
        elasticsearchClient,
        filterConverter,
        queryBatchSize,
        queryKeepAlive,
        defaultSchemaProvider(namedAggregate, elasticsearchClient, filterConverter),
        QuerySchemaValidationMode.COMPATIBLE,
    )

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        filterConverter: AbstractElasticsearchFilterConverter,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
        indexMappingResolver: ElasticsearchIndexMappingResolver,
    ) : this(
        namedAggregate,
        elasticsearchClient,
        filterConverter,
        queryBatchSize,
        queryKeepAlive,
        defaultSchemaProvider(
            namedAggregate,
            elasticsearchClient,
            filterConverter,
            indexMappingResolver,
        ),
        QuerySchemaValidationMode.COMPATIBLE,
    )

    internal constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        schemaProvider: QueryModelSchemaProvider,
        validationMode: QuerySchemaValidationMode,
        filterConverter: AbstractElasticsearchFilterConverter = SnapshotFilterConverter,
        queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
        queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
    ) : this(
        namedAggregate,
        elasticsearchClient,
        filterConverter,
        queryBatchSize,
        queryKeepAlive,
        schemaProvider,
        validationMode,
    )

    override val name: String
        get() = ElasticsearchSnapshotStore.NAME
    override val indexName: String = namedAggregate.toSnapshotIndexName()

    private val snapshotType = JsonSerializer.typeFactory
        .constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType,
        )

    override fun toTypedResult(document: DynamicDocument): MaterializedSnapshot<S> = document.convert(snapshotType)

    override fun resolve(query: ISingleQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: IListQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: IPagedQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(filter: FilterExpression) = schemaProvider.resolve(filter, validationMode)

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
        schemaProvider.resolve(query, validationMode).flatMapMany(::executeAggregation)

    companion object {
        private fun defaultSchemaProvider(
            namedAggregate: NamedAggregate,
            elasticsearchClient: ReactiveElasticsearchClient,
            filterConverter: AbstractElasticsearchFilterConverter,
            mappingResolver: ElasticsearchIndexMappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient),
        ): QueryModelSchemaProvider {
            if (filterConverter !== SnapshotFilterConverter) {
                return object : QueryModelSchemaProvider {
                    override fun schema(): Mono<me.ahoo.wow.query.schema.QueryModelSchema> = unavailable()

                    override fun refresh(): Mono<me.ahoo.wow.query.schema.QueryModelSchema> = unavailable()

                    private fun unavailable(): Mono<me.ahoo.wow.query.schema.QueryModelSchema> = Mono.error(
                        QuerySchemaUnavailableException(
                            "Elasticsearch query schema is unavailable for custom filter converters.",
                        ),
                    )
                }
            }
            val materialized = namedAggregate.materialize()
            val indexName = materialized.toSnapshotIndexName()
            return DefaultQueryModelSchemaProvider(
                context = QuerySchemaContext(materialized, QueryModel.SNAPSHOT),
                sources = emptyList(),
                adapter = ElasticsearchQuerySchemaAdapter(indexName, mappingResolver),
            )
        }
    }
}
