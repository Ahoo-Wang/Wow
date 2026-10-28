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
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchIndices
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.elasticsearch.maxConnectionsPerRoute
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.query.SnapshotFields
import me.ahoo.wow.spi.store.StorePool
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The queries of an Elasticsearch store, its [StoreQueries] facet: its snapshot indices, each aggregate type's state
 * fields mapped from its state schema ([ElasticsearchStateMappings]) as the store's `ddl` says, and its events indices
 * ([ElasticsearchEventQueries]).
 */
internal class ElasticsearchQueries(private val store: ElasticsearchStore) : StoreQueries {
    override fun snapshots(options: StoreQueryOptions): SnapshotQueries =
        ElasticsearchSnapshotQueries(store.queryClient, store.names, options.indexDdl)

    /**
     * The `events` queries of the store: over the events indices its event search Sink writes, for the aggregate types
     * whose `events` queries the host routes to it (`wow.aggregates.[<context>.<aggregate>].query.events.store`); its
     * logs are elsewhere.
     */
    override fun events(options: StoreQueryOptions): EventQueries =
        ElasticsearchEventQueries(store.queryClient, store.names, options.indexDdl)
}

/**
 * The HTTP pool of an Elasticsearch store, its [StorePool] facet: the per-route connections of the pool its queries
 * use (`query-client`'s, else the writes'), which the writes share unless the queries have a client of their own.
 * With the default wait `SNAPSHOT` a command waits for its snapshot's write, so the queries' bound keeps the writes'
 * connections ([me.ahoo.wow.query.store.StoreQueryBounds]). The low-level client counts no waiters.
 */
internal class ElasticsearchPool(private val store: ElasticsearchStore) : StorePool {
    override val queryConnections: Int?
        get() = (store.queryRestClient ?: store.restClient).maxConnectionsPerRoute().takeIf { it > 0 }

    override val sharedWithAppends: Boolean get() = store.queriesShareWrites

    override fun waitingForConnection(): Int? = null
}

/**
 * The snapshot queries of one Elasticsearch store: each aggregate type's backend over its snapshot index, and the
 * mapping of its state fields. Under `auto` an index's state fields are mapped as its schema is first loaded, and at
 * startup ([manageIndexes]), which also creates a missing index ([prepare]); under `validate` the missing ones are
 * reported; under `none` nothing is touched.
 */
internal class ElasticsearchSnapshotQueries(
    private val client: ElasticsearchAsyncClient,
    private val names: ElasticsearchNames,
    private val indexDdl: Ddl = Ddl.AUTO,
) : SnapshotQueries {
    private val mappingResolver = ElasticsearchIndexMappingResolver(client) { ElasticsearchSnapshotIndex.typeMapping() }
    private val stateMappings =
        ElasticsearchStateMappings(client, mappingResolver, ElasticsearchPayload.Root(SnapshotFields.STATE))
    private val bindings = ConcurrentHashMap<AggregateType, QueryBackendBinding<QueryBackend>>()

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = bindings.computeIfAbsent(type) {
        val index = names.snapshots(type.context, type.name)
        QueryBackendBinding(
            ElasticsearchQueryBackend(type, client, index, SnapshotDocuments),
            ElasticsearchQuerySchemaAdapter(index, mappingResolver, QueryModel.SNAPSHOT) { logical ->
                if (indexDdl.creates) stateMappings.manage(index, logical)
            },
        )
    }

    override val managesIndexes: Boolean get() = indexDdl.checks

    override fun prepare(type: AggregateType) {
        if (!indexDdl.creates) return
        runBlocking {
            ElasticsearchIndices(client).ensure(
                names.snapshots(type.context, type.name),
                ElasticsearchSnapshotIndex.KIND,
                ElasticsearchSnapshotIndex.FORMAT,
                ElasticsearchSnapshotIndex.DEFINITION,
                indexDdl,
            )
        }
    }

    override fun manageIndexes(
        type: AggregateType,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): QueryIndexReport = runBlocking {
        stateMappings.manage(
            names.snapshots(type.context, type.name),
            schema.definition,
            indexDdl.creates
        )
    }

    /** The snapshot documents as snapshot records. */
    private object SnapshotDocuments : ElasticsearchQueryDocuments {
        override val tieBreakers: List<String> = listOf(SnapshotFields.TENANT_ID, SnapshotFields.AGGREGATE_ID)

        override val storeFields: List<String> = ElasticsearchSnapshotDocuments.STORE_FIELDS

        override fun record(source: ObjectNode): ObjectNode = ElasticsearchSnapshotDocuments.record(source)
    }
}
