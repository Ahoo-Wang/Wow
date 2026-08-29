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
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import java.time.Duration

class ElasticsearchSnapshotQueryServiceFactory @JvmOverloads constructor(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    private val queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
    private val indexMappingResolver: ElasticsearchIndexMappingResolver =
        ElasticsearchIndexMappingResolver(elasticsearchClient),
    private val schemaSources: List<QuerySchemaSource> = emptyList(),
    private val validationMode: QuerySchemaValidationMode = QuerySchemaValidationMode.COMPATIBLE,
    private val cursorTokenCodec: CursorTokenCodec? = null,
) : AbstractSnapshotQueryServiceFactory() {
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
            cursorTokenCodec = cursorTokenCodec,
        )
    }
}
