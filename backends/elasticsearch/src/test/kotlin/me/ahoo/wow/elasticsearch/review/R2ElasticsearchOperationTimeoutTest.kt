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

package me.ahoo.wow.elasticsearch.review

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Review r2 (configuration keys): `wow.elasticsearch.operation-timeout` of zero or below opens the store and starts
 * the node. The store builds its client lazily (`ElasticsearchStore.client`, `by lazy`), so the refusal of
 * `BoundedTransport` ("An Elasticsearch store's operation timeout is positive.") comes at the store's first request,
 * every request after it failing alike, as a bare `IllegalArgumentException` naming no key. The option is read when the
 * store opens, which is where `reindex-requests-per-second` is checked: the timeout should be refused there too.
 */
class R2ElasticsearchOperationTimeoutTest {
    private val restClient: Rest5Client = Rest5Client.builder(HttpHost.create("http://localhost:1")).build()

    @ParameterizedTest
    @ValueSource(strings = ["0s", "-5s"])
    fun `an operation timeout that is not positive should refuse the store when it opens, naming the key`(
        timeout: String,
    ) {
        val config = StoreConfig.of(
            name = "search",
            type = ElasticsearchStoreProvider.TYPE,
            options = mapOf(ElasticsearchStoreProvider.OPERATION_TIMEOUT.key to timeout),
            resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
        )
        val failure = assertThrows<WowConfigurationException> { ElasticsearchStoreProvider().open(config).close() }
        failure.key.assert().isEqualTo("wow.stores.search.operation-timeout")
    }
}
