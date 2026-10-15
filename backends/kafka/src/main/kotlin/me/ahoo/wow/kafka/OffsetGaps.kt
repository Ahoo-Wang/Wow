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

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * What a read of a Kafka partition does when the offset it is to read from is not in the partition (finding A2-11):
 * before its earliest retained offset (the topic's retention deleted what the checkpoint had yet to read), or past its
 * end (the topic was deleted and created again, or truncated). Either way messages were lost, and the read never steps
 * over them silently: whatever the policy, it logs an error and counts the gap ([KafkaPartitionedSource.offsetGaps]).
 */
enum class OffsetGapPolicy {
    /**
     * The read fails with an [OffsetGapException] (the default). A subscription retries its read with its backoff,
     * logging each failure, so it stays where it is, parked in effect, until an operator moves its checkpoint (or
     * chooses [EARLIEST]) once the loss is understood.
     */
    FAIL,

    /** The read goes on from the earliest offset the partition retains, knowingly skipping the messages lost. */
    EARLIEST,
}

/**
 * Partition [partition] of [topic] does not hold [offset], the offset a read is to start from: it holds [start] up to
 * [end] (exclusive). See [OffsetGapPolicy].
 */
class OffsetGapException(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val start: Long,
    val end: Long,
) : IllegalStateException(
    if (offset < start) {
        "Partition $partition of $topic no longer retains offset $offset: it starts at $start, so the messages from " +
            "$offset to $start were deleted before they were read."
    } else {
        "Partition $partition of $topic ends at $end, before offset $offset: the topic was created again or truncated."
    },
)

/** Applies [policy] to the offsets reads start from, counting the gaps met. */
internal class OffsetGaps(private val policy: OffsetGapPolicy = OffsetGapPolicy.FAIL) {
    private val gaps = AtomicLong()

    /** The gaps met so far. */
    val count: Long get() = gaps.get()

    /**
     * The offset to read [partition] of [topic] from, asked for [from] when it holds [start] up to [end]: [from] itself
     * when the partition holds it or it is the end; otherwise, by the policy, an [OffsetGapException] or [start].
     */
    fun resolve(topic: String, partition: Int, from: Long, start: Long, end: Long): Long {
        if (from in start..end) {
            return from
        }
        gaps.incrementAndGet()
        val gap = OffsetGapException(topic, partition, from, start, end)
        return when (policy) {
            OffsetGapPolicy.FAIL -> {
                logger.error("{} The read fails until its checkpoint is moved.", gap.message)
                throw gap
            }

            OffsetGapPolicy.EARLIEST -> {
                logger.error("{} Reading on from offset {}, as the offset gap policy says.", gap.message, start)
                start
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OffsetGaps::class.java)
    }
}
