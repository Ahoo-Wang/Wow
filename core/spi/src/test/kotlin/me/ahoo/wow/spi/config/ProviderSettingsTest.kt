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

package me.ahoo.wow.spi.config

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.peer.PeerDiscoveryConfig
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.transport.TransportConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderSettingsTest {
    private val size = ProviderOption.int("batch.size", "how many records a batch holds.")

    @Test
    fun `a setting should be read by its key relative to its config's key, blank as unset`() {
        val settings = ProviderSettings("wow.acme", mapOf("region" to "eu-1", "blank" to " "))
        settings.option("region").assert().isEqualTo("eu-1")
        settings.option("blank").assert().isNull()
        settings.option("missing").assert().isNull()
        settings.read.assert().containsExactlyInAnyOrder("region", "blank", "missing")
        settings.toString().assert().isEqualTo("ProviderSettings(key=wow.acme, values=[region, blank])")
        ProviderSettings("wow.none").option("region").assert().isNull()
    }

    @Test
    fun `settings under a prefix should be keyed relative to it, and the prefix counted as read`() {
        val settings = ProviderSettings("wow.acme", mapOf("properties.linger.ms" to "5", "propertiesx" to "no"))
        settings.options("properties").assert().isEqualTo(mapOf("linger.ms" to "5"))
        settings.read.assert().containsExactly("properties")
    }

    @Test
    fun `a value not of its option's type should fail naming the setting by its full key`() {
        val settings = ProviderSettings("wow.acme", mapOf("batch.size" to "x"))
        assertThrows<WowConfigurationException> { settings.option(size) }
            .key.assert().isEqualTo("wow.acme.batch.size")
    }

    @Test
    fun `every config should hold its settings under its own key`() {
        val options = mapOf("a" to "1")
        listOf(
            StoreConfig.of("orders", "acme", options = options).settings,
            SinkConfig.of("clickhouse", "clickhouse", options = options).settings,
            TransportConfig.of("kafka", "kafka", options = options).settings,
            PeerDiscoveryConfig.of("consul", options).settings,
        ).map { it.key to it.values }.assert().containsExactly(
            "wow.stores.orders" to options,
            "wow.clickhouse" to options,
            "wow.kafka" to options,
            "wow.peer.consul" to options,
        )
    }
}
