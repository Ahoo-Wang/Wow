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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.flow.first
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.spi.log.EventLog
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Where a subscription that has no checkpoint yet in a partition starts (decision D154): the host's override of its
 * handler's [StartFrom] (`wow.subscriptions."<name>".start`, [SubscriptionOptions.starts]). A subscription that has a
 * checkpoint goes on from it whatever this says.
 */
sealed interface SubscriptionStart {
    /**
     * The checkpoint the subscription starts after in [partition] of [log]: every entry after it is handled. 0 is
     * before the first entry.
     */
    suspend fun checkpointIn(log: EventLog, partition: Int): Long

    /** From the first entry of the partition. */
    data object Beginning : SubscriptionStart {
        override suspend fun checkpointIn(log: EventLog, partition: Int): Long = 0L

        override fun toString(): String = BEGINNING
    }

    /** After the entries already committed when the subscription first starts in the partition (its log head). */
    data object End : SubscriptionStart {
        override suspend fun checkpointIn(log: EventLog, partition: Int): Long = log.head(partition) ?: 0L

        override fun toString(): String = END
    }

    /**
     * From the first entry of the partition whose commit time is at or after [instant]; after the head when none is
     * yet, as [End].
     *
     * The log's SPI reads a partition only forward from a position ([EventLog.tail]), so the entry is found by a
     * binary search over the sequences up to the head: each step reads the first entry after a sequence, about
     * log2(head) reads in all instead of a scan of the partition. It relies on commit times not decreasing along a
     * partition's sequence, which its single owner at a time keeps (design 02 §2), up to the clock skew between two
     * owners across a lease handover: around one, the start may be off by the entries committed within that skew.
     */
    data class At(val instant: Instant) : SubscriptionStart {
        override suspend fun checkpointIn(log: EventLog, partition: Int): Long {
            val head = log.head(partition) ?: return 0L
            return search(0L, head) { log.tail(partition, it.takeIf { after -> after > 0 }).first().stream.commitTime }
        }

        /**
         * The smallest checkpoint in [low]..[high] whose next entry is committed at or after the instant; [high] when
         * none is. [commitTimeAfter] reads the commit time of the first entry after a checkpoint below [high].
         */
        internal suspend fun search(low: Long, high: Long, commitTimeAfter: suspend (Long) -> Long): Long {
            val millis = instant.toEpochMilli()
            var from = low
            var to = high
            while (from < to) {
                val middle = from + (to - from) / 2
                if (commitTimeAfter(middle) >= millis) {
                    to = middle
                } else {
                    from = middle + 1
                }
            }
            return from
        }

        override fun toString(): String = instant.toString()
    }

    companion object {
        private const val BEGINNING = "beginning"
        private const val END = "end"

        /** The start of a handler that starts [from]. */
        @JvmStatic
        fun of(from: StartFrom): SubscriptionStart = when (from) {
            StartFrom.BEGINNING -> Beginning
            StartFrom.END -> End
        }

        /** `beginning`, `end` (in any case) or an ISO-8601 instant such as `2026-10-01T00:00:00Z`. */
        @JvmStatic
        fun parse(text: String): SubscriptionStart = when (text.trim().lowercase()) {
            BEGINNING -> Beginning
            END -> End
            else -> try {
                At(Instant.parse(text.trim()))
            } catch (invalid: DateTimeParseException) {
                throw IllegalArgumentException(
                    "A subscription starts at the beginning, the end or an ISO-8601 instant, not \"$text\".",
                    invalid,
                )
            }
        }
    }
}
