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
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The bound of a store's concurrent queries, from the connection pool they draw on (decision D247). */
class ElasticsearchQueryProviderTest {
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
    fun `queries sharing the writes' pool take a quarter of it, at least two`() {
        ElasticsearchQueryProvider.maxConcurrent(40, shared = true).assert().isEqualTo(10)
        ElasticsearchQueryProvider.maxConcurrent(10, shared = true).assert().isEqualTo(2)
        ElasticsearchQueryProvider.maxConcurrent(4, shared = true).assert().isEqualTo(2)
        ElasticsearchQueryProvider.maxConcurrent(1, shared = true).assert().isEqualTo(2)
    }

    @Test
    fun `a pool of the queries' own is theirs whole`() {
        ElasticsearchQueryProvider.maxConcurrent(40, shared = false).assert().isEqualTo(40)
        ElasticsearchQueryProvider.maxConcurrent(0, shared = false).assert().isEqualTo(1)
    }

    @Test
    fun `the pool is read from the client's connection manager`() {
        client().maxConnectionsPerRoute().assert().isEqualTo(10)
        client(40).maxConnectionsPerRoute().assert().isEqualTo(40)
        poolOf(Any()).assert().isNull()
        defaultMaxPerRoute(Any()).assert().isNull()
    }

    @Test
    fun `a store's bound follows the pool its queries use`() {
        val provider = ElasticsearchQueryProvider()
        val writes = client(40)
        val shared = store(emptyMap(), mapOf("writes" to writes))
        shared.queriesShareWrites.assert().isTrue()
        shared.queryClient.assert().isSameAs(shared.client)
        provider.maxConcurrent(shared).assert().isEqualTo(10)
        val own = store(
            mapOf(ElasticsearchStoreProvider.CLIENT to "writes", ElasticsearchStoreProvider.QUERY_CLIENT to "queries"),
            mapOf("writes" to writes, "queries" to client(24)),
        )
        own.queriesShareWrites.assert().isFalse()
        own.queryClient.assert().isNotSameAs(own.client)
        provider.maxConcurrent(own).assert().isEqualTo(24)
        // A query client that is the store's own client shares its pool.
        val same = store(
            mapOf(ElasticsearchStoreProvider.CLIENT to "writes", ElasticsearchStoreProvider.QUERY_CLIENT to "writes"),
            mapOf("writes" to writes),
        )
        same.queriesShareWrites.assert().isTrue()
        provider.maxConcurrent(same).assert().isEqualTo(10)
    }
}
