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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transformWhile
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi

/** A message read from a partition of a topic at its [offset], with its [key] (null when it has none). */
@WowSpi
class SourceMessage @InternalWowApi constructor(
    val offset: Long,
    val key: String?,
    val headers: Map<String, String>,
    val value: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is SourceMessage && offset == other.offset && key == other.key &&
            headers == other.headers && value.contentEquals(other.value)

    override fun hashCode(): Int = listOf(offset, key, headers, value.contentHashCode()).hashCode()

    override fun toString(): String = "SourceMessage(offset=$offset, key=$key, headers=$headers, ${value.size} bytes)"

    companion object {
        /** The message read at [offset], keyed [key], with [headers] and [value]. */
        @JvmStatic
        fun of(offset: Long, key: String?, headers: Map<String, String>, value: ByteArray): SourceMessage =
            SourceMessage(offset, key, headers, value)
    }
}

/**
 * The offsets a partition holds: from [start], the first one still retained, to before [end], the offset the next
 * message written will get. A partition holds nothing when they are equal.
 */
@WowSpi
class PartitionOffsets @InternalWowApi constructor(val start: Long, val end: Long) {
    init {
        require(start in 0..end) { "A partition's offsets run from 0 up: $start..$end." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PartitionOffsets && start == other.start && end == other.end

    override fun hashCode(): Int = 31 * start.hashCode() + end.hashCode()

    override fun toString(): String = "PartitionOffsets(start=$start, end=$end)"

    companion object {
        /** The offsets from [start] to before [end]. */
        @JvmStatic
        fun of(start: Long, end: Long): PartitionOffsets = PartitionOffsets(start, end)
    }
}

/**
 * Reads the partitions of a topic from an offset: the port a Kafka-sourced subscription reads
 * through. The runtime assigns the partitions to nodes by its own leases and keeps the offsets in its checkpoint
 * store, so a source has no consumer group, commits nothing and keeps no position: each [read] starts where it is
 * told.
 *
 * - [partitions] are the topic's partitions, in order.
 * - [partitionOf] is the partition a message keyed `key` lands in, as the [TopicProducer] of the same broker picks it:
 *   every message of a key is in that partition, so the records of one aggregate are found by re-scanning it.
 * - [offsets] are what a partition holds now: where a subscription that starts from the end starts, and whether an
 *   offset is still retained.
 * - [read] emits the messages of one partition with an offset at or after `from`, in offset order. A message that is
 *   no longer retained is skipped, so the first one emitted may be later than `from`; offsets may also have gaps
 *   within a partition. The flow does not complete: after the messages written so far it waits for new ones, until
 *   it is cancelled. Bounded by `until`, it emits those before `until` only and completes once it has passed them.
 */
@WowSpi
interface PartitionedSource {
    suspend fun partitions(topic: String): List<Int>

    suspend fun partitionOf(topic: String, key: String): Int

    suspend fun offsets(topic: String, partition: Int): PartitionOffsets

    fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage>

    /**
     * How many reads were to start from an offset its partition no longer held (retention, a topic created again):
     * each is an error to alert on, whatever the source did about it. 0 for a source that counts none.
     */
    val offsetGaps: Long get() = 0

    /**
     * The messages of [partition] from [from] to before [until], then completes. By default the unbounded [read]
     * up to the message at `until - 1`: a source whose partitions may end in a gap (such as transaction markers)
     * overrides it.
     */
    fun read(topic: String, partition: Int, from: Long, until: Long): Flow<SourceMessage> =
        if (from >= until) {
            emptyFlow()
        } else {
            read(topic, partition, from).transformWhile { message ->
                if (message.offset < until) {
                    emit(message)
                }
                message.offset < until - 1
            }
        }
}
