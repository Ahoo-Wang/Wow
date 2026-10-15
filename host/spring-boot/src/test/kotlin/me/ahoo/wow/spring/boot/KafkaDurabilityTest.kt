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

import kotlinx.coroutines.awaitCancellation
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kafka.TopicDurability
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** Decision D143's durability of the Kafka topics, checked at start (review A3-15). */
class KafkaDurabilityTest {
    private fun topics(factor: Short? = null, minInsync: Int? = null, extra: Map<String, String> = emptyMap()) =
        KafkaProperties.Topics().apply {
            replicationFactor = factor
            minInsyncReplicas = minInsync
            configs.putAll(extra)
        }

    @Test
    fun `a configuration below two replicas should be warned about outside a dev profile`() {
        KafkaDurability(topics(1, 1), emptySet()).configured().size.assert().isEqualTo(2)
        KafkaDurability(topics(extra = mapOf("min.insync.replicas" to "1")), setOf("prod")).configured()
            .single().assert().contains("min-insync-replicas is 1")
        KafkaDurability(topics(3, 2), emptySet()).configured().assert().isEmpty()
        // The broker's defaults are not known here: the topics themselves are checked.
        KafkaDurability(topics(), emptySet()).configured().assert().isEmpty()
        KafkaDurability(topics(1, 1), setOf("dev")).configured().assert().isEmpty()
        KafkaDurability(topics(1, 1).apply { devProfiles = mutableSetOf("sandbox") }, setOf("sandbox"))
            .configured().assert().isEmpty()
    }

    @Test
    fun `a command topic that loses commands with one broker should be warned about`() {
        val durability = mapOf(
            "wow.shop.commands" to TopicDurability(1, 1),
            "wow.cart.commands" to TopicDurability(3, 1),
            "wow.order.commands" to TopicDurability(3, 2),
        )
        val warnings = KafkaDurability(topics(), emptySet()).warnings(durability)
        warnings.size.assert().isEqualTo(2)
        warnings.first().assert().contains("wow.shop.commands", "replication factor 1", "min.insync.replicas 1")
        KafkaDurability(topics(), setOf("test")).warnings(durability).assert().isEmpty()
        TopicDurability(2, 2).survivesOneBroker.assert().isTrue()
        TopicDurability(2, 1).survivesOneBroker.assert().isFalse()
    }

    @Test
    fun `a check that cannot read the topics should not stop the node`() {
        val durability = KafkaDurability(topics(), emptySet())
        durability.checkQuietly { error("The broker is down.") }.assert().isEmpty()
        durability.checkQuietly(50.milliseconds) { awaitCancellation() }.assert().isEmpty()
        durability.checkQuietly { mapOf("t" to TopicDurability(1, 1)) }.size.assert().isEqualTo(1)
        KafkaDurability(topics(), setOf("local")).checkQuietly { error("not asked") }.assert().isEmpty()
    }

    @Test
    fun `auto should create topics with the configured min insync replicas`() {
        topics(minInsync = 2, extra = mapOf("retention.ms" to "1000")).creationConfigs().assert()
            .isEqualTo(mapOf("retention.ms" to "1000", "min.insync.replicas" to "2"))
        topics(extra = mapOf("min.insync.replicas" to "3")).creationConfigs().assert()
            .isEqualTo(mapOf("min.insync.replicas" to "3"))
    }
}
