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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.tck.transport.TopicTransportSpec
import org.apache.kafka.clients.admin.RecordsToDelete
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The transport contract on a real broker. */
class KafkaTransportTest : TopicTransportSpec() {
    override val producer: TopicProducer get() = Companion.producer
    override val source: PartitionedSource get() = Companion.source

    override suspend fun newTopic(partitions: Int): String = KafkaFixture.newTopic(partitions)

    /** Deleted as retention deletes them: the partition's log start offset moves to [offset]. */
    override suspend fun deleteBefore(topic: String, partition: Int, offset: Long): Boolean {
        val records = mapOf(TopicPartition(topic, partition) to RecordsToDelete.beforeOffset(offset))
        KafkaFixture.admin.deleteRecords(records).all().get()
        return true
    }

    override fun provided(): Pair<TransportProvider, TransportConfig> = KafkaTransportProvider() to TransportConfig.of(
        "kafka",
        "kafka",
        mapOf(
            "bootstrap-servers" to KafkaFixture.options.bootstrapServers,
            "properties.client.id" to "tck",
            "poll-timeout" to "100ms",
            "offset-gap" to "earliest",
            "topics.replication-factor" to "1",
            "topics.min-insync-replicas" to "1",
            "topics.configs.retention.ms" to "60000",
            "topics.partition-refresh" to "10s",
        ),
    )

    @Test
    fun `a send to a broker that cannot be reached should fail rather than return`(): Unit = runBlocking {
        val options = KafkaOptions(
            "127.0.0.1:1",
            mapOf("max.block.ms" to "500", "request.timeout.ms" to "500", "delivery.timeout.ms" to "1000"),
        )
        KafkaTopicProducer.create(options).use { unreachable ->
            runCatching { unreachable.send("wow.it.unreachable", TopicMessage.of("k", byteArrayOf(1))) }
                .isFailure.assert().isTrue()
        }
    }

    @Test
    fun `a bounded read should close its consumer without waiting for the fetch still in flight`(): Unit =
        runBlocking {
            val topic = KafkaFixture.newTopic(1)
            producer.send(topic, TopicMessage.of("k", byteArrayOf(1)))
            // The broker holds a fetch that finds nothing for 2 s: after the record, a read's consumer has sent its
            // next fetch, so a read whose close waited for it would take 2 s at least, and ten of them 20 s.
            val waiting = KafkaOptions(KafkaFixture.options.bootstrapServers, mapOf("fetch.max.wait.ms" to "2000"))
            KafkaPartitionedSource.create(waiting).use { reads ->
                val took = measureTime {
                    repeat(10) { reads.read(topic, 0, 0, 1).toList().map { it.offset }.assert().containsExactly(0L) }
                }
                took.assert().isLessThan(10.seconds)
            }
        }

    companion object {
        private val producer by lazy { KafkaTopicProducer.create(KafkaFixture.options) }
        private val source by lazy { KafkaPartitionedSource.create(KafkaFixture.options) }

        @JvmStatic
        @AfterAll
        fun close() {
            producer.close()
            source.close()
        }
    }
}
