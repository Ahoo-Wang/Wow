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

package me.ahoo.wow.kafka

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.TransportConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The Kafka transport provider's settings (finding R5.B-5) and decision D143's durability check (review A3-15). */
class KafkaTransportProviderTest {
    private val provider = KafkaTransportProvider()

    private fun config(vararg options: Pair<String, String>, development: Boolean = false) =
        TransportConfig.of("kafka", "kafka", options.toMap(), development = development)

    @Test
    fun `the transport is available once its brokers are set, and reads its clients' settings`() {
        provider.type.assert().isEqualTo("kafka")
        provider.available(config()).assert().isFalse()
        provider.available(config("bootstrap-servers" to " ")).assert().isFalse()
        val config = config(
            "bootstrap-servers" to "b:9092",
            "properties.client.id" to "bank",
            "poll-timeout" to "50ms",
            "topics.partition-refresh" to "5000",
        )
        provider.available(config).assert().isTrue()
        val options = KafkaTransportProvider.options(config)
        options.bootstrapServers.assert().isEqualTo("b:9092")
        options.properties.assert().isEqualTo(mapOf("client.id" to "bank"))
        options.pollTimeout.assert().isEqualTo(50.milliseconds)
        options.metadataMaxAge.assert().isEqualTo(5.seconds)
        KafkaTransportProvider.options(config("bootstrap-servers" to "b:9092")).pollTimeout
            .assert().isEqualTo(KafkaOptions.DEFAULT_POLL_TIMEOUT)
        assertThrows<IllegalArgumentException> { KafkaTransportProvider.options(config()) }
            .message.assert().contains("wow.kafka.bootstrap-servers")
    }

    @Test
    fun `an offset gap fails the read unless it is set to read on from the earliest offset`() {
        KafkaTransportProvider.offsetGap(config()).assert().isEqualTo(OffsetGapPolicy.FAIL)
        KafkaTransportProvider.offsetGap(
            config("offset-gap" to "EARLIEST")
        ).assert().isEqualTo(OffsetGapPolicy.EARLIEST)
        assertThrows<IllegalArgumentException> { KafkaTransportProvider.offsetGap(config("offset-gap" to "skip")) }
            .message.assert().contains("fail or earliest")
    }

    @Test
    fun `auto should create topics with the configured replication and min insync replicas`() {
        val config = config(
            "topics.replication-factor" to "3",
            "topics.min-insync-replicas" to "2",
            "topics.configs.retention.ms" to "1000",
        )
        KafkaTransportProvider.replicationFactor(config).assert().isEqualTo(3.toShort())
        KafkaTransportProvider.creationConfigs(config).assert()
            .isEqualTo(mapOf("retention.ms" to "1000", "min.insync.replicas" to "2"))
        KafkaTransportProvider.creationConfigs(config("topics.configs.min.insync.replicas" to "3")).assert()
            .isEqualTo(mapOf("min.insync.replicas" to "3"))
        KafkaTransportProvider.replicationFactor(config()).assert().isNull()
        assertThrows<IllegalArgumentException> {
            KafkaTransportProvider.replicationFactor(config("topics.replication-factor" to "0"))
        }.message.assert().contains("wow.kafka.topics.replication-factor is 0")
    }

    @Test
    fun `a configuration below two replicas should be warned about outside a dev profile`() {
        val low = arrayOf("topics.replication-factor" to "1", "topics.min-insync-replicas" to "1")
        KafkaTransportProvider.durability(config(*low)).configured().size.assert().isEqualTo(2)
        KafkaTransportProvider.durability(config("topics.configs.min.insync.replicas" to "1", development = false))
            .configured().single().assert().contains("min-insync-replicas is 1")
        KafkaTransportProvider.durability(
            config("topics.replication-factor" to "3", "topics.min-insync-replicas" to "2")
        )
            .configured().assert().isEmpty()
        // The broker's defaults are not known here: the topics themselves are checked.
        KafkaTransportProvider.durability(config()).configured().assert().isEmpty()
        KafkaTransportProvider.durability(config(*low, development = true)).configured().assert().isEmpty()
    }

    @Test
    fun `a command topic that loses commands with one broker should be warned about`() {
        val durability = mapOf(
            "wow.shop.commands" to TopicDurability(1, 1),
            "wow.cart.commands" to TopicDurability(3, 1),
            "wow.order.commands" to TopicDurability(3, 2),
        )
        val warnings = KafkaTransportProvider.durability(config()).warnings(durability)
        warnings.size.assert().isEqualTo(2)
        warnings.first().assert().contains("wow.shop.commands", "replication factor 1", "min.insync.replicas 1")
        KafkaTransportProvider.durability(config(development = true)).warnings(durability).assert().isEmpty()
        TopicDurability(2, 2).survivesOneBroker.assert().isTrue()
        TopicDurability(2, 1).survivesOneBroker.assert().isFalse()
    }

    @Test
    fun `a check that cannot read the topics should not stop the node`(): Unit = runBlocking {
        val durability = KafkaTransportProvider.durability(config())
        durability.checkQuietly { error("The broker is down.") }.assert().isEmpty()
        durability.checkQuietly(50.milliseconds) { awaitCancellation() }.assert().isEmpty()
        durability.checkQuietly { mapOf("t" to TopicDurability(1, 1)) }.size.assert().isEqualTo(1)
        KafkaTransportProvider.durability(config(development = true))
            .checkQuietly { error("not asked") }.assert().isEmpty()
    }

    @Test
    fun `the provider is found on the classpath`() {
        java.util.ServiceLoader.load(me.ahoo.wow.spi.transport.TransportProvider::class.java).map { it.type }
            .assert().contains("kafka")
    }
}
