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

package me.ahoo.wow.spring.boot

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetBindings
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetConfig
import me.ahoo.wow.query.dataset.QueryDatasetProvider
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The ClickHouse dataset backend, found by `ServiceLoader` and opened over the application's client, or over one of its
 * own that the node's bindings close ([HostDatasets.provided]).
 */
class HostClickHouseDatasetsTest {
    private val client: Client = Client.Builder().addEndpoint("http://127.0.0.1:1").setUsername("default").build()

    private val providers = HostDatasets.providers(javaClass.classLoader)

    private fun provided(
        resources: StoreResources = StoreResources.of(mapOf("client" to client)),
        vararg settings: Pair<String, String>,
    ) = HostDatasets.provided(providers, resources) { mapOf(*settings) }

    @Test
    fun `without the application's client the endpoint should give the backend its own, which the node closes`() {
        val own = provided(StoreResources.NONE, "endpoint" to "http://127.0.0.1:1", "sink.batch-size" to "5")
        own.assert().isInstanceOf(AutoCloseable::class.java)
        HostDatasetBindings(own, provided = true).close()
        val application = provided()
        application.assert().isInstanceOf(ClickHouseDatasetBindings::class.java)
        application.assert().isNotInstanceOf(AutoCloseable::class.java)
    }

    @Test
    fun `the dataset backend on the class path should serve the datasets over the application's client`() {
        providers.map { it.type }.assert().containsExactly("clickhouse")
        provided().assert().isInstanceOf(ClickHouseDatasetBindings::class.java)
    }

    @Test
    fun `the dataset backend should be switched off by its setting, and absent without a client`() {
        provided(settings = arrayOf("query.enabled" to "false")).assert().isNull()
        provided(StoreResources.NONE).assert().isNull()
    }

    @Test
    fun `several available dataset backends should refuse the start, naming them`() {
        val other = object : QueryDatasetProvider {
            override val type: String = "other"

            override fun available(config: QueryDatasetConfig): Boolean = true

            override fun open(config: QueryDatasetConfig): QueryBindings<QueryDataset> = QueryBindings { null }
        }
        val failure = assertThrows<WowConfigurationException> {
            HostDatasets.provided(providers + other, StoreResources.of(mapOf("client" to client))) { emptyMap() }
        }
        failure.key.assert().isEqualTo("wow.query.dataset")
        failure.message.assert().contains("clickhouse", "other")
    }
}
