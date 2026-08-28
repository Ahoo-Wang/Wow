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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import java.time.Duration

class ElasticsearchSnapshotQueryServiceFactory(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val queryBatchSize: Int,
    private val queryKeepAlive: Duration,
    private val schemaSources: List<QuerySchemaSource>,
    private val validationMode: QuerySchemaValidationMode,
) : AbstractSnapshotQueryServiceFactory() {
    private var indexMappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient)

    constructor(elasticsearchClient: ReactiveElasticsearchClient) : this(
        elasticsearchClient = elasticsearchClient,
        queryBatchSize = DEFAULT_SEARCH_BATCH_SIZE,
        queryKeepAlive = DEFAULT_PIT_KEEP_ALIVE,
        schemaSources = emptyList(),
        validationMode = QuerySchemaValidationMode.COMPATIBLE,
    )

    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
    ) : this(
        elasticsearchClient = elasticsearchClient,
        queryBatchSize = queryBatchSize,
        queryKeepAlive = queryKeepAlive,
        schemaSources = emptyList(),
        validationMode = QuerySchemaValidationMode.COMPATIBLE,
    )

    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
        indexMappingResolver: ElasticsearchIndexMappingResolver,
    ) : this(
        elasticsearchClient = elasticsearchClient,
        queryBatchSize = queryBatchSize,
        queryKeepAlive = queryKeepAlive,
        schemaSources = emptyList(),
        validationMode = QuerySchemaValidationMode.COMPATIBLE,
    ) {
        this.indexMappingResolver = indexMappingResolver
    }

    constructor(
        elasticsearchClient: ReactiveElasticsearchClient,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
        indexMappingResolver: ElasticsearchIndexMappingResolver,
        schemaSources: List<QuerySchemaSource>,
        validationMode: QuerySchemaValidationMode,
    ) : this(
        elasticsearchClient = elasticsearchClient,
        queryBatchSize = queryBatchSize,
        queryKeepAlive = queryKeepAlive,
        schemaSources = schemaSources,
        validationMode = validationMode,
    ) {
        this.indexMappingResolver = indexMappingResolver
    }

    override fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> {
        val materialized = namedAggregate.materialize()
        val indexName = materialized.toSnapshotIndexName()
        val provider = DefaultQueryModelSchemaProvider(
            context = QuerySchemaContext(materialized, QueryModel.SNAPSHOT),
            sources = schemaSources,
            adapter = ElasticsearchQuerySchemaAdapter(indexName, indexMappingResolver),
        )
        return ElasticsearchSnapshotQueryService<Any>(
            namedAggregate = materialized,
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = queryBatchSize,
            queryKeepAlive = queryKeepAlive,
            schemaProvider = provider,
            validationMode = validationMode,
        )
    }
}
