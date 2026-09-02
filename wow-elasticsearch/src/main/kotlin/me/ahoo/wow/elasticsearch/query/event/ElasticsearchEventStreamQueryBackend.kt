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

package me.ahoo.wow.elasticsearch.query.event

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackend
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.resolve
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

class ElasticsearchEventStreamQueryBackend(
    override val namedAggregate: NamedAggregate,
    override val elasticsearchClient: ReactiveElasticsearchClient,
    override val filterConverter: AbstractElasticsearchFilterConverter = EventStreamFilterConverter,
    override val queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    override val queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
    private val schemaProvider: QueryModelSchemaProvider =
        defaultSchemaProvider(namedAggregate, elasticsearchClient, filterConverter),
    private val validationMode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
) : AbstractElasticsearchQueryBackend(),
    EventStreamQueryBackend,
    QueryModelSchemaProvider by schemaProvider {
    override val indexName: String = namedAggregate.toEventStreamIndexName()
    override val cursorUniqueField: QueryField = QueryField(MessageRecords.ID)

    override fun resolve(query: ISingleQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: IListQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: IPagedQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(query: ICursorQuery) = schemaProvider.resolve(query, validationMode)

    override fun resolve(filter: FilterExpression) = schemaProvider.resolve(filter, validationMode)

    override fun aggregate(query: AggregationQuery): reactor.core.publisher.Flux<ObjectNode> =
        schemaProvider.resolve(query, validationMode).flatMapMany(::executeAggregation)

    companion object {
        private fun defaultSchemaProvider(
            namedAggregate: NamedAggregate,
            elasticsearchClient: ReactiveElasticsearchClient,
            filterConverter: AbstractElasticsearchFilterConverter,
            mappingResolver: ElasticsearchIndexMappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient),
        ): QueryModelSchemaProvider {
            if (filterConverter !== EventStreamFilterConverter) {
                return object : QueryModelSchemaProvider {
                    override fun schema(): reactor.core.publisher.Mono<me.ahoo.wow.query.schema.QueryModelSchema> =
                        unavailable()

                    override fun refresh(): reactor.core.publisher.Mono<me.ahoo.wow.query.schema.QueryModelSchema> =
                        unavailable()

                    private fun unavailable(): reactor.core.publisher.Mono<me.ahoo.wow.query.schema.QueryModelSchema> =
                        reactor.core.publisher.Mono.error(
                            QuerySchemaUnavailableException(
                                "Elasticsearch query schema is unavailable for custom filter converters.",
                            ),
                        )
                }
            }
            val materialized = namedAggregate.materialize()
            return DefaultQueryModelSchemaProvider(
                context = QuerySchemaContext(materialized, QueryModel.EVENT_STREAM),
                sources = emptyList(),
                adapter = ElasticsearchQuerySchemaAdapter(
                    materialized.toEventStreamIndexName(),
                    mappingResolver,
                    QueryModel.EVENT_STREAM,
                ),
            )
        }
    }
}
