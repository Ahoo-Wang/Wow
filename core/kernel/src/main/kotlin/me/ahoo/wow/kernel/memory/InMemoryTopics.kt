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

package me.ahoo.wow.kernel.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Topics in memory, a [TopicProducer] and a [PartitionedSource] at once, for tests and samples, as the other
 * in-memory backends: a topic is [create]d, or created with [partitions] partitions when it is first written or read;
 * a message goes to the partition it names, else to the one its key's hash picks ([partitionOf]), and every partition
 * keeps all it was sent,
 * from offset 0, until [retain] drops its oldest messages as a broker's retention would.
 *
 * [failure], when set, makes every [send] throw it without writing anything, as a broker that does not acknowledge.
 *
 * As a [TopicAdmin] (decision D168), it describes the topics created so far and creates the ones asked for; [grow]
 * adds partitions to a topic, as an operator does to a Kafka topic.
 */
class InMemoryTopics(private val partitions: Int = DEFAULT_PARTITIONS) : TopicProducer, PartitionedSource, TopicAdmin {
    private val topics = ConcurrentHashMap<String, Topic>()

    /** Thrown by every [send] while set. */
    @Volatile
    var failure: Exception? = null

    init {
        require(partitions >= 1) { "A topic has at least one partition." }
    }

    override suspend fun send(topic: String, messages: List<TopicMessage>) {
        failure?.let { throw it }
        topicOf(topic).append(messages)
    }

    override suspend fun partitions(topic: String): List<Int> = (0 until topicOf(topic).size).toList()

    override suspend fun partitionOf(topic: String, key: String): Int =
        Math.floorMod(key.hashCode(), topicOf(topic).size)

    override suspend fun offsets(topic: String, partition: Int): PartitionOffsets = topicOf(topic).offsets(partition)

    override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> = flow {
        val target = topicOf(topic)
        var next = maxOf(from, 0)
        while (true) {
            val seen = target.appended.value
            val batch = target.messages(partition, next)
            batch.forEach {
                emit(it)
                next = it.offset + 1
            }
            if (batch.isEmpty()) {
                target.appended.first { it != seen }
            }
        }
    }

    override suspend fun partitions(topics: Set<String>): Map<String, Int> =
        topics.mapNotNull { topic -> this.topics[topic]?.let { topic to it.size } }.toMap()

    override suspend fun create(topics: Map<String, Int>) {
        topics.forEach { (topic, partitions) -> create(topic, partitions) }
    }

    /** Gives [topic] [partitions] partitions, the new ones empty; a topic never has fewer than it had. */
    fun grow(topic: String, partitions: Int) {
        topicOf(topic).grow(partitions)
    }

    /** Creates [topic] with [partitions] partitions, unless it exists. */
    fun create(topic: String, partitions: Int = this.partitions) {
        require(partitions >= 1) { "A topic has at least one partition." }
        topics.computeIfAbsent(topic) { Topic(partitions) }
    }

    /** Every message [topic] holds, partition by partition, each in offset order. */
    fun messages(topic: String): List<SourceMessage> = topicOf(topic).let { target ->
        (0 until target.size).flatMap { target.messages(it, 0) }
    }

    /**
     * Drops the messages of [partition] of [topic] before [from], as a broker's retention does: they are no longer
     * read, and the partition's offsets start at [from]. Offsets do not move.
     */
    fun retain(topic: String, partition: Int, from: Long) {
        topicOf(topic).retain(partition, from)
    }

    private fun topicOf(topic: String): Topic = topics.computeIfAbsent(topic) { Topic(partitions) }

    private class Topic(partitions: Int) {
        val lock = ReentrantLock()
        private val partitions: MutableList<MutableList<SourceMessage>> = MutableList(partitions) { ArrayList() }

        /** By partition, the first offset retained. */
        private val starts: MutableList<Long> = MutableList(partitions) { 0L }
        val appended = MutableStateFlow(0L)

        val size: Int get() = lock.withLock { partitions.size }

        fun grow(count: Int) = lock.withLock {
            require(count >= partitions.size) { "A topic of ${partitions.size} partitions never has fewer." }
            while (partitions.size < count) {
                partitions.add(ArrayList())
                starts.add(0L)
            }
        }

        fun append(messages: List<TopicMessage>) {
            lock.withLock {
                messages.forEach { message ->
                    val index = message.partition?.also(::requirePartition)
                        ?: Math.floorMod(message.key.hashCode(), partitions.size)
                    val partition = partitions[index]
                    val offset = starts[index] + partition.size
                    partition += SourceMessage(offset, message.key, message.headers, message.value)
                }
                appended.value += 1
            }
        }

        fun messages(partition: Int, from: Long): List<SourceMessage> = lock.withLock {
            requirePartition(partition)
            val skipped = (from - starts[partition]).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            partitions[partition].drop(skipped)
        }

        fun offsets(partition: Int): PartitionOffsets = lock.withLock {
            requirePartition(partition)
            PartitionOffsets(starts[partition], starts[partition] + partitions[partition].size)
        }

        fun retain(partition: Int, from: Long) = lock.withLock {
            requirePartition(partition)
            val dropped = (from - starts[partition]).coerceIn(0, partitions[partition].size.toLong()).toInt()
            repeat(dropped) { partitions[partition].removeAt(0) }
            starts[partition] += dropped
        }

        private fun requirePartition(partition: Int) =
            require(partition in partitions.indices) { "No partition $partition among ${partitions.size}." }
    }

    private companion object {
        const val DEFAULT_PARTITIONS = 4
    }
}
