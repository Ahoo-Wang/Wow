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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import java.time.Clock

/**
 * How many handlings one subscription runs at once in one partition while its downstream pushes back:
 * an additive-increase, multiplicative-decrease limit in front of each attempt of the handler.
 *
 * It starts at [max], the in-flight bound, where it holds nothing back. Each backpressure ([backpressure]) halves it,
 * down to [BackpressureOptions.minConcurrency], and each attempt that succeeds ([succeeded]) raises it by half again
 * (at least one), back to [max], or at once to [max] when no attempt waits under it. An attempt over the limit waits,
 * first come first served, until one in flight ends.
 * Entering and leaving the throttle are logged once each and told to [observer]; [state] is what the metrics read.
 */
internal class PartitionThrottle(
    private val group: String,
    private val subscription: String,
    private val partition: Int,
    private val max: Int,
    private val options: BackpressureOptions,
    private val clock: Clock,
    private val observer: Observer = Observer.NONE,
) {
    /** Guarded by the monitor. */
    private var limit = max
    private var running = 0
    private var since: Long? = null
    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

    /** Whether the partition runs fewer handlings at once than its in-flight bound. */
    val isThrottled: Boolean get() = synchronized(this) { limit < max }

    /** The throttle now, null while it holds nothing back. */
    val state: ThrottledPartition?
        get() = synchronized(this) {
            since?.let { ThrottledPartition(group, subscription, partition, limit, it) }
        }

    /** Waits for room under the limit, then takes it; [release] gives it back. */
    suspend fun acquire() {
        val turn = synchronized(this) {
            if (running < limit && waiting.isEmpty()) {
                running++
                return
            }
            CompletableDeferred<Unit>().also { waiting.addLast(it) }
        }
        try {
            turn.await()
        } catch (cancelled: CancellationException) {
            val granted = synchronized(this) { !waiting.remove(turn) }
            if (granted) {
                // The room was handed over as the wait was cancelled: pass it on.
                release()
            }
            throw cancelled
        }
    }

    /** Gives back the room [acquire] took, to the longest waiter while there is room under the limit. */
    fun release() = synchronized(this) {
        running--
        grant()
    }

    /** [ref]'s downstream pushed back: the limit halves, down to the minimum. */
    fun backpressure(ref: AggregateRef) {
        val entered = synchronized(this) {
            limit = (limit / 2).coerceAtLeast(options.minConcurrency.coerceAtMost(max))
            if (since == null && limit < max) {
                since = clock.millis()
                true
            } else {
                false
            }
        }
        if (entered) {
            logger.warn {
                "Subscription $subscription throttles partition $partition of group $group: the downstream of $ref " +
                    "pushed back; it runs at most ${state?.limit} handlings at once until handlings succeed again."
            }
            observer.observed(ObservedEvent.PartitionThrottled(group, subscription, partition, true))
        }
    }

    /** An attempt succeeded: the limit rises by half again, back to the in-flight bound. */
    fun succeeded() {
        val left = synchronized(this) {
            if (limit >= max) {
                return
            }
            // Nothing held back: the backlog the limit protected the downstream from is gone.
            limit = if (waiting.isEmpty()) max else (limit + (limit / 2).coerceAtLeast(1)).coerceAtMost(max)
            grant()
            if (limit >= max) {
                since.also { since = null }
            } else {
                null
            }
        } ?: return
        logger.info {
            "Subscription $subscription stops throttling partition $partition of group $group after " +
                "${clock.millis() - left}ms: its handlings succeed again."
        }
        observer.observed(ObservedEvent.PartitionThrottled(group, subscription, partition, false))
    }

    /** Called under the monitor: hands the room under the limit to the longest waiters. */
    private fun grant() {
        while (running < limit) {
            val next = waiting.removeFirstOrNull() ?: return
            running++
            next.complete(Unit)
        }
    }
}

/**
 * A subscription's partition throttled by backpressure: since [since] (epoch milliseconds),
 * [subscription] of the subscription [group] runs at most [limit] handlings at once in [partition].
 */
@InternalWowApi
data class ThrottledPartition(
    val group: String,
    val subscription: String,
    val partition: Int,
    val limit: Int,
    val since: Long,
)

private val logger = KotlinLogging.logger {}
