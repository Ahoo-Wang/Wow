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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.common.config.ConfigResource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/** Describing and creating topics on a real broker (decision D168). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaTopicAdminTest {
    private val admin = KafkaTopicAdmin.create(KafkaFixture.options, 1, mapOf("retention.ms" to "3600000"))

    @AfterAll
    fun close() {
        admin.close()
    }

    @Test
    fun `missing topics should be created with their partitions and configs, existing ones left as they are`(): Unit =
        runBlocking {
            val existing = KafkaFixture.newTopic(2, "wow.it.admin")
            val created = "wow.it.admin.created.commands"
            admin.partitions(setOf(existing, created)).assert().isEqualTo(mapOf(existing to 2))
            admin.partitions(emptySet()).assert().isEmpty()
            admin.create(emptyMap())

            admin.create(mapOf(existing to 6, created to 3))

            admin.partitions(setOf(existing, created)).assert().isEqualTo(mapOf(existing to 2, created to 3))
            KafkaFixture.admin.describeConfigs(listOf(ConfigResource(ConfigResource.Type.TOPIC, created))).all().get().values.single()
                .get("retention.ms").value().assert().isEqualTo("3600000")
        }

    @Test
    fun `asking a source about a topic should not create it, and partitions added later should be seen`(): Unit =
        runBlocking {
            val options = KafkaOptions(
                KafkaFixture.options.bootstrapServers,
                mapOf("metadata.max.age.ms" to "500"),
            )
            KafkaPartitionedSource.create(options).use { source ->
                val missing = "wow.it.admin.missing.state"
                source.partitions(missing).assert().isEmpty()
                admin.partitions(setOf(missing)).assert().isEmpty()

                val topic = KafkaFixture.newTopic(2, "wow.it.admin")
                source.partitions(topic).assert().containsExactly(0, 1)
                KafkaFixture.admin.createPartitions(mapOf(topic to NewPartitions.increaseTo(4))).all().get()
                withTimeout(30.seconds) {
                    while (source.partitions(topic).size != 4) {
                        delay(100)
                    }
                }
                admin.partitions(setOf(topic)).assert().isEqualTo(mapOf(topic to 4))
            }
        }

    @Test
    fun `the durability of a topic should be its replication factor and min insync replicas`(): Unit = runBlocking {
        // Review A3-15: a single broker gives one replica; the topic's own config is read, else the broker's 1.
        val durable = "wow.it.admin.durable.commands"
        KafkaTopicAdmin.create(KafkaFixture.options, 1, mapOf(KafkaTopicAdmin.MIN_INSYNC_REPLICAS to "1")).use {
            it.create(mapOf(durable to 1))
        }
        val plain = KafkaFixture.newTopic(2, "wow.it.admin")
        val read = admin.durability(setOf(durable, plain, "wow.it.admin.missing.commands"))
        read.assert().isEqualTo(mapOf(durable to TopicDurability(1, 1), plain to TopicDurability(1, 1)))
        read.getValue(durable).survivesOneBroker.assert().isFalse()
        admin.durability(emptySet()).assert().isEmpty()
    }

    @Test
    fun `a replication factor below one should be refused`() {
        runCatching { KafkaTopicAdmin(KafkaFixture.admin, 0) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
