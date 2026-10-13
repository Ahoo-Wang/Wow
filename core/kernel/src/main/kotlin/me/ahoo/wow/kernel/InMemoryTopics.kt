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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Topics in memory, a [TopicProducer] and a [PartitionedSource] at once, for tests and samples, as the other
 * in-memory backends: a topic is [create]d, or created with [partitions] partitions when it is first written or read;
 * a message goes to the partition its key's hash picks, and every partition keeps all it was sent, from offset 0.
 *
 * [failure], when set, makes every [send] throw it without writing anything, as a broker that does not acknowledge.
 */
class InMemoryTopics(private val partitions: Int = DEFAULT_PARTITIONS) : TopicProducer, PartitionedSource {
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

    override suspend fun partitions(topic: String): List<Int> = (0 until topicOf(topic).partitions.size).toList()

    override suspend fun offsets(topic: String, partition: Int): PartitionOffsets =
        PartitionOffsets(0, topicOf(topic).messages(partition, 0).size.toLong())

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

    /** Creates [topic] with [partitions] partitions, unless it exists. */
    fun create(topic: String, partitions: Int = this.partitions) {
        require(partitions >= 1) { "A topic has at least one partition." }
        topics.computeIfAbsent(topic) { Topic(partitions) }
    }

    /** Every message [topic] holds, partition by partition, each in offset order. */
    fun messages(topic: String): List<SourceMessage> = topicOf(topic).let { target ->
        target.partitions.indices.flatMap { target.messages(it, 0) }
    }

    /** The partition a message keyed [key] goes to in [topic]. */
    fun partitionOf(topic: String, key: String): Int = Math.floorMod(key.hashCode(), topicOf(topic).partitions.size)

    private fun topicOf(topic: String): Topic = topics.computeIfAbsent(topic) { Topic(partitions) }

    private class Topic(partitions: Int) {
        val lock = ReentrantLock()
        val partitions: List<MutableList<SourceMessage>> = List(partitions) { ArrayList() }
        val appended = MutableStateFlow(0L)

        fun append(messages: List<TopicMessage>) {
            lock.withLock {
                messages.forEach { message ->
                    val partition = partitions[Math.floorMod(message.key.hashCode(), partitions.size)]
                    partition += SourceMessage(partition.size.toLong(), message.key, message.headers, message.value)
                }
                appended.value += 1
            }
        }

        fun messages(partition: Int, from: Long): List<SourceMessage> = lock.withLock {
            require(partition in partitions.indices) { "No partition $partition among ${partitions.size}." }
            partitions[partition].drop(from.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private companion object {
        const val DEFAULT_PARTITIONS = 4
    }
}
