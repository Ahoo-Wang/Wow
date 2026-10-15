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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.utils.Utils
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The [PartitionedSource] over Kafka consumers (decisions D31, D132, D134): made by [create] from [KafkaOptions],
 * consumers without a group that commit nothing, since the runtime assigns the partitions and keeps the offsets.
 *
 * - [partitions] and [offsets] ask one consumer of their own ([consumers] makes it on the first call), one call at a
 *   time, since a Kafka consumer is not safe for concurrent use.
 * - [partitionOf] is the producer's default partitioner: murmur2 of the key over the partition count.
 * - [read] of a partition the source does not read yet joins the topic's shared consumer ([SharedTopicConsumer]): the
 *   tails of every partition of a topic this node owns go through one consumer, assigned those partitions by hand
 *   (decision D134). A second read of a partition already joined, such as a subscription catching up, and a bounded
 *   read run a consumer of their own for as long as their flow is collected: assigned the one partition, it seeks to
 *   `from` and polls, each poll at most [pollTimeout] on a virtual thread, emitting what it got; the consumer closes
 *   when the flow ends or is cancelled.
 *
 * Either way, an offset the partition does not hold, before its earliest retained offset or past its end, is a gap
 * (finding A2-11): the read never steps over it silently, but logs an error, counts it in [offsetGaps] and does what
 * [offsetGap] says, by default failing with an [OffsetGapException].
 *
 * [close] closes the consumer of [partitions] and [offsets] and the shared consumers; the other reads close their own.
 */
class KafkaPartitionedSource @InternalWowApi constructor(
    private val consumers: () -> Consumer<String, ByteArray>,
    private val pollTimeout: Duration,
    private val dispatcher: CoroutineDispatcher,
    /** What a read does when its partition does not hold the offset it is to read from. */
    val offsetGap: OffsetGapPolicy,
) : PartitionedSource, AutoCloseable {
    constructor(
        consumers: () -> Consumer<String, ByteArray>,
        pollTimeout: Duration = KafkaOptions.DEFAULT_POLL_TIMEOUT,
        dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    ) : this(consumers, pollTimeout, dispatcher, OffsetGapPolicy.FAIL)

    private val gaps = OffsetGaps(offsetGap)

    /** The offset gaps this source's reads met (finding A2-11), each logged as an error: a counter to alert on. */
    val offsetGaps: Long get() = gaps.count

    private val lock = Mutex()
    private var metadata: Consumer<String, ByteArray>? = null
    private val shared = ConcurrentHashMap<String, SharedTopicConsumer>()

    override suspend fun partitions(topic: String): List<Int> = metadata { consumer ->
        // Empty for a topic the broker does not know.
        consumer.partitionsFor(topic).map { it.partition() }.sorted()
    }

    /**
     * The partition the producer's default partitioner puts a message keyed [key] in: murmur2 of the key's UTF-8
     * bytes over the topic's partition count, as [KafkaTopicProducer] sends with no partition of its own.
     */
    override suspend fun partitionOf(topic: String, key: String): Int {
        val count = partitions(topic).size
        check(count > 0) { "Topic $topic has no partitions." }
        return Utils.toPositive(Utils.murmur2(key.toByteArray(Charsets.UTF_8))) % count
    }

    override suspend fun offsets(topic: String, partition: Int): PartitionOffsets = metadata { consumer ->
        val target = listOf(TopicPartition(topic, partition))
        val start = consumer.beginningOffsets(target).values.single()
        val end = consumer.endOffsets(target).values.single()
        PartitionOffsets(start, end)
    }

    override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> = flow {
        require(from >= 0) { "An offset is never negative." }
        val topicConsumer = shared.computeIfAbsent(topic) {
            SharedTopicConsumer(it, consumers, pollTimeout, dispatcher, gaps = gaps)
        }
        val reader = topicConsumer.join(partition, from)
        if (reader == null) {
            emitAll(poll(topic, partition, from, Long.MAX_VALUE))
            return@flow
        }
        try {
            for (message in reader.channel) {
                emit(message)
            }
        } finally {
            topicConsumer.leave(reader)
        }
    }

    /** Polls a consumer of its own until its position reaches [until], so a partition ending in a gap completes too. */
    override fun read(topic: String, partition: Int, from: Long, until: Long): Flow<SourceMessage> =
        if (from >= until) emptyFlow() else poll(topic, partition, from, until)

    private fun poll(topic: String, partition: Int, from: Long, until: Long): Flow<SourceMessage> = flow {
        require(from >= 0) { "An offset is never negative." }
        val target = TopicPartition(topic, partition)
        val consumer = withContext(dispatcher) { consumers() }
        try {
            var next = withContext(dispatcher) {
                consumer.assign(listOf(target))
                place(consumer, target, from)
            }
            val timeout = pollTimeout.toJavaDuration()
            var position = 0L
            while (position < until) {
                val records = withContext(dispatcher) {
                    try {
                        consumer.poll(timeout).also { position = consumer.position(target) }
                    } catch (_: OffsetOutOfRangeException) {
                        // The retention deleted what was still to be read, while it was being read.
                        next = place(consumer, target, next)
                        ConsumerRecords.empty<String, ByteArray>()
                    }
                }
                for (record in records) {
                    if (record.offset() >= until) {
                        return@flow
                    }
                    if (record.offset() >= next) {
                        next = record.offset() + 1
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

    /** Seeks [consumer] to [from] in [target], or where [offsetGap] says when the partition does not hold it. */
    private fun place(consumer: Consumer<String, ByteArray>, target: TopicPartition, from: Long): Long {
        val start = consumer.beginningOffsets(listOf(target)).values.single()
        val end = consumer.endOffsets(listOf(target)).values.single()
        return gaps.resolve(target.topic(), target.partition(), from, start, end).also { consumer.seek(target, it) }
    }

    private suspend fun <T> metadata(call: (Consumer<String, ByteArray>) -> T): T = lock.withLock {
        withContext(dispatcher) {
            call(metadata ?: consumers().also { metadata = it })
        }
    }

    override fun close() {
        shared.values.forEach { it.close() }
        shared.clear()
        metadata?.close()
        metadata = null
    }

    companion object {
        /** A source whose consumers have [options]' configuration ([KafkaOptions.consumerConfig]). */
        fun create(options: KafkaOptions): KafkaPartitionedSource = create(options, OffsetGapPolicy.FAIL)

        /** A source whose consumers have [options]' configuration, its reads meeting an offset gap as [offsetGap] says. */
        fun create(options: KafkaOptions, offsetGap: OffsetGapPolicy): KafkaPartitionedSource = KafkaPartitionedSource(
            { KafkaConsumer<String, ByteArray>(options.consumerConfig()) },
            options.pollTimeout,
            VirtualThreads.dispatcher,
            offsetGap,
        )
    }
}
