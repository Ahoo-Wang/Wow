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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transformWhile
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.spi.log.EventLog
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.TimeSource

/**
 * Where a subscription that has no checkpoint yet in a partition starts: the host's override of its
 * handler's [StartFrom] (`wow.subscriptions."<name>".start`, [SubscriptionOptions.starts]). A subscription that has a
 * checkpoint goes on from it whatever this says.
 */
@InternalWowApi
sealed interface SubscriptionStart {
    /**
     * The checkpoint the subscription starts after in [partition] of [log]: every entry after it is handled. 0 is
     * before the first entry.
     */
    suspend fun checkpointIn(log: EventLog, partition: Int): Long

    /** From the first entry of the partition. */
    @InternalWowApi
    data object Beginning : SubscriptionStart {
        override suspend fun checkpointIn(log: EventLog, partition: Int): Long = 0L

        override fun toString(): String = BEGINNING
    }

    /** After the entries already committed when the subscription first starts in the partition (its log head). */
    @InternalWowApi
    data object End : SubscriptionStart {
        override suspend fun checkpointIn(log: EventLog, partition: Int): Long = log.head(partition) ?: 0L

        override fun toString(): String = END
    }

    /**
     * From the first entry of the partition, in log order, written at or after [instant]: every entry after it is
     * handled, whatever its time; after the head when none is yet, as [End]. An entry's time is when it was written
     * (`EventStreamRecord.writtenAt`): an import's commit occurred long before, and was written after the instant all
     * the same.
     *
     * Those times are not ordered along a partition: an entry's is its decision's reading of the clock, and decisions
     * of different aggregates commit in another order than they read it (one decides longer, or waits longer to be
     * appended), as a lease handover's clock skew does too. So the entry is found by
     * reading the partition forward from its first entry ([firstAt]) up to the head read when the search begins, the
     * way a subscription catching up reads it ([EventLog.tail]), once, on the subscription's first start in the
     * partition: the entries before the start are read, not handled.
     */
    @InternalWowApi
    data class At(val instant: Instant) : SubscriptionStart {
        override suspend fun checkpointIn(log: EventLog, partition: Int): Long {
            val head = log.head(partition) ?: 0L
            // The tail never completes: it is read up to the head, never waiting for an entry after it.
            val commits = if (head == 0L) {
                emptyFlow()
            } else {
                log.tail(partition, null).transformWhile { entry ->
                    emit(TimedEntry(entry.position.sequence, entry.stream.writtenAt))
                    entry.position.sequence < head
                }
            }
            return firstAt(commits, head, "partition $partition of the log")
        }

        /**
         * The checkpoint before the first of [commits] committed at or after the instant, or [end] when none is:
         * [commits] are the entries of a partition [where] in order up to [end], read forward as they stream, so any
         * number of them takes the memory of one. The only search for the start, so that a backend able to answer it
         * itself can take its place.
         */
        internal suspend fun firstAt(commits: Flow<TimedEntry>, end: Long, where: String): Long {
            val millis = instant.toEpochMilli()
            logger.info { "Finding the first entry committed at or after $instant in $where, reading up to $end." }
            val began = TimeSource.Monotonic.markNow()
            var read = 0L
            val first = commits.firstOrNull { commit ->
                read++
                commit.writtenAt?.let { it >= millis } == true
            }
            val checkpoint = first?.let { it.sequence - 1 } ?: end
            logger.info {
                val found = first?.let { "entry ${it.sequence} is the first" } ?: "none is"
                "Read $read entries of $where in ${began.elapsedNow()}: $found committed at or after $instant; " +
                    "starting after $checkpoint."
            }
            return checkpoint
        }

        override fun toString(): String = instant.toString()
    }

    @InternalWowApi
    companion object {
        private const val BEGINNING = "beginning"
        private const val END = "end"

        /**
         * The start of a handler that starts [from]: for [StartFrom.JOIN], [End] when the service had [joined] the
         * stream already (another of its subscriptions has a checkpoint in it), [Beginning] when it joins it now.
         */
        @JvmStatic
        @JvmOverloads
        fun of(from: StartFrom, joined: Boolean = false): SubscriptionStart = when (from) {
            StartFrom.BEGINNING -> Beginning
            StartFrom.END -> End
            StartFrom.JOIN -> if (joined) End else Beginning
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

/** An entry of a partition, at [sequence] (from 1), written at [writtenAt]; null when it cannot be read. */
internal class TimedEntry(val sequence: Long, val writtenAt: Long?)

private val logger = KotlinLogging.logger {}
