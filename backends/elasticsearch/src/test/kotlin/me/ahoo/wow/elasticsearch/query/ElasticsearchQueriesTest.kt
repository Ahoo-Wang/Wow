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

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.elasticsearch.defaultMaxPerRoute
import me.ahoo.wow.elasticsearch.maxConnectionsPerRoute
import me.ahoo.wow.elasticsearch.poolOf
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryBounds
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.store.facet
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The bound of a store's concurrent queries, from the connection pool they draw on (decision D247). */
class ElasticsearchQueriesTest {
    private val clients = mutableListOf<Rest5Client>()

    private fun client(perRoute: Int? = null): Rest5Client = Rest5Client.builder(HttpHost.create("http://localhost:1"))
        .apply {
            perRoute?.let { size ->
                setConnectionManagerCallback { it.setMaxConnPerRoute(size).setMaxConnTotal(size) }
            }
        }
        .build()
        .also(clients::add)

    @AfterEach
    fun close() {
        clients.forEach(Rest5Client::close)
    }

    private fun store(options: Map<String, String>, beans: Map<String, Any>): ElasticsearchStore =
        ElasticsearchStoreProvider().open(
            StoreConfig.of(
                name = "search",
                type = ElasticsearchStoreProvider.TYPE,
                options = options,
                resources = StoreResources.of(beans),
            ),
        ) as ElasticsearchStore

    @Test
    fun `the pool is read from the client's connection manager`() {
        client().maxConnectionsPerRoute().assert().isEqualTo(10)
        client(40).maxConnectionsPerRoute().assert().isEqualTo(40)
        poolOf(Any()).assert().isNull()
        defaultMaxPerRoute(Any()).assert().isNull()
    }

    private fun ElasticsearchStore.pool(): StorePool = checkNotNull(facet<StorePool>())

    @Test
    fun `a store's pool is the one its queries use, which the writes share unless the queries have their own`() {
        val writes = client(40)
        val shared = store(emptyMap(), mapOf("writes" to writes))
        shared.queriesShareWrites.assert().isTrue()
        shared.queryClient.assert().isSameAs(shared.client)
        with(shared.pool()) {
            queryConnections.assert().isEqualTo(40)
            sharedWithAppends.assert().isTrue()
            waitingForConnection().assert().isNull()
        }
        // Queries sharing the writes' pool take a quarter of it.
        StoreQueryBounds.maxConcurrent(shared.pool()).assert().isEqualTo(10)
        val own = store(
            mapOf(
                ElasticsearchStoreProvider.CLIENT.key to "writes",
                ElasticsearchStoreProvider.QUERY_CLIENT.key to "queries"
            ),
            mapOf("writes" to writes, "queries" to client(24)),
        )
        own.queriesShareWrites.assert().isFalse()
        own.queryClient.assert().isNotSameAs(own.client)
        own.pool().sharedWithAppends.assert().isFalse()
        StoreQueryBounds.maxConcurrent(own.pool()).assert().isEqualTo(24)
        // A query client that is the store's own client shares its pool.
        val same = store(
            mapOf(
                ElasticsearchStoreProvider.CLIENT.key to "writes",
                ElasticsearchStoreProvider.QUERY_CLIENT.key to "writes"
            ),
            mapOf("writes" to writes),
        )
        same.queriesShareWrites.assert().isTrue()
        StoreQueryBounds.maxConcurrent(same.pool()).assert().isEqualTo(10)
    }

    @Test
    fun `a store's queries open its snapshot and events queries`() {
        val queries = checkNotNull(store(emptyMap(), mapOf("writes" to client())).facet<StoreQueries>())
        queries.snapshots(StoreQueryOptions.DEFAULT).assert().isNotNull()
        queries.events(StoreQueryOptions.DEFAULT).assert().isNotNull()
    }
}
