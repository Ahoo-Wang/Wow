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

package me.ahoo.wow.spi.transport

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi

/**
 * A message to write to a topic: its [key], which picks its partition (the same key always lands in the same
 * partition, so the messages of one key keep their order), its [headers] and its [value] bytes.
 *
 * [partition], when set, is the partition the message goes to instead of the one its key picks: a command topic's
 * partitions are the context's log partitions, chosen by the log's own partitioner.
 */
@WowSpi
class TopicMessage @InternalWowApi constructor(
    val key: String,
    val value: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val partition: Int? = null,
) {
    init {
        require(partition == null || partition >= 0) { "A partition is never negative." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TopicMessage && key == other.key && value.contentEquals(other.value) &&
            headers == other.headers && partition == other.partition

    override fun hashCode(): Int = listOf(key, value.contentHashCode(), headers, partition).hashCode()

    override fun toString(): String =
        "TopicMessage(key=$key, ${partition?.let { "partition=$it, " }.orEmpty()}headers=$headers, ${value.size} bytes)"

    companion object {
        /** The message of [value] keyed [key], with [headers], to [partition] when it names one. */
        @JvmStatic
        @JvmOverloads
        fun of(
            key: String,
            value: ByteArray,
            headers: Map<String, String> = emptyMap(),
            partition: Int? = null,
        ): TopicMessage = TopicMessage(key, value, headers, partition)
    }
}

/**
 * Writes messages to the topics of a broker: the port of the Kafka transport, which an in-memory
 * transport also implements so the runtime's semantics are tested without a broker.
 *
 * - **Acknowledged on return**: [send] suspends until the broker has acknowledged every message, durable as the
 *   broker is configured to make it (Kafka: `acks=all`); it throws when one is not. A caller moves its checkpoint only
 *   after [send] returns.
 * - **Order**: the messages of one key in one call reach their partition in the order given, and a later call's
 *   after an earlier call's. A retried call may write some messages again: delivery is at least once.
 * - **Partitions**: the producer chooses the partition of a message by its key, unless the message names its own
 *   ([TopicMessage.partition]); the topic must exist (or the broker creates it).
 */
@WowSpi
interface TopicProducer {
    suspend fun send(topic: String, messages: List<TopicMessage>)

    /** Sends one [message] to [topic], as [send] does. */
    suspend fun send(topic: String, message: TopicMessage): Unit = send(topic, listOf(message))
}
