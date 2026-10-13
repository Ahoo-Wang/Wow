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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The [PartitionedSource] over Kafka consumers (decisions D31, D132, D134): made by [create] from [KafkaOptions],
 * consumers without a group that commit nothing, since the runtime assigns the partitions and keeps the offsets.
 *
 * - [partitions] and [offsets] ask one consumer of their own ([consumers] makes it on the first call), one call at a
 *   time, since a Kafka consumer is not safe for concurrent use.
 * - [read] runs a consumer of its own for as long as its flow is collected: assigned the one partition, it seeks to
 *   `from` and polls, each poll at most [pollTimeout] on a virtual thread, emitting what it got; the consumer closes
 *   when the flow is cancelled. An offset no longer retained reads from the earliest one retained; one past the end
 *   waits for the messages to reach it.
 *
 * [close] closes the consumer of [partitions] and [offsets]; the reads close their own.
 */
class KafkaPartitionedSource(
    private val consumers: () -> Consumer<String, ByteArray>,
    private val pollTimeout: Duration = KafkaOptions.DEFAULT_POLL_TIMEOUT,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : PartitionedSource, AutoCloseable {
    private val lock = Mutex()
    private var metadata: Consumer<String, ByteArray>? = null

    override suspend fun partitions(topic: String): List<Int> = metadata { consumer ->
        // Empty for a topic the broker does not know.
        consumer.partitionsFor(topic).map { it.partition() }.sorted()
    }

    override suspend fun offsets(topic: String, partition: Int): PartitionOffsets = metadata { consumer ->
        val target = listOf(TopicPartition(topic, partition))
        val start = consumer.beginningOffsets(target).values.single()
        val end = consumer.endOffsets(target).values.single()
        PartitionOffsets(start, end)
    }

    override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> = flow {
        require(from >= 0) { "An offset is never negative." }
        val target = TopicPartition(topic, partition)
        val consumer = withContext(dispatcher) { consumers() }
        try {
            withContext(dispatcher) {
                consumer.assign(listOf(target))
                // Past the end, Kafka would reset to the earliest offset: seek to the end and skip up to `from`.
                consumer.seek(target, minOf(from, consumer.endOffsets(listOf(target)).values.single()))
            }
            val timeout = pollTimeout.toJavaDuration()
            while (true) {
                val records = withContext(dispatcher) { consumer.poll(timeout) }
                for (record in records) {
                    if (record.offset() >= from) {
                        emit(
                            SourceMessage(
                                record.offset(),
                                record.key(),
                                KafkaHeaders.of(record.headers()),
                                record.value()
                            )
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable + dispatcher) { consumer.close() }
        }
    }

    private suspend fun <T> metadata(call: (Consumer<String, ByteArray>) -> T): T = lock.withLock {
        withContext(dispatcher) {
            call(metadata ?: consumers().also { metadata = it })
        }
    }

    override fun close() {
        metadata?.close()
        metadata = null
    }

    companion object {
        /** A source whose consumers have [options]' configuration ([KafkaOptions.consumerConfig]). */
        fun create(options: KafkaOptions): KafkaPartitionedSource = KafkaPartitionedSource(
            { KafkaConsumer<String, ByteArray>(options.consumerConfig()) },
            options.pollTimeout,
        )
    }
}
