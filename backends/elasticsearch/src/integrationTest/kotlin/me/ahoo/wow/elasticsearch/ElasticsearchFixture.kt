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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.hc.core5.http.HttpHost
import java.util.UUID

/** The shared Elasticsearch container of the integration tests, a client over it, and fresh names for each test. */
internal object ElasticsearchFixture {
    val restClient: Rest5Client by lazy {
        Rest5Client.builder(HttpHost.create("http://" + WowContainers.elasticsearch.httpHostAddress)).build()
    }

    val client: ElasticsearchAsyncClient by lazy { ElasticsearchStore.client(restClient) }

    /** A context no other test uses, so each test has its own indices. */
    fun newContext(): String = "it_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)

    /** The configuration of a store over the container, refreshing on every write so a search sees it at once. */
    fun config(
        ddl: Ddl = Ddl.AUTO,
        options: Map<String, String> = mapOf(ElasticsearchStoreProvider.REFRESH.key to "immediate"),
    ): StoreConfig = StoreConfig.of(
        name = "search",
        type = ElasticsearchStoreProvider.TYPE,
        ddl = ddl,
        options = options,
        resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
    )

    fun store(ddl: Ddl = Ddl.AUTO): ElasticsearchStore =
        ElasticsearchStoreProvider().open(config(ddl)) as ElasticsearchStore

    val names: ElasticsearchNames = ElasticsearchNames()

    val REFRESH: Refresh = Refresh.True

    private const val CONTEXT_SUFFIX = 12
}
