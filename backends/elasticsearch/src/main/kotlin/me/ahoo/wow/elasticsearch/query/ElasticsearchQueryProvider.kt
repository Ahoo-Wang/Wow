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
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.elasticsearch.maxConnectionsPerRoute
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotDocuments
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.query.SnapshotFields
import me.ahoo.wow.spi.store.Store
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The queries of an Elasticsearch store (work package 6.7): its snapshot indices (decision D131), each aggregate
 * type's state fields mapped from its state schema ([ElasticsearchStateMappings]) as `wow.query.index.ddl` says, and
 * its events indices (decision D235, [ElasticsearchEventQueries]).
 * Found by `ServiceLoader`.
 */
internal class ElasticsearchQueryProvider : StoreQueryProvider {
    override val type: String = ElasticsearchStoreProvider.TYPE

    override fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries {
        val elasticsearch = store as ElasticsearchStore
        return ElasticsearchSnapshotQueries(elasticsearch.queryClient, elasticsearch.names, options.indexDdl)
    }

    /**
     * The `events` queries of [store] (decision D235): over the events indices its event search Sink writes, for the
     * aggregate types whose `events` queries the host routes to it (`wow.aggregates.[<context>.<aggregate>].query
     * .events.store`); its logs are elsewhere (decision D13).
     */
    override fun events(store: Store, options: StoreQueryOptions): EventQueries {
        val elasticsearch = store as ElasticsearchStore
        return ElasticsearchEventQueries(elasticsearch.queryClient, elasticsearch.names, options.indexDdl)
    }

    /**
     * How many queries of [store] run at once when its configuration names no limit (decision D247): from the
     * per-route connections of the HTTP pool its queries use. With the default wait `SNAPSHOT` (D69) a command waits
     * for its snapshot's write, so queries that share the writes' pool take a quarter of it (at least [MIN_SHARED])
     * and never hold the connections the writes wait for; a pool of the queries' own (`query-client`) is theirs whole.
     * The bulkhead of `:query` (R3-B) applies it.
     */
    override fun maxConcurrent(store: Store): Int {
        val elasticsearch = store as ElasticsearchStore
        val pool = (elasticsearch.queryRestClient ?: elasticsearch.restClient).maxConnectionsPerRoute()
        return maxConcurrent(pool, elasticsearch.queriesShareWrites)
    }

    companion object {
        /** The fewest queries a store whose queries share the writes' pool runs at once. */
        const val MIN_SHARED: Int = 2

        /** The share of the writes' pool the queries take: one connection in four. */
        const val SHARED_FRACTION: Int = 4

        /** The bound of a pool of [perRoute] connections per node: a share of it when the writes [shared] it. */
        fun maxConcurrent(perRoute: Int, shared: Boolean): Int =
            if (shared) maxOf(MIN_SHARED, perRoute / SHARED_FRACTION) else perRoute.coerceAtLeast(1)
    }
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
                if (indexDdl == Ddl.AUTO) stateMappings.manage(index, logical)
            },
        )
    }

    override val managesIndexes: Boolean get() = indexDdl != Ddl.NONE

    override fun prepare(type: AggregateType) {
        if (indexDdl != Ddl.AUTO) return
        runBlocking {
            ElasticsearchIndices(client).ensure(
                names.snapshots(type.context, type.name),
                ElasticsearchSnapshotIndex.KIND,
                ElasticsearchSnapshotIndex.FORMAT,
                ElasticsearchSnapshotIndex.DEFINITION,
                Ddl.AUTO,
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
            indexDdl == Ddl.AUTO
        )
    }

    /** The snapshot documents as snapshot records. */
    private object SnapshotDocuments : ElasticsearchQueryDocuments {
        override val tieBreakers: List<String> = listOf(SnapshotFields.TENANT_ID, SnapshotFields.AGGREGATE_ID)

        override val storeFields: List<String> = ElasticsearchSnapshotDocuments.STORE_FIELDS

        override fun record(source: ObjectNode): ObjectNode = ElasticsearchSnapshotDocuments.record(source)
    }
}
