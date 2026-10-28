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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.ClickHouseClients
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.store.StoreResources
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.ServiceLoader

/** The ClickHouse Sink provider (finding R5.B-5): when it is configured, and the options it reads. */
class ClickHouseSinkProviderTest {
    private val provider = ClickHouseSinkProvider()

    private fun config(vararg options: Pair<String, String>, resources: StoreResources = StoreResources.NONE) =
        SinkConfig.of("clickhouse", "clickhouse", options.toMap(), resources)

    @Test
    fun `the sink is switched on by its setting alone`() {
        provider.type.assert().isEqualTo("clickhouse")
        provider.available(config()).assert().isFalse()
        provider.available(config("endpoint" to "http://c:8123")).assert().isFalse()
        provider.available(config("sink.enabled" to "true")).assert().isTrue()
        provider.available(config("endpoint" to "http://c:8123", "sink.enabled" to "false")).assert().isFalse()
        assertThrows<WowConfigurationException> {
            provider.available(config("endpoint" to "http://c:8123", "sink.enabled" to "maybe"))
        }.key.assert().isEqualTo("wow.clickhouse.sink.enabled")
        ServiceLoader.load(SinkProvider::class.java).map { it.type }.assert().contains("clickhouse")
    }

    @Test
    fun `a client should need an endpoint, and sign in as the default user without a password unless set`() {
        assertThrows<IllegalArgumentException> { ClickHouseClients.of(mapOf("endpoint" to " ")) }
            .message.assert().contains("wow.clickhouse.endpoint is not set")
        ClickHouseClients.of(mapOf("endpoint" to "http://127.0.0.1:1", "username" to " ")).use {
            it.assert().isNotNull()
        }
    }

    @Test
    fun `unset settings take the Sink's defaults`() {
        val options = ClickHouseSinkProvider.options(config("endpoint" to "http://c:8123"))
        options.assert().isEqualTo(ClickHouseSinkOptions())
        assertThrows<WowConfigurationException> { ClickHouseSinkProvider.options(config("ddl" to "drop")) }
            .message.assert().contains("wow.clickhouse.ddl", "auto, validate, none")
        // A history setting is an aggregate type's: a key that names no aggregate is refused.
        assertThrows<IllegalArgumentException> {
            ClickHouseSinkProvider.options(config("aggregates.shop.history.enabled" to "false"))
        }.message.assert().contains("wow.clickhouse.aggregates.shop", "<context>.<aggregate>")
    }

    @Test
    fun `the sinks write the Manifest's streams over the application's client, or one of their own`() {
        val manifest = Manifest(service = ServiceInfo("bank-service"))
        val client = ClickHouseClients.of(mapOf("endpoint" to "http://127.0.0.1:1", "username" to "u"))
        client.use {
            val shared = provider.open(
                config(
                    "endpoint" to "http://127.0.0.1:1",
                    resources = StoreResources.of(mapOf("m" to manifest, "c" to it))
                ),
            )
            shared.processor.assert().isEqualTo(ClickHouseSinks.PROCESSOR)
            shared.toString().assert().contains("default")
            // The application's client stays open.
            shared.close()
        }
        val own = provider.open(
            config("endpoint" to "http://127.0.0.1:1", resources = StoreResources.of(mapOf("m" to manifest)))
        )
        own.close()
        assertThrows<IllegalStateException> { provider.open(config("endpoint" to "http://127.0.0.1:1")) }
        val neither = config("sink.enabled" to "true", resources = StoreResources.of(mapOf("m" to manifest)))
        assertThrows<IllegalArgumentException> {
            provider.open(neither)
        }.message.assert().contains("ClickHouse Client bean", "wow.clickhouse.endpoint")
    }
}
