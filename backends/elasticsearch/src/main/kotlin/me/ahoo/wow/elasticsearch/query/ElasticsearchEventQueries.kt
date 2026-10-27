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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventDocuments
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventIndex
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.query.EventStreamFields
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The `events` queries of one Elasticsearch store (decision D235, work package 6.7): each aggregate type's backend
 * over the events index its event search Sink writes ([ElasticsearchNames.events]), one record per stream as stored.
 * The filters, sorts, pages and aggregations are the snapshot queries' ([ElasticsearchQueryBackend]); an event
 * condition is a `nested` query on `events`, an expanded aggregation a `nested` aggregation.
 *
 * Under the store's `ddl=auto` the fields of the events' bodies are mapped from their schemas as the aggregate's
 * schema is first loaded ([ElasticsearchPayload.EventBodies]): a field the events declare alike is mapped once, one
 * they declare with different types is not; under `validate` and `none` nothing is mapped. The index itself is the
 * Sink's to create: before its first write every query answers nothing.
 */
internal class ElasticsearchEventQueries(
    private val client: ElasticsearchAsyncClient,
    private val names: ElasticsearchNames,
    private val indexDdl: Ddl = Ddl.AUTO,
) : EventQueries {
    private val mappingResolver = ElasticsearchIndexMappingResolver(client) { ElasticsearchEventIndex.typeMapping() }
    private val bodyMappings = ElasticsearchStateMappings(client, mappingResolver, ElasticsearchPayload.EventBodies)
    private val bindings = ConcurrentHashMap<AggregateType, QueryBackendBinding<QueryBackend>>()

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = bindings.computeIfAbsent(type) {
        val index = names.events(type.context, type.name)
        QueryBackendBinding(
            ElasticsearchQueryBackend(type, client, index, StreamDocuments),
            ElasticsearchQuerySchemaAdapter(index, mappingResolver, QueryModel.EVENTS) { logical ->
                if (indexDdl == Ddl.AUTO) bodyMappings.manage(index, logical)
            },
        )
    }

    /** The stream documents as stream records, in their key's order: tenant, aggregate, version. */
    private object StreamDocuments : ElasticsearchQueryDocuments {
        override val tieBreakers: List<String> = listOf(
            EventStreamFields.TENANT_ID,
            EventStreamFields.AGGREGATE_ID,
            EventStreamFields.VERSION,
        )

        override val storeFields: List<String> = emptyList()

        override fun record(source: ObjectNode): ObjectNode = ElasticsearchEventDocuments.record(source)
    }
}
