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

package me.ahoo.wow.postgres

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * How often a tail polls (finding A2-14): every [interval] while it finds entries, backing off when it finds none, each
 * empty poll doubling the wait up to [maxInterval]. A commit this log made in the partition wakes its tails at once,
 * so an idle partition's next entry is not held up by the backoff on the node that appends it; entries other nodes
 * append are read within [maxInterval].
 */
internal class TailPacer(partitions: Int, val interval: Duration, val maxInterval: Duration) {
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
     * empty one: the interval after entries, else [idle], cut short when this log commits in the partition after
     * [mark]. Returns the wait after the next empty poll: the interval after entries, else [idle] doubled, up to the
     * longest.
     */
    suspend fun await(partition: Int, mark: Long, found: Boolean, idle: Duration): Duration {
        val wait = if (found) interval else idle
        withTimeoutOrNull(wait) { commits[partition].first { it != mark } }
        return if (found) interval else (wait * 2).coerceAtMost(maxInterval)
    }
}
