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

package me.ahoo.wow.spi.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.WowSpi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How often a tail polls: every [interval] while it finds entries, backing off when it finds none, each
 * empty poll doubling the wait up to [maxInterval]. A commit this log made in the partition wakes its tails at once,
 * so an idle partition's next entry is not held up by the backoff on the node that appends it; entries other nodes
 * append are read within [maxInterval].
 *
 * A poll a commit of this log woke, which found entries and is not behind the partition's counter, keeps the backoff
 * as it was instead of polling again after [interval] (B.D diagnostics): this log's next commit wakes the tail anyway,
 * so on a partition only this node appends to ('s partitioned routing) a commit costs one read of the tail,
 * not a run of short polls backing off again. Entries found without a wake, from another node, still bring the tail
 * back to [interval].
 *
 * A backend whose [EventLog] polls its storage for the tails ([EventLog.tail]) paces them with one of these, of
 * [TailPolling]'s intervals (`wow.log.poll-interval`, `wow.log.max-poll-interval`), counting each commit of its own.
 */
@WowSpi
class TailPacer(partitions: Int, val interval: Duration, val maxInterval: Duration) {
    init {
        require(interval.isPositive()) { "The poll interval is positive." }
        require(maxInterval >= interval) { "The longest poll interval is at least the poll interval." }
    }

    /** The commits this log made in each partition, counted. */
    private val commits = Array(partitions) { MutableStateFlow(0L) }

    /** Counts a commit this log made in [partition], waking its tails. */
    fun committed(partition: Int) = commits[partition].update { it + 1 }

    /** The commits counted in [partition] so far, read before a poll and handed to [await] after it. */
    fun mark(partition: Int): Long = commits[partition].value

    /**
     * Waits after a poll of [partition] that found entries ([found]) or none, [idle] being the wait after the last
     * empty one, [woken] whether a commit of this log woke the poll (and it is not behind the counter): the interval
     * after entries found unwoken, else [idle]; cut short when this log commits in the partition after [mark]. Returns
     * the wait after the next empty poll (the interval after entries found unwoken, [idle] after entries found woken,
     * else [idle] doubled, up to the longest) and whether a commit cut this wait short.
     */
    suspend fun await(partition: Int, mark: Long, found: Boolean, idle: Duration, woken: Boolean = false): Pace {
        val fast = found && !woken
        val wait = if (fast) interval else idle
        val cut = withTimeoutOrNull(wait) { commits[partition].first { it != mark } } != null
        val next = when {
            fast -> interval
            found -> idle
            else -> (wait * 2).coerceAtMost(maxInterval)
        }
        return Pace(next, cut)
    }

    /** The wait after the next empty poll, and whether a commit of this log woke the next poll. */
    class Pace(val idle: Duration, val woken: Boolean) {
        override fun equals(other: Any?): Boolean =
            this === other || other is Pace && idle == other.idle && woken == other.woken

        override fun hashCode(): Int = 31 * idle.hashCode() + woken.hashCode()

        override fun toString(): String = "Pace(idle=$idle, woken=$woken)"
    }
}

/**
 * How often the tails of a polled log poll: every [interval] while they find entries, backing off up to
 * [maxInterval] while they find none (`wow.log.poll-interval`, `wow.log.max-poll-interval`).
 */
@WowSpi
class TailPolling(val interval: Duration, val maxInterval: Duration) {
    init {
        require(interval.isPositive()) { "The poll interval is positive." }
        require(maxInterval >= interval) { "The longest poll interval is at least the poll interval." }
    }

    /** A pacer of the tails of a log of [partitions] partitions, at these intervals. */
    fun pacer(partitions: Int): TailPacer = TailPacer(partitions, interval, maxInterval)

    override fun equals(other: Any?): Boolean =
        this === other || other is TailPolling && interval == other.interval && maxInterval == other.maxInterval

    override fun hashCode(): Int = 31 * interval.hashCode() + maxInterval.hashCode()

    override fun toString(): String = "TailPolling(interval=$interval, maxInterval=$maxInterval)"

    companion object {
        /** 50 ms while entries come, backing off to 1 s. */
        @JvmField
        val DEFAULT: TailPolling = TailPolling(50.milliseconds, 1.seconds)
    }
}
