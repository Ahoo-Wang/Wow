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

package me.ahoo.wow.elasticsearch.events

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/** The event search Sink as a provider (DG-6): found on the classpath, available only over an Elasticsearch store. */
class ElasticsearchSinkProviderTest {
    private val restClient: Rest5Client = Rest5Client.builder(HttpHost.create("http://localhost:1")).build()
    private val store = ElasticsearchStoreProvider().open(
        StoreConfig.of(
            name = "search",
            type = ElasticsearchStoreProvider.TYPE,
            resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
        ),
    )

    @Test
    fun `the provider should be found on the classpath`() {
        ServiceLoader.load(SinkProvider::class.java).map { it.type }.assert().contains(ElasticsearchStoreProvider.TYPE)
    }

    @Test
    fun `it should be available over an Elasticsearch store alone, and write that store's event indices`() {
        val provider = ElasticsearchSinkProvider()
        provider.requirement.assert().contains("query.events.store")
        provider.available(SinkConfig.of("elasticsearch", ElasticsearchStoreProvider.TYPE)).assert().isFalse()
        val config = SinkConfig.of(
            "stores.search",
            ElasticsearchStoreProvider.TYPE,
            streams = setOf(AggregateType("sales", "order")),
            store = store,
        )
        provider.available(config).assert().isTrue()
        val sinks = provider.open(config)
        sinks.processor.assert().isEqualTo(ElasticsearchEventSinks.PROCESSOR)
        sinks.sinkOf("sales", "order").assert().isInstanceOf(ElasticsearchEventSink::class.java)
        sinks.toString().assert().contains("search")
    }
}
